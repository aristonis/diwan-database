package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** The migration lock is not cut short by a {@code lock_wait} configured on its data source. */
class PostgresMigrationLockWaitTest {

    private static final int WAIT_SECONDS = 30;
    private static final long CONFIGURED_WAIT_MILLIS = 200;
    private static final long RELEASE_AFTER_MILLIS = 600;

    @Test
    void acquireWaitsLongerThanTheConfiguredLockWait() throws Exception {
        String database = PostgresServer.newDatabaseName();
        PGSimpleDataSource plain = PostgresServer.dataSource(database);
        PGSimpleDataSource configured = PostgresServer.dataSource(database);
        configured.setOptions("-c lock_timeout=" + CONFIGURED_WAIT_MILLIS);
        PostgresMigrationLock holder = new PostgresMigrationLock(plain);
        PostgresMigrationLock waiter = new PostgresMigrationLock(configured, PostgresMigrationLock.DEFAULT_NAME,
                WAIT_SECONDS, TimeUnit.SECONDS);
        holder.acquire();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> release = pool.submit(() -> {
                Thread.sleep(RELEASE_AFTER_MILLIS);
                holder.release();
                return null;
            });
            long started = System.nanoTime();

            assertDoesNotThrow(waiter::acquire);

            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            release.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(elapsed > CONFIGURED_WAIT_MILLIS, "took " + elapsed + " ms");
        } finally {
            pool.shutdownNow();
            waiter.release();
            holder.release();
        }
    }

    @Test
    void anInterruptStopsTheWaitAndKeepsTheFlag() throws Exception {
        String database = PostgresServer.newDatabaseName();
        PostgresMigrationLock holder = new PostgresMigrationLock(PostgresServer.dataSource(database));
        PostgresMigrationLock waiter = new PostgresMigrationLock(PostgresServer.dataSource(database),
                PostgresMigrationLock.DEFAULT_NAME, WAIT_SECONDS, TimeUnit.SECONDS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean isFlagKept = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                waiter.acquire();
            } catch (IllegalStateException e) {
                failure.set(e);
                isFlagKept.set(Thread.currentThread().isInterrupted());
            }
        });
        holder.acquire();
        try {
            thread.start();
            Thread.sleep(RELEASE_AFTER_MILLIS);

            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            assertFalse(thread.isAlive());
            assertNotNull(failure.get());
            assertTrue(failure.get().getMessage().contains("Interrupted"), failure.get().getMessage());
            assertTrue(isFlagKept.get());
        } finally {
            thread.interrupt();
            holder.release();
        }
    }
}
