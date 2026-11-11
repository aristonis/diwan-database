package org.aristonis.diwan.database.migrations;

/**
 * Keeps two processes from migrating the same database at once.
 *
 * <p>The {@link Migrator} acquires the lock before it reads the migrations table for a run,
 * rollback or reset, and releases it when the call ends, whether it succeeded or failed. Status and
 * pretend calls change nothing and do not lock.
 *
 * <p>The default, {@link #NONE}, does nothing: only one process should migrate at a time. A lock
 * on the database, such as MySQL's {@code GET_LOCK} or PostgreSQL's advisory locks, can be plugged
 * in through {@link Migrator.Builder#lock(MigrationLock)}. {@link FileMigrationLock} locks a file
 * beside a file-based database such as SQLite, and {@code PostgresMigrationLock} in the PostgreSQL
 * module uses a session advisory lock.
 */
public interface MigrationLock {

    /** A lock that does nothing. */
    MigrationLock NONE = new MigrationLock() {
        @Override
        public void acquire() {
        }

        @Override
        public void release() {
        }
    };

    /**
     * Waits until this process holds the lock.
     *
     * @throws RuntimeException if the lock cannot be taken; the migrator then does nothing
     */
    void acquire();

    /**
     * Gives the lock back.
     *
     * @throws RuntimeException if releasing fails; reported after the migrator's own failure, if any
     */
    void release();

    /**
     * Lets the lock add a note to a failure of the work it guarded, before {@link #release()} runs.
     *
     * <p>The migrator calls this when a run, rollback or reset fails with a {@link RuntimeException},
     * while the lock is still held. A lock that can say why the failure happened, for example a
     * pool too small to give the lock its own session, adds that as a suppressed exception. The
     * default does nothing.
     *
     * @param failure the failure of the guarded work, never {@code null}; the lock may add a
     *     suppressed exception to it but must not throw
     */
    default void explain(RuntimeException failure) {
    }
}
