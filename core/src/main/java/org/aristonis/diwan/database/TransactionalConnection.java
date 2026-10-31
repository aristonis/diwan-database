package org.aristonis.diwan.database;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.driver.DriverException;
import org.aristonis.diwan.database.driver.DriverSession;

/**
 * Runs every statement on the session of one transaction and nests further transactions through
 * savepoints. Confined to the thread that opened the transaction; only the ended flag is volatile,
 * so a leaked connection used elsewhere fails instead of reaching the session.
 */
class TransactionalConnection extends AbstractConnection {

    private static final String ENDED_MESSAGE = "This transaction already ended; the connection passed to a"
            + " transaction callback may only be used inside that callback";

    private final TransactionState state;
    private TransactionScopeConnection openChild;
    private volatile boolean ended;

    TransactionalConnection(ConnectionSettings settings, TransactionState state) {
        super(settings);
        this.state = state;
    }

    final TransactionState state() {
        return state;
    }

    /** Refuses any further use of this callback connection; run when its attempt ends. */
    final void end() {
        ended = true;
    }

    /** Fails when this connection may no longer be used at all. */
    void ensureOpen() {
        if (ended) {
            throw new IllegalStateException(ENDED_MESSAGE);
        }
    }

    /** Fails when this connection may not send anything more to the database. */
    final void ensureUsable() {
        ensureOpen();
        if (state.isRollbackOnly()) {
            throw rollbackOnlyFailure();
        }
    }

    /** Builds the rollback-only refusal, caused by the recorded concurrency failure when there is one. */
    final IllegalStateException rollbackOnlyFailure() {
        return new IllegalStateException(TransactionState.ROLLBACK_ONLY_MESSAGE, state.concurrencyFailure());
    }

    @Override
    final <T> T run(PendingStatement<T> statement) {
        ensureUsable();
        try {
            return statement.runOn(state.session());
        } catch (DriverException failure) {
            throw failed(statement.sql(), failure);
        }
    }

    /** Runs a transaction command, translating its failure like a statement failure. */
    final void control(String description, Consumer<DriverSession> command) {
        try {
            command.accept(state.session());
        } catch (DriverException failure) {
            throw failed(description, failure);
        }
    }

    private QueryException failed(String sql, DriverException failure) {
        QueryException error = queryFailure(sql, Collections.<Object>emptyList(), failure);
        // The database may already have dropped the whole transaction.
        if (failure.isConcurrencyFailure()) {
            state.recordConcurrencyFailure(error);
        }
        return error;
    }

    /**
     * Ends the outermost level successfully: commits, or keeps the savepoint of an external
     * transaction. A commit failure is never recorded as a retryable concurrency failure, because the
     * commit may have landed.
     */
    final void commitOutermost() {
        if (state.isRollbackOnly()) {
            throw rollbackOnlyFailure();
        }
        state.markCommitAttempted();
        if (state.isExternal()) {
            releaseSavepoint(TransactionState.OUTERMOST_SAVEPOINT);
            return;
        }
        try {
            state.session().commit();
        } catch (DriverException failure) {
            throw queryFailure("commit", Collections.<Object>emptyList(), failure);
        }
    }

    /** Undoes the outermost level; inside an external transaction only its own savepoint. */
    final void rollbackOutermost() {
        if (state.isExternal()) {
            String name = TransactionState.OUTERMOST_SAVEPOINT;
            control("rollback to savepoint " + name, session -> session.rollbackToSavepoint(name));
        } else {
            control("rollback", DriverSession::rollback);
        }
    }

    final void releaseSavepoint(String name) {
        if (state.canReleaseSavepoints()) {
            control("release savepoint " + name, session -> session.releaseSavepoint(name));
        }
    }

    /** Rolls back to a savepoint, attaching a failure to {@code original} instead of throwing it. */
    final void rollbackToSavepoint(String name, Throwable original) {
        try {
            control("rollback to savepoint " + name, session -> session.rollbackToSavepoint(name));
        } catch (Throwable rollbackFailure) {
            state.markRollbackOnly();
            Sessions.suppress(original, rollbackFailure);
        }
    }

    /** Opens a savepoint one level deeper, which becomes the depth, and returns its name. */
    final String enterSavepoint() {
        ensureUsable();
        int level = state.depth() + 1;
        String name = state.nextSavepointName(level);
        control("savepoint " + name, session -> session.savepoint(name));
        state.setDepth(level);
        return name;
    }

    /** Rolls back a scope this connection opened and its callback or owner left open. */
    final void closeOpenChild() {
        TransactionScopeConnection child = openChild;
        if (child != null) {
            child.close();
        }
    }

    // Ends only a scope opened deeper than level, so a nested transaction never closes a scope its
    // caller opened before entering it.
    private void closeOpenChildAbove(int level) {
        TransactionScopeConnection child = openChild;
        if (child != null && child.level() > level) {
            child.close();
        }
    }

    final void childEnded(TransactionScopeConnection child) {
        if (openChild == child) {
            openChild = null;
        }
    }

    @Override
    public final <T> T transaction(TransactionCallback<T> callback) {
        return transaction(callback, 1);
    }

    // Attempts are ignored below the outermost level: a concurrency failure is rethrown at once so
    // the outermost transaction decides whether to retry.
    @Override
    public final <T> T transaction(TransactionCallback<T> callback, int attempts) {
        Objects.requireNonNull(callback, "callback");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
        String savepoint = enterSavepoint();
        int level = state.depth();
        try {
            T result = callback.run(this);
            closeOpenChildAbove(level);
            if (!state.isRollbackOnly()) {
                releaseSavepoint(savepoint);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            closeChildAfterFailure(level, failure);
            rollbackToSavepoint(savepoint, failure);
            throw failure;
        } finally {
            state.setDepth(level - 1);
        }
    }

    private void closeChildAfterFailure(int level, Throwable failure) {
        try {
            closeOpenChildAbove(level);
        } catch (Throwable childFailure) {
            Sessions.suppress(failure, childFailure);
        }
    }

    @Override
    public final TransactionScope beginTransaction() {
        ensureOpen();
        if (openChild != null) {
            throw new IllegalStateException("An inner transaction scope is still open; begin the next one on it,"
                    + " or end it first");
        }
        String savepoint = enterSavepoint();
        TransactionScopeConnection child = new TransactionScopeConnection(settings(), state, state.depth(),
                savepoint, this, null);
        openChild = child;
        return child;
    }

    @Override
    public final List<LoggedQuery> pretend(PretendCallback callback) {
        ensureOpen();
        return PretendConnection.record(settings(), callback);
    }

    @Override
    public final int transactionLevel() {
        ensureOpen();
        return state.depth();
    }
}
