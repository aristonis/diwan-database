package org.aristonis.diwan.database;

/**
 * Implements {@link TransactionScope}: level 1 owns the session of the transaction, deeper levels
 * own a savepoint inside it. Confined to one thread at a time; the ended flag is volatile so a scope
 * closed from another thread is seen as ended everywhere.
 */
final class TransactionScopeConnection extends TransactionalConnection implements TransactionScope {

    private static final String ENDED_MESSAGE = "This transaction scope already ended";

    private final int level;
    // Name of the savepoint this level created; null for an outermost scope that owns a real transaction.
    private final String savepointName;
    private final TransactionalConnection parent;
    private final Runnable onOutermostEnd;
    private volatile boolean ended;

    // The savepoint or transaction of the level is already open. parent is null for the outermost
    // scope, which alone has onOutermostEnd, run once when it ends.
    TransactionScopeConnection(ConnectionSettings settings, TransactionState state, int level,
            String savepointName, TransactionalConnection parent, Runnable onOutermostEnd) {
        super(settings, state);
        this.level = level;
        this.savepointName = savepointName;
        this.parent = parent;
        this.onOutermostEnd = onOutermostEnd;
    }

    int level() {
        return level;
    }

    @Override
    void ensureOpen() {
        if (ended) {
            throw new IllegalStateException(ENDED_MESSAGE);
        }
    }

    @Override
    int cursorLevel() {
        return level;
    }

    private boolean isOutermost() {
        return parent == null;
    }

    // A failed commit leaves the scope open, so close() still rolls it back.
    @Override
    public void commit() {
        ensureOpen();
        closeOpenChild();
        if (isOutermost()) {
            commitOutermost();
            try {
                markEnded();
            } finally {
                Sessions.closeAfterSuccess(state().session(), settings().cleanupFailureHandler());
            }
            // The transaction is over and the thread has left it, so callbacks may use the root.
            Throwable callbackFailure = TransactionState.finishOutermost(this, true);
            if (callbackFailure != null) {
                Sessions.rethrow(callbackFailure);
            }
            return;
        }
        closeCursorsFrom(level);
        if (state().isRollbackOnly()) {
            throw rollbackOnlyFailure();
        }
        commitLevel(savepointName, level);
        markEnded();
        fire(TransactionPhase.COMMITTED, level);
    }

    @Override
    public void close() {
        if (ended) {
            return;
        }
        Throwable failure = null;
        try {
            closeOpenChild();
        } catch (Throwable childFailure) {
            failure = childFailure;
        }
        Throwable cursorFailure = closeCursorsQuietlyFrom(level);
        if (cursorFailure != null) {
            failure = TransactionState.combine(failure, cursorFailure);
        }
        try {
            failure = rollBack(failure);
        } finally {
            failure = finish(failure);
        }
        if (isOutermost()) {
            failure = TransactionState.combine(failure, TransactionState.finishOutermost(this, false));
        }
        if (failure != null) {
            Sessions.rethrow(failure);
        }
    }

    // Marks the scope ended and gives the session back even when something above threw an Error.
    private Throwable finish(Throwable failure) {
        Throwable result = failure;
        try {
            markEnded();
        } catch (Throwable endFailure) {
            result = TransactionState.combine(result, endFailure);
        } finally {
            if (isOutermost()) {
                closeSession(result);
            }
        }
        return result;
    }

    private Throwable rollBack(Throwable earlier) {
        if (!isOutermost()) {
            return TransactionState.combine(earlier, rollbackLevelTo(savepointName, level));
        }
        try {
            rollbackOutermost();
            return earlier;
        } catch (Throwable rollbackFailure) {
            return TransactionState.combine(earlier, rollbackFailure);
        }
    }

    private void closeSession(Throwable failure) {
        if (failure == null) {
            Sessions.closeAfterSuccess(state().session(), settings().cleanupFailureHandler());
        } else {
            Sessions.closeAfterFailure(state().session(), failure);
        }
    }

    // A child must never raise the depth above its true value: it only lowers the depth when the
    // depth still points at its own level, which an enclosing nested transaction may already have
    // lowered.
    private void markEnded() {
        ended = true;
        if (state().depth() == level) {
            state().setDepth(level - 1);
        }
        if (isOutermost()) {
            onOutermostEnd.run();
        } else {
            parent.childEnded(this);
        }
    }
}
