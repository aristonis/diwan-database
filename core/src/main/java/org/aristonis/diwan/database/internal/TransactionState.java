package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.event.TransactionPhase;
import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.event.ConnectionEvents;
import org.aristonis.diwan.database.event.TransactionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.driver.DriverSession;

/**
 * Holds what every connection of one transaction shares: the session, the current depth, the
 * rollback-only flag, the first concurrency failure raised on the session, the guard of its open
 * cursor and the after-commit and after-rollback callbacks registered on it. The callbacks belong to
 * one attempt: a retry opens a new state with new callbacks. Confined to one thread at a time; the
 * mutable fields are volatile so a scope handed to another thread (for example to be closed there)
 * sees the latest values.
 */
@Internal
public final class TransactionState {

    static final String ROLLBACK_ONLY_MESSAGE = "The transaction is rollback-only: a concurrency failure or a failed"
            + " rollback to a savepoint means the database may have dropped it; let the failure reach the outermost"
            + " transaction so it rolls back";

    private static final String SAVEPOINT_PREFIX = "diwan_";

    private final DriverSession session;
    private final boolean external;
    private final boolean canReleaseSavepoints;
    private volatile int depth = 1;
    private volatile boolean rollbackOnly;
    private volatile Throwable concurrencyFailure;
    private volatile boolean commitAttempted;
    private volatile boolean rolledBack;
    private final TransactionCallbacks callbacks = new TransactionCallbacks();
    private final Map<Integer, Integer> savepointsByLevel = new HashMap<>();
    private final CursorGuard cursorGuard;

    TransactionState(DriverSession session, boolean external, boolean canReleaseSavepoints) {
        this(session, external, canReleaseSavepoints, new CursorGuard());
    }

    /** Creates the state of a transaction on a session whose cursor guard already exists. */
    TransactionState(DriverSession session, boolean external, boolean canReleaseSavepoints,
            CursorGuard cursorGuard) {
        this.cursorGuard = cursorGuard;
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
     * {@code diwan_<level>}, later ones {@code diwan_<level>_<n>}.
     */
    synchronized String nextSavepointName(int level) {
        int used = savepointsByLevel.getOrDefault(level, 0);
        savepointsByLevel.put(level, used + 1);
        return used == 0 ? SAVEPOINT_PREFIX + level : SAVEPOINT_PREFIX + level + "_" + used;
    }

    /** Returns the guard of the session's one open cursor, shared with a session callback. */
    CursorGuard cursorGuard() {
        return cursorGuard;
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

    /** Returns the callbacks registered for after the commit or the rollback of this transaction. */
    TransactionCallbacks callbacks() {
        return callbacks;
    }

    /** Records that the rollback command of the outermost level succeeded. */
    void markRolledBack() {
        rolledBack = true;
    }

    boolean isRolledBack() {
        return rolledBack;
    }

    /** Tells the listeners of {@code settings} that a transaction phase happened at {@code level}. */
    static void fire(ConnectionSettings settings, TransactionPhase phase, int level, boolean isSavepoint) {
        ConnectionEvents events = settings.events();
        if (events.isActive()) {
            events.transaction(phase, new TransactionEvent(settings.name(), level, isSavepoint));
        }
    }

    /** Keeps {@code earlier} as the failure and attaches {@code later} to it; either may be {@code null}. */
    @Internal
    public static Throwable combine(Throwable earlier, Throwable later) {
        if (earlier == null) {
            return later;
        }
        if (later != null) {
            Sessions.suppress(earlier, later);
        }
        return earlier;
    }

    /** Runs every runner in order and returns the first failure with the later ones attached, or {@code null}. */
    static Throwable runAll(List<Runnable> runners) {
        Throwable failure = null;
        for (Runnable runner : runners) {
            try {
                runner.run();
            } catch (Throwable runnerFailure) {
                failure = combine(failure, runnerFailure);
            }
        }
        return failure;
    }

    /**
     * Runs once the outermost level of {@code connection} ended and its session was given back: tells
     * the listeners (a rollback only when its command worked) and runs the after-commit or
     * after-rollback callbacks. Returns the first failure, or {@code null}.
     */
    static Throwable finishOutermost(TransactionalConnection connection, boolean isCommitted) {
        return finishOutermost(connection, isCommitted, true);
    }

    // A retried attempt reports its rollback at once, so events keep the order the database saw;
    // its callbacks still wait until the whole transaction call is over.
    static Throwable finishOutermost(TransactionalConnection connection, boolean isCommitted,
            boolean isFiring) {
        Throwable failure = isFiring ? fireOutermostEnd(connection, isCommitted) : null;
        TransactionCallbacks callbacks = connection.state().callbacks();
        if (!isCommitted) {
            callbacks.rollbackLevel(1);
        }
        List<Runnable> runners = new ArrayList<>();
        if (isCommitted) {
            runners.add(callbacks.drainCommitted());
        }
        runners.add(callbacks.drainRolledBack());
        return combine(failure, runAll(runners));
    }

    /**
     * Tells the listeners how the outermost level of {@code connection} ended. A listener of the
     * ROLLED_BACK phase on the root path runs before the thread leaves the transaction, so SQL it
     * sends on the root connection is refused in strict mode.
     *
     * @return the failure a listener threw, or {@code null}
     */
    static Throwable fireOutermostEnd(TransactionalConnection connection, boolean isCommitted) {
        try {
            if (isCommitted) {
                connection.fire(TransactionPhase.COMMITTED, 1);
            } else if (connection.state().isRolledBack()) {
                connection.fire(TransactionPhase.ROLLED_BACK, 1);
            }
            return null;
        } catch (Throwable listenerFailure) {
            return listenerFailure;
        }
    }

    /**
     * Undoes a transaction or savepoint that was just opened when a listener refused it, attaching a
     * failure of the undo to {@code failure}.
     */
    static void undoBegin(DriverSession session, boolean isExternal, Throwable failure) {
        try {
            if (isExternal) {
                session.rollbackToSavepoint(OUTERMOST_SAVEPOINT);
            } else {
                session.rollback();
            }
        } catch (Throwable undoFailure) {
            Sessions.suppress(failure, undoFailure);
        }
    }
}
