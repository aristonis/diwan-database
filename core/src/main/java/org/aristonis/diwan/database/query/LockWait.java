package org.aristonis.diwan.database.query;

/**
 * What a locked select does when another transaction already holds a conflicting lock.
 *
 * <p>PostgreSQL renders all three. SQLite accepts only {@link #WAIT} and refuses the others with
 * {@link UnsupportedOperationException}.
 */
public enum LockWait {

    /** Waits until the other lock is released; the default. */
    WAIT,

    /** {@code nowait}: fails at once with a database error. */
    NOWAIT,

    /** {@code skip locked}: leaves out the rows that are locked. */
    SKIP_LOCKED
}
