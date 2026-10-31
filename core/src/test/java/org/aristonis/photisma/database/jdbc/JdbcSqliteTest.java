package org.aristonis.photisma.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.photisma.database.Connection;
import org.aristonis.photisma.database.QueryException;
import org.aristonis.photisma.database.UniqueConstraintViolationException;
import org.aristonis.photisma.database.driver.DriverCapability;
import org.aristonis.photisma.database.driver.DriverInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcSqliteTest {

    @TempDir
    Path folder;

    private CountingDataSource dataSource;
    private Connection db;

    @BeforeEach
    void createDatabase() {
        dataSource = new CountingDataSource(folder.resolve("test.db").toString());
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db.statement("create table users (id integer primary key, email text not null unique, note text)");
    }

    @AfterEach
    void everyConnectionWentBackWithAutoCommitOn() {
        assertEquals(0, dataSource.openConnections());
        assertEquals(0, dataSource.returnedWithoutAutoCommit());
    }

    @Test
    void writesAndReadsRowsInOrderWithColumnsInSelectOrder() {
        assertTrue(db.insert("insert into users (email, note) values (?, ?)", "a@x", "first"));
        assertTrue(db.insert("insert into users (email, note) values (?, ?)", "b@x", null));

        List<Map<String, Object>> rows = db.select("select note, email from users order by id");

        assertEquals(2, rows.size());
        assertEquals(java.util.Arrays.asList("note", "email"), new ArrayList<>(rows.get(0).keySet()));
        assertEquals("first", rows.get(0).get("note"));
        assertEquals(null, rows.get(1).get("note"));
    }

    @Test
    void countsChangedRows() {
        db.insert("insert into users (email) values ('a@x')");
        db.insert("insert into users (email) values ('b@x')");

        assertEquals(2, db.update("update users set note = ?", "n"));
        assertEquals(1, db.delete("delete from users where email = ?", "a@x"));
    }

    @Test
    void readsSingleRowsAndValues() {
        db.insert("insert into users (email) values ('a@x')");

        assertEquals("a@x", db.selectOne("select email from users").orElseThrow().get("email"));
        assertEquals(1, ((Number) db.scalar("select count(*) from users").orElseThrow()).intValue());
        assertFalse(db.selectOne("select * from users where id = -1").isPresent());
    }

    @Test
    void roundTripsEverySupportedBindingType() {
        db.statement("create table vals (n text, b boolean, i integer, l integer, d real, dec numeric,"
                + " big integer, s text, bytes blob, day text, moment text)");

        db.insert("insert into vals values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", null, true, 7, 8L, 1.5d,
                new BigDecimal("2.25"), BigInteger.valueOf(42), "text", new byte[] {1, 2, 3},
                LocalDate.of(2026, 10, 30), Instant.parse("2026-10-30T12:00:00Z"));
        Map<String, Object> row = db.selectOne("select * from vals").orElseThrow();

        assertEquals(null, row.get("n"));
        assertEquals(1, ((Number) row.get("b")).intValue());
        assertEquals(7, ((Number) row.get("i")).intValue());
        assertEquals(8L, ((Number) row.get("l")).longValue());
        assertEquals(1.5d, ((Number) row.get("d")).doubleValue());
        assertEquals(42, ((Number) row.get("big")).intValue());
        assertEquals(2.25d, ((Number) row.get("dec")).doubleValue());
        assertEquals("text", row.get("s"));
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) row.get("bytes"));
        assertTrue(String.valueOf(row.get("day")).contains("2026-10-30"));
        assertTrue(String.valueOf(row.get("moment")).contains("2026-10-30"));
    }

    @Test
    void reportsADuplicateKeyAsAUniqueViolationWithoutTheValueInTheMessage() {
        db.insert("insert into users (email) values (?)", "secret@x");

        UniqueConstraintViolationException error = assertThrows(UniqueConstraintViolationException.class,
                () -> db.insert("insert into users (email) values (?)", "secret@x"));

        assertFalse(error.getMessage().contains("secret@x"));
        assertFalse(error.getCause().getMessage().contains("secret@x"));
    }

    @Test
    void reportsBadSqlAsAQueryException() {
        QueryException error = assertThrows(QueryException.class, () -> db.select("select * from missing"));

        assertEquals("select * from missing", error.getSql());
    }

    @Test
    void refusesTwoColumnsWithTheSameLabel() {
        QueryException error = assertThrows(QueryException.class, () -> db.select("select 1 as a, 2 as a"));

        assertTrue(error.getCause().getMessage().contains("a"));
    }

    @Test
    void commitsATransactionAndUndoesAFailedOne() {
        db.transaction(tx -> tx.insert("insert into users (email) values ('kept@x')"));
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.insert("insert into users (email) values ('gone@x')");
            throw new IllegalStateException("rule");
        }));

        assertEquals(1, ((Number) db.scalar("select count(*) from users").orElseThrow()).intValue());
    }

    @Test
    void undoesOnlyTheInnerPartThroughARealSavepoint() {
        db.transaction(tx -> {
            tx.insert("insert into users (email) values ('outer@x')");
            try {
                tx.transaction(inner -> {
                    inner.insert("insert into users (email) values ('inner@x')");
                    throw new IllegalStateException("inner rule");
                });
            } catch (IllegalStateException expected) {
                // carry on with the outer work
            }
            tx.transaction(inner -> inner.insert("insert into users (email) values ('second@x')"));
            return null;
        });

        assertEquals(2, ((Number) db.scalar("select count(*) from users").orElseThrow()).intValue());
    }

    @Test
    void reportsSqliteAndItsCapabilities() {
        DriverInfo info = new JdbcDriver(new DataSourceConnectionProvider(dataSource)).info();

        assertEquals("SQLite", info.databaseName());
        assertFalse(info.databaseVersion().trim().isEmpty());
        assertTrue(info.supports(DriverCapability.SAVEPOINT_RELEASE));
        assertTrue(info.supports(DriverCapability.TRANSACTIONAL_DDL));
    }

    @Test
    void joinsATransactionOwnedByTheProviderInsteadOfEndingIt() throws Exception {
        java.sql.Connection outer = dataSource.getConnection();
        outer.setAutoCommit(false);
        JdbcConnectionProvider external = new JdbcConnectionProvider() {
            @Override
            public java.sql.Connection acquire() {
                return outer;
            }

            @Override
            public void release(java.sql.Connection connection) {
                // the outer owner keeps it
            }

            @Override
            public boolean inExternalTransaction(java.sql.Connection connection) {
                return true;
            }
        };
        Connection joined = Connection.builder().name("joined").connectionProvider(external).build();

        joined.transaction(tx -> tx.insert("insert into users (email) values ('inside@x')"));
        assertFalse(outer.getAutoCommit());
        outer.rollback();
        outer.setAutoCommit(true);
        outer.close();

        assertEquals(0, ((Number) db.scalar("select count(*) from users").orElseThrow()).intValue());
    }

    @Test
    void servesManyThreadsAtOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> work = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int thread = t;
            work.add(pool.submit(() -> {
                for (int i = 0; i < 200; i++) {
                    db.insert("insert into users (email) values (?)", thread + "-" + i + "@x");
                }
            }));
        }
        for (Future<?> f : work) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1600, ((Number) db.scalar("select count(*) from users").orElseThrow()).intValue());
    }

    @Test
    void keepsUnsavedWorkInvisibleToOtherConnections() {
        Connection other = Connection.builder().name("other").dataSource(dataSource).build();

        db.transaction(tx -> {
            tx.insert("insert into users (email) values ('pending@x')");
            int seenElsewhere = ((Number) other.scalar("select count(*) from users").orElseThrow()).intValue();
            assertEquals(0, seenElsewhere);
            return null;
        });
    }

    @Test
    void roundTripsTheRemainingNumberAndTimeTypes() {
        db.statement("create table more (tiny integer, small integer, f real, t text, dt text, odt text)");

        db.insert("insert into more values (?, ?, ?, ?, ?, ?)", (byte) 3, (short) 4, 2.5f,
                java.time.LocalTime.of(9, 30), java.time.LocalDateTime.of(2026, 10, 31, 9, 30),
                java.time.OffsetDateTime.of(2026, 10, 31, 9, 30, 0, 0, java.time.ZoneOffset.ofHours(3)));
        Map<String, Object> row = db.selectOne("select * from more").orElseThrow();

        assertEquals(3, ((Number) row.get("tiny")).intValue());
        assertEquals(4, ((Number) row.get("small")).intValue());
        assertEquals(2.5f, ((Number) row.get("f")).floatValue());
        assertTrue(String.valueOf(row.get("t")).startsWith("09:30"));
        assertTrue(String.valueOf(row.get("dt")).startsWith("2026-10-31"));
        assertTrue(String.valueOf(row.get("odt")).contains("2026-10-31"));
    }

    @Test
    void returnsBytesThatStayUsableAfterTheConnectionWentBack() {
        db.statement("create table files (data blob)");
        db.insert("insert into files values (?)", (Object) new byte[] {7, 8, 9});

        byte[] data = (byte[]) db.scalar("select data from files").orElseThrow();

        assertEquals(0, dataSource.openConnections());
        assertArrayEquals(new byte[] {7, 8, 9}, data);
    }
}
