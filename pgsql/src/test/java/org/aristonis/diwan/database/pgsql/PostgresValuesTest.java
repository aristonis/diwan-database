package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.UniqueConstraintViolationException;
import org.junit.jupiter.api.Test;

/** Values written and read back on a real PostgreSQL, under several JVM time zones. */
class PostgresValuesTest {

    private static final String[] ZONES = {"UTC", "Asia/Beirut", "America/New_York"};

    private interface ZonedCheck {
        void run(Connection db) throws Exception;
    }

    private static void inEachZone(ZonedCheck check) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : ZONES) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                check.run(PostgresServer.newDatabase());
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
