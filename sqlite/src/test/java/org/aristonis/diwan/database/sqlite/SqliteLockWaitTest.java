package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.OptionalInt;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionOptions;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The lock wait of a transaction on a SQLite file: the busy timeout is set before it and put back after. */
class SqliteLockWaitTest {

    private static final Duration WAIT = Duration.ofMillis(200);
    private static final long LIMIT_MILLIS = 5_000;
    private static final String CONFIGURED_TIMEOUT = "7000";

    @TempDir
    Path folder;

    private String url() {
        return "jdbc:sqlite:" + folder.resolve("lock.db");
    }

    private DatabaseManager manager() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("sqlite")
                .database(folder.resolve("lock.db").toString()).option("busy_timeout", CONFIGURED_TIMEOUT).build();
        return ManagerFixtures.manager(config);
    }

    private static long busyTimeout(Connection db) {
        return ((Number) db.scalar("PRAGMA busy_timeout").get()).longValue();
    }

    @Test
    void busyTimesOut() throws SQLException {
        try (DatabaseManager manager = manager();
                java.sql.Connection writer = DriverManager.getConnection(url());
                Statement hold = writer.createStatement()) {
            Connection db = manager.connection("main");
            db.update("create table t (id integer primary key, v integer)");
            hold.execute("BEGIN IMMEDIATE");
            long started = System.nanoTime();

            QueryException e = assertThrows(QueryException.class, () -> db.session(held -> held.transaction(
                    tx -> tx.update("insert into t (v) values (1)"), TransactionOptions.lockWait(WAIT))));

            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(e.isConcurrencyFailure(), e.getMessage());
            assertTrue(elapsed < LIMIT_MILLIS, "took " + elapsed + " ms");
            hold.execute("ROLLBACK");
        }
    }

    @Test
    void restoredAfterCommit() {
        try (DatabaseManager manager = manager()) {
            Connection db = manager.connection("main");

            db.session(held -> {
                long before = busyTimeout(held);
                long inside = held.transaction(tx -> busyTimeout(tx), TransactionOptions.lockWait(WAIT));

                assertEquals(Long.parseLong(CONFIGURED_TIMEOUT), before);
                assertEquals(WAIT.toMillis(), inside);
                assertEquals(before, busyTimeout(held));
                return null;
            });
        }
    }

    @Test
    void restoredAfterFailure() {
        try (DatabaseManager manager = manager()) {
            Connection db = manager.connection("main");
            IllegalArgumentException boom = new IllegalArgumentException("boom");

            db.session(held -> {
                IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                        () -> held.transaction(tx -> {
                            throw boom;
                        }, TransactionOptions.lockWait(WAIT)));

                assertSame(boom, thrown);
                assertEquals(Long.parseLong(CONFIGURED_TIMEOUT), busyTimeout(held));
                return null;
            });
        }
    }

    @Test
    void aSingleStatementWaitIsNotSupported() {
        try (DatabaseManager manager = manager()) {
            Connection db = manager.connection("main");
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> db.transaction(tx -> { return tx.withStatementLockWait(WAIT.toMillis(), () -> null); }));

            assertTrue(e.getMessage().contains("TransactionOptions.lockWait"), e.getMessage());
            assertEquals(OptionalInt.of(15), manager.connection("main").exactDecimalDigits());
        }
    }
}
