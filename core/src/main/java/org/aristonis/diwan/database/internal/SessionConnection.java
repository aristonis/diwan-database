package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.event.TransactionPhase;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.PretendCallback;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.TransactionScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.RowCursor;

/**
 * A connection held to one driver session for the life of a {@link Connection#session} callback.
 *
 * <p>The session is owned by the root connection that opened it; this class never closes it.
 */
final class SessionConnection extends AbstractConnection {

    private static final String ENDED_MESSAGE = "This session already ended; the connection passed to a"
            + " session callback may only be used inside that callback";

    private final DriverSession session;
    private final boolean canReleaseSavepoints;
    private final CursorGuard cursorGuard = new CursorGuard();
    private volatile boolean ended;

    SessionConnection(ConnectionSettings settings, DriverSession session, boolean canReleaseSavepoints) {
        super(settings);
        this.session = session;
        this.canReleaseSavepoints = canReleaseSavepoints;
    }

    /** Closes a cursor the callback left open, then refuses any further use. */
    void end() {
        try {
            cursorGuard.closeFrom(0);
        } finally {
            ended = true;
        }
    }

    /** Ends as {@link #end()} does after the callback failed, attaching a close failure to it. */
    void endAfterFailure(Throwable failure) {
        Throwable cursorFailure = cursorGuard.closeQuietlyFrom(0);
        if (cursorFailure != null) {
            Sessions.suppress(failure, cursorFailure);
        }
        ended = true;
    }

    private void ensureOpen() {
        if (ended) {
            throw new IllegalStateException(ENDED_MESSAGE);
        }
    }

    @Override
    <T> T run(PendingStatement<T> statement) {
        ensureOpen();
        cursorGuard.check();
        try {
            return statement.runOn(session);
        } catch (DriverException failure) {
            throw queryFailure(statement.sql(), statement.bindings(), failure);
        }
    }

    @Override
    RowCursor openCursor(String sql, List<Object> bindings) {
        ensureOpen();
        cursorGuard.check();
        RowCursor driverCursor;
        try {
            driverCursor = session.cursor(sql, bindings);
        } catch (DriverException failure) {
            throw queryFailure(sql, bindings, failure);
        }
        TranslatingCursor cursor = new TranslatingCursor(getName(), driverCursor,
                failure -> queryFailure(sql, bindings, failure), failure -> cursorGuard.release());
        cursorGuard.register(cursor, 0);
        return cursor;
    }

    @Override
    public <T> T transaction(TransactionCallback<T> callback) {
        return transaction(callback, 1);
    }

    // Retries reuse the held session: giving it up would lose the settings the session exists for.
    @Override
    public <T> T transaction(TransactionCallback<T> callback, int attempts) {
        Objects.requireNonNull(callback, "callback");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
        ensureOpen();
        // After-rollback work of a failed attempt waits until the whole call is over, as on the root.
        List<Runnable> deferred = new ArrayList<>();
        T result = null;
        Throwable failure = null;
        try {
            result = attempts(callback, attempts, deferred);
        } catch (RuntimeException | Error attemptFailure) {
            failure = attemptFailure;
        }
        Throwable deferredFailure = TransactionState.runAll(deferred);
        if (failure != null) {
            if (deferredFailure != null) {
                Sessions.suppress(failure, deferredFailure);
            }
            Sessions.rethrow(failure);
        } else if (deferredFailure != null) {
            Sessions.rethrow(deferredFailure);
        }
        return result;
    }

    private <T> T attempts(TransactionCallback<T> callback, int attempts, List<Runnable> deferred) {
        for (int attempt = 1; ; attempt++) {
            TransactionState state = begin();
            TransactionalConnection connection = new TransactionalConnection(settings(), state);
            T result;
            try {
                result = runAttempt(connection, callback);
            } catch (RuntimeException | Error failure) {
                // The event keeps its place in the order; the callbacks wait for the end of the call.
                Throwable eventFailure = TransactionState.fireOutermostEnd(connection, false);
                if (eventFailure != null) {
                    Sessions.suppress(failure, eventFailure);
                }
                deferred.add(afterEnd(connection, false, false));
                if (state.isExternal() || state.isCommitAttempted() || !state.hasOwnConcurrencyFailure()
                        || attempt >= attempts) {
                    throw failure;
                }
                continue;
            }
            deferred.add(afterEnd(connection, true, true));
            return result;
        }
    }

    private static Runnable afterEnd(TransactionalConnection connection, boolean isCommitted,
            boolean isFiring) {
        return () -> {
            Throwable failure = TransactionState.finishOutermost(connection, isCommitted, isFiring);
            if (failure != null) {
                Sessions.rethrow(failure);
            }
        };
    }

    private TransactionState begin() {
        cursorGuard.check();
        try {
            boolean isExternal = session.inExternalTransaction();
            if (isExternal) {
                session.savepoint(TransactionState.OUTERMOST_SAVEPOINT);
            } else {
                session.begin();
            }
            TransactionState state = new TransactionState(session, isExternal, canReleaseSavepoints, cursorGuard);
            try {
                TransactionState.fire(settings(), TransactionPhase.BEGINNING, 1, isExternal);
            } catch (RuntimeException | Error listenerFailure) {
                TransactionState.undoBegin(session, isExternal, listenerFailure);
                throw listenerFailure;
            }
            return state;
        } catch (DriverException failure) {
            throw queryFailure("begin", Collections.<Object>emptyList(), failure);
        }
    }

    private static <T> T runAttempt(TransactionalConnection connection, TransactionCallback<T> callback) {
        try {
            T result = callback.run(connection);
            connection.closeOpenChild();
            connection.commitOutermost();
            return result;
        } catch (RuntimeException | Error failure) {
            try {
                connection.rollbackOutermost();
            } catch (Throwable rollbackFailure) {
                Sessions.suppress(failure, rollbackFailure);
            }
            throw failure;
        } finally {
            connection.end();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always: a scope may outlive the session callback; use
     *     {@link #transaction(TransactionCallback)} inside a session
     */
    @Override
    public TransactionScope beginTransaction() {
        throw new UnsupportedOperationException("beginTransaction is not available inside a session;"
                + " use transaction(callback)");
    }

    @Override
    public List<LoggedQuery> pretend(PretendCallback callback) {
        ensureOpen();
        return PretendConnection.record(settings(), callback);
    }

    @Override
    public int transactionLevel() {
        return 0;
    }
}
