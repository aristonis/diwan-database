package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Values written and read back on a real PostgreSQL, under several JVM time zones. */
class PostgresValuesTest {

    private static final String[] ZONES = {"UTC", "Asia/Beirut", "America/New_York"};

    private interface ZonedCheck {
        void run(Connection db) throws Exception;
    }

    private static void inEachZone(ZonedCheck check) throws Exception {
        inEachZone(PostgresServer::newDatabase, check);
    }

    private static void inEachZone(Supplier<Connection> database, ZonedCheck check) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : ZONES) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                check.run(database.get());
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void datesReadBackEqualInEveryZone() throws Exception {
        inEachZone(db -> {
            db.statement("create table d (ts timestamp, tz timestamptz, day date, at time)");
            LocalDateTime old = LocalDateTime.of(1500, 3, 1, 12, 0);
            LocalDateTime gap = LocalDateTime.of(2026, 3, 29, 0, 30);
            OffsetDateTime moment = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.ofHours(3));
            db.insert("insert into d values (?, ?, ?, ?)", old, moment, LocalDate.of(1500, 3, 1), LocalTime.of(23, 59, 59));
            db.insert("insert into d (ts) values (?)", gap);

            Map<String, Object> first = db.select("select * from d where ts = ?", old).get(0);
            assertEquals(old, first.get("ts"));
            assertEquals(moment.toInstant(), ((OffsetDateTime) first.get("tz")).toInstant());
            assertEquals(LocalDate.of(1500, 3, 1), first.get("day"));
            assertEquals(LocalTime.of(23, 59, 59), first.get("at"));
            assertEquals(gap, db.select("select ts from d where ts = ?", gap).get(0).get("ts"));
        });
    }

    @Test
    void infinityReadsAsTheEndsOfTime() {
        Connection db = PostgresServer.newDatabase();
        Map<String, Object> row = db.select("select 'infinity'::timestamp as hi, '-infinity'::timestamp as lo,"
                + " 'infinity'::date as day").get(0);

        assertEquals(LocalDateTime.MAX, row.get("hi"));
        assertEquals(LocalDateTime.MIN, row.get("lo"));
        assertEquals(LocalDate.MAX, row.get("day"));
    }

    @Test
    void anInstantIsTheSameInstantWhateverTheSessionZone() throws Exception {
        inEachZone(db -> {
            db.statement("create table i (at timestamptz)");
            Instant now = Instant.parse("2026-07-01T12:34:56Z");
            db.insert("insert into i values (?)", now);

            assertEquals(now, ((OffsetDateTime) db.select("select at from i").get(0).get("at")).toInstant());
        });
    }

    @Test
    void anInstantIntoATimestampColumnReadsAsItsUtcWallTimeInEveryZone() throws Exception {
        inEachZone(PostgresValuesTest::utcSession, db -> {
            db.statement("create table w (at timestamp)");

            db.insert("insert into w values (?)", Instant.parse("2026-07-01T12:34:56Z"));

            assertEquals(LocalDateTime.of(2026, 7, 1, 12, 34, 56), db.select("select at from w").get(0).get("at"));
        });
    }

    @Test
    void withoutTheTimezoneOptionAnInstantIntoATimestamptzColumnIsKept() throws Exception {
        inEachZone(db -> {
            db.statement("create table k (at timestamptz)");
            Instant moment = Instant.parse("2026-07-01T12:34:56Z");

            db.insert("insert into k values (?)", moment);

            assertEquals(moment, ((OffsetDateTime) db.select("select at from k").get(0).get("at")).toInstant());
        });
    }

    /** A connection built through the connector with {@code timezone=UTC}, in the current JVM zone. */
    private static Connection utcSession() {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource server = PostgresServer.dataSource(database);
        ConnectionConfig config = ConnectionConfig.builder("main").driver("pgsql")
                .host(server.getServerNames()[0]).port(server.getPortNumbers()[0]).database(database)
                .username("postgres").option("timezone", "UTC").build();
        return new PostgresConnector().connect(config, null);
    }

    @Test
    void aFractionBelowAMicrosecondIsRoundedToTheNearestMicrosecond() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table f (at time, ts timestamp)");
        LocalTime halfMicro = LocalTime.of(23, 59, 59, 999_999_500);
        LocalTime nanos = LocalTime.of(23, 59, 59, 999_999_900);
        LocalDateTime halfMicroStamp = LocalDateTime.of(2026, 1, 1, 23, 59, 59, 999_999_500);
        LocalDateTime nanosStamp = LocalDateTime.of(2026, 1, 1, 23, 59, 59, 999_999_900);

        db.insert("insert into f values (?, ?)", halfMicro, halfMicroStamp);
        db.insert("insert into f values (?, ?)", nanos, nanosStamp);

        // The server keeps microseconds, so a read value is at most one microsecond from the input.
        for (Map<String, Object> row : db.select("select at, ts from f")) {
            assertTrue(Duration.between((LocalTime) row.get("at"), halfMicro).abs().compareTo(Duration.ofNanos(1_000)) <= 0
                    || Duration.between((LocalTime) row.get("at"), nanos).abs().compareTo(Duration.ofNanos(1_000)) <= 0);
            assertTrue(Duration.between((LocalDateTime) row.get("ts"), halfMicroStamp).abs()
                    .compareTo(Duration.ofNanos(1_000)) <= 0
                    || Duration.between((LocalDateTime) row.get("ts"), nanosStamp).abs()
                    .compareTo(Duration.ofNanos(1_000)) <= 0);
        }
    }

    @Test
    void aNonFiniteNumericFailsNamingTheColumnWithoutTheValue() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table nn (id int, amount numeric)");
        db.statement("insert into nn values (1, 'NaN'), (2, 'Infinity'), (3, '-Infinity'), (4, 1.5)");

        for (int id = 1; id <= 3; id++) {
            int row = id;
            QueryException error = assertThrows(QueryException.class,
                    () -> db.select("select amount from nn where id = ?", row));
            assertTrue(error.getMessage().contains("amount"));
            assertFalse(error.getMessage().contains("NaN"));
            assertFalse(error.getMessage().contains("Infinity"));
        }
        // A finite numeric in the same table still reads, so the guard did not break the connection.
        assertEquals(new BigDecimal("1.5"), db.select("select amount from nn where id = ?", 4).get(0).get("amount"));
    }

    @Test
    void aNonFiniteFloatStillReadsAsADouble() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table fl (x float8)");
        db.statement("insert into fl values ('NaN')");

        assertEquals(Double.NaN, db.select("select x from fl").get(0).get("x"));
    }

    @Test
    void timestamptzAndDateInfinityReadAsTheEndsOfTime() {
        Connection db = PostgresServer.newDatabase();
        Map<String, Object> row = db.select("select 'infinity'::timestamptz as hi, '-infinity'::timestamptz as lo,"
                + " '-infinity'::date as day").get(0);

        assertEquals(OffsetDateTime.MAX, row.get("hi"));
        assertEquals(OffsetDateTime.MIN, row.get("lo"));
        assertEquals(LocalDate.MIN, row.get("day"));
    }

    @Test
    void coalesceWithANullBindingFallsBackToTheColumn() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table c (v text)");
        db.insert("insert into c values (?)", "kept");

        assertEquals("kept", db.select("select coalesce(?, v) as r from c", (Object) null).get(0).get("r"));
        assertEquals("given", db.select("select coalesce(?, v) as r from c", "given").get(0).get("r"));
    }

    @Test
    void textBindsIntoUuidJsonbAndInet() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table x (u uuid, j jsonb, ip inet)");
        String id = "0f8fad5b-d9cb-469f-a165-70867728950e";

        db.insert("insert into x values (?, ?, ?)", id, "{\"a\": 1}", "192.168.0.1");

        Map<String, Object> row = db.select("select * from x where u = ?", id).get(0);
        assertEquals(UUID.fromString(id), row.get("u"));
        assertEquals("{\"a\": 1}", row.get("j"));
        assertEquals("192.168.0.1", row.get("ip"));
    }

    @Test
    void aNullBindsIntoAnyColumnType() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table x (u uuid, j jsonb, n numeric, t timestamptz)");

        db.insert("insert into x values (?, ?, ?, ?)", null, null, null, null);

        assertEquals(1L, ((Number) db.scalar("select count(*) from x where u is null and j is null").get()).longValue());
        // PostgreSQL cannot type a bare parameter; a cast settles it, as in Laravel.
        assertEquals(Boolean.TRUE, db.select("select ?::text is null as empty", (Object) null).get(0).get("empty"));
    }

    @Test
    void numericKeepsItsScaleAndTextStaysText() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table n (amount numeric(20, 6), label text)");
        db.insert("insert into n values (?, ?)", new BigDecimal("10.10"), "x");

        Map<String, Object> row = db.select("select * from n").get(0);
        assertEquals(new BigDecimal("10.100000"), row.get("amount"));
        assertEquals("x", row.get("label"));
        assertEquals(1L, ((Number) db.scalar("select count(*) from n where label = ?", "x").get()).longValue());
    }

    @Test
    void untypedStringsNeedACastWherePostgresCannotChooseAType() {
        Connection db = PostgresServer.newDatabase();

        // Untyped text resolves to text where PostgreSQL has a preferred type, as with ||.
        assertEquals("ab", db.select("select ? || ? as joined", "a", "b").get(0).get("joined"));
        // A bare parameter tested only for null has no type to resolve to.
        assertThrows(QueryException.class, () -> db.select("select ? is null as empty", (Object) null));
        assertEquals(5L, ((Number) db.select("select ?::bigint as n", "5").get(0).get("n")).longValue());
    }

    @Test
    void aUniqueViolationHidesTheValue() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table u (email text unique)");
        db.insert("insert into u values (?)", "secret@example.com");

        UniqueConstraintViolationException error = assertThrows(UniqueConstraintViolationException.class,
                () -> db.insert("insert into u values (?)", "secret@example.com"));

        assertFalse(error.getMessage().contains("secret@example.com"));
        assertTrue(error instanceof QueryException);
    }
}
