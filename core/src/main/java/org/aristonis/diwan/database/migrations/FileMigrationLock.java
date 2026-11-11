package org.aristonis.diwan.database.migrations;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Keeps two processes from migrating one file-based database at the same time, with an operating
 * system lock on a file beside the database.
 *
 * <p>The lock file is the database file's name with {@code .migrate.lock} added, in the same
 * folder, which must exist and be writable. The lock belongs to the process that took it, so the
 * system frees it when the process dies. The file itself is never deleted: it is empty, and
 * removing it could race with a process that has just opened it. Inside one JVM a lock on the same
 * file, reached through any path or symbolic link, waits for the first one, even on the same
 * thread. Network file systems may ignore file locks; the lock then protects nothing.
 *
 * <p>The database path is asked for when {@link #acquire()} runs, not when the lock is created, so
 * a database that is opened lazily can be named. The lock uses {@code java.io} and
 * {@link FileChannel} only, so it works on Android.
 *
 * <p>An instance holds the lock for one caller at a time: a second {@link #acquire()} on the same
 * instance while it is held fails at once. Separate instances on one database file queue instead.
 */
public final class FileMigrationLock implements MigrationLock {

    /** How long {@link #acquire()} waits by default, in seconds. */
    public static final long DEFAULT_WAIT_SECONDS = 60;

    private static final long POLL_MILLIS = 200;
    private static final String LOCK_SUFFIX = ".migrate.lock";

    // One entry per lock file used by this JVM; never removed, the count is bounded by databases used.
    private static final ConcurrentMap<File, Semaphore> GUARDS = new ConcurrentHashMap<>();

    private final Supplier<File> database;
    private final long waitMillis;
    private Semaphore heldGuard;
    private RandomAccessFile heldFile;
    private FileLock heldLock;

    /**
     * Creates a lock with the default wait.
     *
     * @param database supplies the database file when the lock is acquired, never {@code null}
     * @throws NullPointerException if {@code database} is {@code null}
     */
    public FileMigrationLock(Supplier<File> database) {
        this(database, DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Creates a lock with a longest wait.
     *
     * @param database supplies the database file when the lock is acquired, never {@code null}
     * @param wait how long {@link #acquire()} waits for another holder, 0 or more
     * @param unit the unit of {@code wait}, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code wait} is negative
     */
    public FileMigrationLock(Supplier<File> database, long wait, TimeUnit unit) {
        this.database = Objects.requireNonNull(database, "database");
        if (wait < 0) {
            throw new IllegalArgumentException("The wait must not be negative");
        }
        this.waitMillis = Objects.requireNonNull(unit, "unit").toMillis(wait);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if this lock is already held, another holder still has the lock
     *     file when the wait runs out, the wait is interrupted (the thread's interrupt flag is
     *     kept), or the lock file cannot be opened or locked, for example when its folder is missing
     *     or read-only
     */
    @Override
    public synchronized void acquire() {
        if (heldGuard != null) {
            throw new IllegalStateException("The migration lock is already held");
        }
        // nanoTime, so a wall-clock jump neither ends the wait early nor stretches it.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        File lockFile = resolveLockFile();
        Semaphore guard = GUARDS.computeIfAbsent(lockFile, key -> new Semaphore(1));
        boolean isGuarded = false;
        boolean isTaken = false;
        RandomAccessFile file = null;
        try {
            if (!guard.tryAcquire(remainingMillis(deadline), TimeUnit.MILLISECONDS)) {
                throw timeout(lockFile);
            }
            isGuarded = true;
            file = new RandomAccessFile(lockFile, "rw");
            FileLock fileLock = pollLock(file.getChannel(), lockFile, deadline);
            heldGuard = guard;
            heldFile = file;
            heldLock = fileLock;
            isTaken = true;
        } catch (IOException e) {
            throw new IllegalStateException("The migration lock " + lockFile + " could not be taken", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the migration lock", e);
        } finally {
            if (!isTaken) {
                closeQuietly(file);
                if (isGuarded) {
                    guard.release();
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing when this lock is not held.
     *
     * @throws IllegalStateException if the file lock could not be released or the lock file could
     *     not be closed; the lock is given back for other holders in every case
     */
    @Override
    public synchronized void release() {
        Semaphore guard = heldGuard;
        RandomAccessFile file = heldFile;
        FileLock fileLock = heldLock;
        heldGuard = null;
        heldFile = null;
        heldLock = null;
        if (guard == null) {
            return;
        }
        IOException problem = null;
        try {
            fileLock.release();
        } catch (IOException e) {
            problem = e;
        }
        try {
            file.close();
        } catch (IOException e) {
            if (problem == null) {
                problem = e;
            } else {
                problem.addSuppressed(e);
            }
        } finally {
            guard.release();
        }
        if (problem != null) {
            throw new IllegalStateException("The migration lock could not be released", problem);
        }
    }

    /**
     * Names the lock file of a database file.
     *
     * @param database the database file, never {@code null}
     * @return the file beside it with {@code .migrate.lock} added to its name, never {@code null}
     */
    static File lockFileOf(File database) {
        return new File(database.getParentFile(), database.getName() + LOCK_SUFFIX);
    }

    // The canonical path makes two spellings of one database, a symlink for instance, share one guard.
    private File resolveLockFile() {
        File file = Objects.requireNonNull(database.get(), "The database file is missing");
        try {
            return lockFileOf(file.getCanonicalFile());
        } catch (IOException e) {
            throw new IllegalStateException("The migration lock " + lockFileOf(file) + " could not be taken", e);
        }
    }

    // A lock held in this JVM by another path to the same file reports as overlapping: keep waiting.
    private FileLock pollLock(FileChannel channel, File lockFile, long deadline) throws IOException,
            InterruptedException {
        while (true) {
            try {
                FileLock fileLock = channel.tryLock();
                if (fileLock != null) {
                    return fileLock;
                }
            } catch (OverlappingFileLockException e) {
                // Held inside this JVM; the same as held by another process.
            }
            long remaining = remainingMillis(deadline);
            if (remaining <= 0) {
                throw timeout(lockFile);
            }
            Thread.sleep(Math.min(POLL_MILLIS, remaining));
        }
    }

    private IllegalStateException timeout(File lockFile) {
        return new IllegalStateException("The migration lock " + lockFile
                + " is held by another process; waited " + waitMillis + " ms");
    }

    private static long remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static void closeQuietly(RandomAccessFile file) {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (IOException e) {
            // Nothing more can be done; the descriptor is freed when the file is collected.
        }
    }
}
