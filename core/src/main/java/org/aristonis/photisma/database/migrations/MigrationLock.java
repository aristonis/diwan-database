package org.aristonis.photisma.database.migrations;

/**
 * Keeps two processes from migrating the same database at once.
 *
 * <p>The {@link Migrator} acquires the lock before it reads the migrations table for a run,
 * rollback or reset, and releases it when the call ends, whether it succeeded or failed. Status and
 * pretend calls change nothing and do not lock.
 *
 * <p>The default, {@link #NONE}, does nothing: only one process should migrate at a time. A lock
 * on the database, such as MySQL's {@code GET_LOCK} or PostgreSQL's advisory locks, can be plugged
 * in through {@link Migrator.Builder#lock(MigrationLock)}.
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
}
