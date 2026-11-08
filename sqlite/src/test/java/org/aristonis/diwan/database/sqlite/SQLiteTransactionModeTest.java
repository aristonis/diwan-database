package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code transaction_mode} and {@code busy_timeout} options change how writers meet on a file database. */
class SQLiteTransactionModeTest {

    private static final int WAIT_SECONDS = 15;
    private static final String BUSY_TIMEOUT_MILLIS = "5000";

    @TempDir
    Path folder;

    private ConnectionConfig config(String mode, String busyTimeout) {
        ConnectionConfig.Builder builder = ConnectionConfig.builder("main").driver("sqlite")
                .database(folder.resolve("modes.db").toString());
        if (mode != null) {
            builder.option("transaction_mode", mode);
        }
        if (busyTimeout != null) {
            builder.option("busy_timeout", busyTimeout);
        }
        return builder.build();
    }

    private static Connection prepared(DatabaseManager manager) {
        Connection db = manager.connection("main");
        db.update("create table counter (id integer primary key, v integer not null)");
        db.insert("insert into counter (id, v) values (1, 0)");
        return db;
    }

    private static long value(Connection db) {
        return ((Number) db.scalar("select v from counter where id = 1").get()).longValue();
    }

    private static boolean isConcurrency(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DriverException && ((DriverException) t).isConcurrencyFailure()) {
                return true;
            }
            if (t instanceof QueryException && ((QueryException) t).isConcurrencyFailure()) {
                return true;
            }
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void readThenIncrement(Connection db, long by) {
        db.transaction(tx -> {
            long read = ((Number) tx.scalar("select v from counter where id = 1").get()).longValue();
            tx.update("update counter set v = ? where id = 1", read + by);
            return null;
        });
    }

    @Test
    void immediateWritersQueue() throws Exception {
        try (DatabaseManager manager = ManagerFixtures.manager(config("immediate", BUSY_TIMEOUT_MILLIS))) {
            Connection db = prepared(manager);
            CountDownLatch firstHolds = new CountDownLatch(1);
            CountDownLatch secondAbout = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> db.transaction(tx -> {
                    long read = ((Number) tx.scalar("select v from counter where id = 1").get()).longValue();
                    firstHolds.countDown();
                    await(secondAbout);
                    tx.update("update counter set v = ? where id = 1", read + 1);
                    return null;
                }));
                await(firstHolds);
                Future<?> second = pool.submit(() -> {
                    secondAbout.countDown();
                    readThenIncrement(db, 1);
                });

                first.get(WAIT_SECONDS, TimeUnit.SECONDS);
                second.get(WAIT_SECONDS, TimeUnit.SECONDS);

                assertEquals(2L, value(db));
            } finally {
                secondAbout.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void deferredShowsTheBusyTrap() throws Exception {
        try (DatabaseManager manager = ManagerFixtures.manager(config("deferred", BUSY_TIMEOUT_MILLIS))) {
            Connection db = prepared(manager);
            CountDownLatch bothRead = new CountDownLatch(2);
            CountDownLatch firstWrote = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> db.transaction(tx -> {
                    tx.scalar("select v from counter where id = 1");
                    bothRead.countDown();
                    await(bothRead);
                    tx.update("update counter set v = 1 where id = 1");
                    firstWrote.countDown();
                    await(secondDone);
                    return null;
                }));
                Future<?> second = pool.submit(() -> {
                    try {
                        db.transaction(tx -> {
                            tx.scalar("select v from counter where id = 1");
                            bothRead.countDown();
                            await(bothRead);
                            await(firstWrote);
                            tx.update("update counter set v = 2 where id = 1");
                            return null;
                        });
                    } finally {
                        secondDone.countDown();
                    }
                });

                ExecutionException e = assertThrows(ExecutionException.class,
                        () -> second.get(WAIT_SECONDS, TimeUnit.SECONDS));
                first.get(WAIT_SECONDS, TimeUnit.SECONDS);

                assertTrue(isConcurrency(e.getCause()), String.valueOf(e.getCause()));
                assertEquals(1L, value(db));
            } finally {
                firstWrote.countDown();
                secondDone.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void rollbackUnderContention() throws Exception {
        try (DatabaseManager manager = ManagerFixtures.manager(config("immediate", BUSY_TIMEOUT_MILLIS))) {
            Connection db = prepared(manager);
            CountDownLatch firstHolds = new CountDownLatch(1);
            CountDownLatch secondAbout = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> db.transaction(tx -> {
                    tx.update("update counter set v = 100 where id = 1");
                    firstHolds.countDown();
                    await(secondAbout);
                    throw new IllegalStateException("undo");
                }));
                await(firstHolds);
                Future<?> second = pool.submit(() -> {
                    secondAbout.countDown();
                    db.transaction(tx -> tx.update("update counter set v = v + 1 where id = 1"));
                });

                ExecutionException e = assertThrows(ExecutionException.class,
                        () -> first.get(WAIT_SECONDS, TimeUnit.SECONDS));
                second.get(WAIT_SECONDS, TimeUnit.SECONDS);

                assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
                assertEquals(1L, value(db));
                readThenIncrement(db, 1);
                assertEquals(2L, value(db));
            } finally {
                secondAbout.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void busyTimeoutReachesAFileDatabase() throws Exception {
        long shortWaitMillis = 200;
        long limitMillis = 2000;
        try (DatabaseManager manager = ManagerFixtures.manager(config("immediate", Long.toString(shortWaitMillis)))) {
            Connection db = prepared(manager);
            String url = "jdbc:sqlite:" + folder.resolve("modes.db");
            try (java.sql.Connection other = DriverManager.getConnection(url);
                    Statement statement = other.createStatement()) {
                statement.execute("begin immediate");

                long started = System.nanoTime();
                assertThrows(RuntimeException.class, () -> readThenIncrement(db, 1));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertTrue(elapsedMillis < limitMillis, "waited " + elapsedMillis + " ms");
                statement.execute("rollback");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            assertEquals(0L, value(db));
        }
    }

    @Test
    void failedCommitLeavesTheSessionUsable() throws Exception {
        try (DatabaseManager manager = ManagerFixtures.manager(config(null, "200"))) {
            Connection db = prepared(manager);
            String url = "jdbc:sqlite:" + folder.resolve("modes.db");
            try (java.sql.Connection reader = DriverManager.getConnection(url)) {
                reader.setAutoCommit(false);
                try (Statement cursor = reader.createStatement()) {
                    java.sql.ResultSet open = cursor.executeQuery("select id from counter");
                    assertTrue(open.next());

                    RuntimeException failure = assertThrows(RuntimeException.class, () -> db.transaction(tx -> {
                        tx.update("update counter set v = 7 where id = 1");
                        return null;
                    }));

                    assertTrue(isConcurrency(failure), String.valueOf(failure));
                    assertEquals(0L, value(db));
                }
                reader.rollback();
            }

            readThenIncrement(db, 1);

            assertEquals(1L, value(db));
            try (java.sql.Connection other = DriverManager.getConnection(url);
                    Statement write = other.createStatement()) {
                write.executeUpdate("update counter set v = 5 where id = 1");
            }
            assertEquals(5L, value(db));
        }
    }
}
