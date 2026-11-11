package org.aristonis.diwan.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMigrationLockTest {

    private static final long SHORT_WAIT_MILLIS = 100;
    private static final long LONG_WAIT_SECONDS = 10;

    @TempDir
    File folder;

    private FileMigrationLock lockOn(File database, long millis) {
        return new FileMigrationLock(() -> database, millis, TimeUnit.MILLISECONDS);
    }

    @Test
    void theLockFileSitsBesideTheDatabase() {
        File database = new File(folder, "main.db");

        assertEquals(new File(folder, "main.db.migrate.lock"), FileMigrationLock.lockFileOf(database));
    }

    @Test
    void aSecondLockOnTheSameDatabaseTimesOutNamingTheLockFile() {
        File database = new File(folder, "main.db");
        FileMigrationLock first = lockOn(database, SHORT_WAIT_MILLIS);
        FileMigrationLock second = lockOn(database, SHORT_WAIT_MILLIS);
        first.acquire();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, second::acquire);

            assertTrue(failure.getMessage().contains("main.db.migrate.lock"), failure.getMessage());
            assertTrue(failure.getMessage().contains("is held by another process; waited 100 ms"),
                    failure.getMessage());
        } finally {
            first.release();
        }
    }

    @Test
    void aWaitingThreadTakesTheLockWhenTheHolderReleases() throws Exception {
        File database = new File(folder, "main.db");
        FileMigrationLock first = lockOn(database, SHORT_WAIT_MILLIS);
        FileMigrationLock second = new FileMigrationLock(() -> database, LONG_WAIT_SECONDS, TimeUnit.SECONDS);
        first.acquire();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                second.acquire();
                second.release();
            } catch (Throwable e) {
                problem.set(e);
            }
        });
        waiter.start();
        Thread.sleep(300);

        first.release();
        waiter.join(TimeUnit.SECONDS.toMillis(LONG_WAIT_SECONDS));

        assertNull(problem.get());
        assertTrue(!waiter.isAlive(), "the waiter finished");
    }

    @Test
    void anInterruptedWaitFailsKeepingTheFlagAndFreesTheLock() throws Exception {
        File database = new File(folder, "main.db");
        FileMigrationLock holder = lockOn(database, SHORT_WAIT_MILLIS);
        FileMigrationLock waiting = new FileMigrationLock(() -> database, LONG_WAIT_SECONDS, TimeUnit.SECONDS);
        holder.acquire();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        AtomicReference<Boolean> isFlagKept = new AtomicReference<>(false);
        Thread waiter = new Thread(() -> {
            try {
                waiting.acquire();
            } catch (IllegalStateException e) {
                problem.set(e);
                isFlagKept.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        Thread.sleep(300);
        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(LONG_WAIT_SECONDS));
        holder.release();

        assertNotNull(problem.get());
        assertEquals("Interrupted while waiting for the migration lock", problem.get().getMessage());
        assertTrue(isFlagKept.get(), "the interrupt flag is kept");
        FileMigrationLock after = lockOn(database, SHORT_WAIT_MILLIS);
        after.acquire();
        after.release();
    }

    @Test
    void theSameThreadCannotNestAndTimesOut() {
        File database = new File(folder, "main.db");
        FileMigrationLock first = lockOn(database, SHORT_WAIT_MILLIS);
        FileMigrationLock nested = lockOn(database, SHORT_WAIT_MILLIS);
        first.acquire();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, nested::acquire);

            assertTrue(failure.getMessage().contains("is held by another process"), failure.getMessage());
        } finally {
            first.release();
        }
    }

    @Test
    void aSymlinkedPathSharesTheLock() throws IOException {
        File real = new File(folder, "real");
        assertTrue(real.mkdir());
        File link = new File(folder, "link");
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath());
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here");
        }
        FileMigrationLock direct = lockOn(new File(real, "main.db"), SHORT_WAIT_MILLIS);
        FileMigrationLock viaLink = lockOn(new File(link, "main.db"), SHORT_WAIT_MILLIS);
        direct.acquire();
        try {
            assertThrows(IllegalStateException.class, viaLink::acquire);
        } finally {
            direct.release();
        }
    }

    @Test
    void aMissingFolderFailsNamingTheLockFileWithTheCause() {
        File database = new File(new File(folder, "absent"), "main.db");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> lockOn(database, SHORT_WAIT_MILLIS).acquire());

        assertTrue(failure.getMessage().startsWith("The migration lock "), failure.getMessage());
        assertTrue(failure.getMessage().endsWith("main.db.migrate.lock could not be taken"), failure.getMessage());
        assertNotNull(failure.getCause());
    }

    @Test
    void aReadOnlyFolderFailsNamingTheLockFileAndFreesTheGuard() {
        File readOnly = new File(folder, "readonly");
        assertTrue(readOnly.mkdir());
        File database = new File(readOnly, "main.db");
        assertTrue(readOnly.setWritable(false));
        try {
            assumeFalse(readOnly.canWrite(), "folder permissions are not enforced here, for example as root");

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> lockOn(database, SHORT_WAIT_MILLIS).acquire());
            assertTrue(failure.getMessage().contains("could not be taken"), failure.getMessage());
            assertNotNull(failure.getCause());

            assertTrue(readOnly.setWritable(true));
            FileMigrationLock after = lockOn(database, SHORT_WAIT_MILLIS);
            after.acquire();
            after.release();
        } finally {
            readOnly.setWritable(true);
        }
    }

    @Test
    void acquiringTwiceOnOneInstanceFails() {
        FileMigrationLock lock = lockOn(new File(folder, "main.db"), SHORT_WAIT_MILLIS);
        lock.acquire();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, lock::acquire);

            assertEquals("The migration lock is already held", failure.getMessage());
        } finally {
            lock.release();
        }
    }

    @Test
    void releasingWhenNotHeldDoesNothing() {
        FileMigrationLock lock = lockOn(new File(folder, "main.db"), SHORT_WAIT_MILLIS);

        lock.release();
        lock.acquire();
        lock.release();
        lock.release();
    }

    @Test
    void theLockFileIsLeftBehindAfterRelease() {
        File database = new File(folder, "main.db");
        FileMigrationLock lock = lockOn(database, SHORT_WAIT_MILLIS);

        lock.acquire();
        lock.release();

        assertTrue(FileMigrationLock.lockFileOf(database).isFile());
    }

    @Test
    void aNegativeWaitIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new FileMigrationLock(() -> new File(folder, "main.db"), -1, TimeUnit.SECONDS));
    }
}
