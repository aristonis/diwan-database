package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionOptions;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** The lock wait of a transaction against a real PostgreSQL server: one session holds a row, another waits. */
class PostgresLockWaitTest {

    private static final int WAIT_SECONDS = 30;
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final long MIN_WAIT_MILLIS = 500;
    private static final long MAX_WAIT_MILLIS = 15_000;
    private static final long RELEASE_AFTER_MILLIS = 500;
    private static final String UPDATE_ROW = "update items set name = 'x' where id = 1";

    private static Connection items() {
        Connection db = PostgresServer.newDatabase();
        PostgresSchema.of(db).create("items", t -> {
            t.id();
            t.string("name");
        });
        db.insert("insert into items (name) values ('a')");
        return db;
    }

    /** Opens a connection through the connector, so the {@code lock_wait} option is applied to its URL. */
    private static Connection configured(String lockWait) {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource source = PostgresServer.dataSource(database);
        Connection setup = Connection.builder().name("setup").dataSource(source).build();
        PostgresSchema.of(setup).create("items", t -> {
            t.id();
            t.string("name");
        });
        setup.insert("insert into items (name) values ('a')");
        String url = "jdbc:postgresql://127.0.0.1:" + source.getPortNumbers()[0] + "/" + database;
        ConnectionConfig config = ConnectionConfig.builder("main").driver("pgsql").url(url).username("postgres")
                .option("lock_wait", lockWait).build();
        return new PostgresConnector().connect(config, null);
    }

    /** A second session that holds the row lock until released. */
    private static final class Holder implements AutoCloseable {
        private final ExecutorService pool = Executors.newSingleThreadExecutor();
        private final CountDownLatch locked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> work;

        Holder(Connection db) throws InterruptedException {
            work = pool.submit(() -> db.transaction(c -> {
                c.update(UPDATE_ROW);
                locked.countDown();
                try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            if (!locked.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The holder never took the lock");
            }
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() throws ExecutionException, TimeoutException {
            release();
            try {
                work.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    @Test
    @SuppressWarnings("try") // the holder only keeps the row locked
    void transactionTimesOut() throws Exception {
        Connection db = items();
        try (Holder holder = new Holder(db)) {
            long started = System.nanoTime();

            QueryException e = assertThrows(QueryException.class,
                    () -> db.transaction(tx -> tx.update(UPDATE_ROW), TransactionOptions.lockWait(ONE_SECOND)));

            long elapsed = elapsedMillis(started);
            assertTrue(e.isConcurrencyFailure(), e.getMessage());
            assertTrue(elapsed >= MIN_WAIT_MILLIS && elapsed < MAX_WAIT_MILLIS, "took " + elapsed + " ms");
        }
    }

    @Test
    void retriesAfterTimeout() throws Exception {
        Connection db = items();
        try (Holder holder = new Holder(db)) {
            ExecutorService releaser = Executors.newSingleThreadExecutor();
            try {
                releaser.submit(() -> {
                    Thread.sleep(RELEASE_AFTER_MILLIS);
                    holder.release();
                    return null;
                });

                int changed = db.transaction(tx -> tx.update(UPDATE_ROW),
                        TransactionOptions.lockWait(Duration.ofMillis(300)).withAttempts(5));

                assertEquals(1, changed);
            } finally {
                releaser.shutdownNow();
            }
        }
    }

    @Test
    void settingDoesNotLeak() {
        Connection db = items();

        db.session(held -> {
            String before = String.valueOf(held.scalar("show lock_timeout").get());
            String inside = held.transaction(tx -> String.valueOf(tx.scalar("show lock_timeout").get()),
                    TransactionOptions.lockWait(ONE_SECOND));
            String after = String.valueOf(held.scalar("show lock_timeout").get());

            assertEquals("1s", inside);
            assertEquals(before, after);
            return null;
        });
    }

    @Test
    @SuppressWarnings("try") // the holder only keeps the row locked
    void configDefault() throws Exception {
        Connection db = configured("1s");
        try (Holder holder = new Holder(db)) {
            long started = System.nanoTime();

            QueryException e = assertThrows(QueryException.class, () -> db.transaction(tx -> tx.update(UPDATE_ROW)));

            long elapsed = elapsedMillis(started);
            assertTrue(e.isConcurrencyFailure(), e.getMessage());
            assertTrue(elapsed >= MIN_WAIT_MILLIS && elapsed < MAX_WAIT_MILLIS, "took " + elapsed + " ms");
        }
    }

    @Test
    void transactionOverridesConfig() {
        Connection db = configured("5s");

        db.session(held -> {
            String inside = held.transaction(tx -> String.valueOf(tx.scalar("show lock_timeout").get()),
                    TransactionOptions.lockWait(ONE_SECOND));
            String after = String.valueOf(held.scalar("show lock_timeout").get());

            assertEquals("1s", inside);
            assertEquals("5s", after);
            return null;
        });
    }
}
