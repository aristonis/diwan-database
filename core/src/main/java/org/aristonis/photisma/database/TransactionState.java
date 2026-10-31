package org.aristonis.photisma.database;

import java.util.HashMap;
import java.util.Map;
import org.aristonis.photisma.database.driver.DriverSession;

/**
 * Holds what every connection of one transaction shares: the session, the current depth, the
 * rollback-only flag and the first concurrency failure raised on the session. Confined to one thread
 * at a time; the mutable fields are volatile so a scope handed to another thread (for example to be
 * closed there) sees the latest values.
 */
final class TransactionState {

    static final String ROLLBACK_ONLY_MESSAGE = "The transaction is rollback-only: a concurrency failure or a failed"
            + " rollback to a savepoint means the database may have dropped it; let the failure reach the outermost"
            + " transaction so it rolls back";

    private static final String SAVEPOINT_PREFIX = "photisma_";

    private final DriverSession session;
    private final boolean external;
    private final boolean canReleaseSavepoints;
    private volatile int depth = 1;
    private volatile boolean rollbackOnly;
    private volatile Throwable concurrencyFailure;
    private volatile boolean commitAttempted;
    private final Map<Integer, Integer> savepointsByLevel = new HashMap<>();

    TransactionState(DriverSession session, boolean external, boolean canReleaseSavepoints) {
        this.session = session;
        this.external = external;
        this.canReleaseSavepoints = canReleaseSavepoints;
        if (external) {
            // Level 1 already holds OUTERMOST_SAVEPOINT.
            savepointsByLevel.put(1, 1);
        }
    }

    /** Name of the savepoint that starts level 1 inside an external transaction. */
    static final String OUTERMOST_SAVEPOINT = SAVEPOINT_PREFIX + 1;

    /**
     * Returns a savepoint name unique within the session. A savepoint rolled back to stays alive,
     * so a later savepoint at the same level needs a new name: the first at a level is
     * {@code photisma_<level>}, later ones {@code photisma_<level>_<n>}.
     */
    synchronized String nextSavepointName(int level) {
        int used = savepointsByLevel.getOrDefault(level, 0);
        savepointsByLevel.put(level, used + 1);
        return used == 0 ? SAVEPOINT_PREFIX + level : SAVEPOINT_PREFIX + level + "_" + used;
    }

    DriverSession session() {
        return session;
    }

    boolean isExternal() {
        return external;
    }

    boolean canReleaseSavepoints() {
        return canReleaseSavepoints;
    }

    int depth() {
        return depth;
    }

    void setDepth(int depth) {
        this.depth = depth;
    }

    boolean isRollbackOnly() {
        return rollbackOnly;
    }

    void markRollbackOnly() {
        rollbackOnly = true;
    }

    /**
     * Records that a statement or savepoint command on this transaction's own session failed with a
     * concurrency failure, keeping only the first one, and marks the transaction rollback-only.
     */
    void recordConcurrencyFailure(Throwable failure) {
        if (concurrencyFailure == null) {
            concurrencyFailure = failure;
        }
        rollbackOnly = true;
    }

    /** Returns the first concurrency failure raised on this session, or {@code null} when none was. */
    Throwable concurrencyFailure() {
        return concurrencyFailure;
    }

    /** Tells whether a concurrency failure on this transaction's own session makes a retry worthwhile. */
    boolean hasOwnConcurrencyFailure() {
        return concurrencyFailure != null;
    }

    /** Records that the commit was sent: from here on the outcome may be unknown, so no retry. */
    void markCommitAttempted() {
        commitAttempted = true;
    }

    boolean isCommitAttempted() {
        return commitAttempted;
    }
}
