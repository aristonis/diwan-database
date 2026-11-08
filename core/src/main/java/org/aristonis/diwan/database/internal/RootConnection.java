package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.event.TransactionPhase;
import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.PretendCallback;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.TransactionOptions;
import org.aristonis.diwan.database.TransactionScope;
import org.aristonis.diwan.database.exception.QueryException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.WeakHashMap;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.RowCursor;

/**
 * Runs each statement on a session borrowed from the driver for that one call, and opens
 * transactions on a session of their own. Its only mutable state is the strict-mode map of threads
 * inside a transaction it opened, with a nesting count per thread; the map is synchronized and holds
 * its threads weakly, so the connection is safe to share and dead threads never accumulate.
 */
@Internal
public final class RootConnection extends AbstractConnection {

    private static final String BEGIN = "begin";

    private final Driver driver;
    // Guarded by its own lock; only tiny map operations run under it, never a driver call.
    private final Map<Thread, Integer> threadsInTransaction =
            Collections.synchronizedMap(new WeakHashMap<Thread, Integer>());

    @Internal
    public RootConnection(Driver driver, ConnectionSettings settings) {
        super(settings);
        this.driver = driver;
    }

    @Override
    <T> T run(PendingStatement<T> statement) {
        refuseInsideOwnTransaction();
        DriverSession session = open(statement.sql(), statement.bindings());
        T result;
        try {
            result = statement.runOn(session);
        } catch (DriverException failure) {
            QueryException error = queryFailure(statement.sql(), statement.bindings(), failure);
            Sessions.closeAfterFailure(session, error);
            throw error;
        } catch (RuntimeException | Error failure) {
            Sessions.closeAfterFailure(session, failure);
            throw failure;
        }
        Sessions.closeAfterSuccess(session, settings().cleanupFailureHandler());
        return result;
    }

    // The cursor owns a session of its own, given back when the cursor closes, by the caller or by
    // reaching the end.
    @Override
    RowCursor openCursor(String sql, List<Object> bindings) {
        refuseInsideOwnTransaction();
        DriverSession session = open(sql, bindings);
        RowCursor driverCursor;
        try {
            driverCursor = session.cursor(sql, bindings);
        } catch (DriverException failure) {
            QueryException error = queryFailure(sql, bindings, failure);
            Sessions.closeAfterFailure(session, error);
            throw error;
        } catch (RuntimeException | Error failure) {
            Sessions.closeAfterFailure(session, failure);
            throw failure;
        }
        return new TranslatingCursor(getName(), driverCursor, failure -> queryFailure(sql, bindings, failure),
                failure -> {
                    if (failure == null) {
                        Sessions.closeAfterSuccess(session, settings().cleanupFailureHandler());
                    } else {
                        Sessions.endAfterFailure(session, failure, false);
                    }
                });
    }

    @Override
    public OptionalInt exactDecimalDigits() {
        return driver.exactDecimalDigits();
    }

    private DriverSession open(String sql, List<Object> bindings) {
        try {
            return driver.open();
        } catch (DriverException failure) {
            throw queryFailure(sql, bindings, failure);
        }
    }

    // A root call from inside the transaction would borrow a second connection, so its
    // writes escape the rollback and a small pool can deadlock.
    private void refuseInsideOwnTransaction() {
        if (settings().strictTransactions() && threadsInTransaction.containsKey(Thread.currentThread())) {
            throw new IllegalStateException("This thread is inside a transaction on connection '" + getName()
                    + "'; use the connection passed to the transaction callback, or the transaction scope, instead"
                    + " of the root connection, or build the connection with strictTransactions(false)");
        }
    }

    @Override
    public <T> T transaction(TransactionCallback<T> callback) {
        return transaction(callback, 1);
    }

    @Override
    public <T> T transaction(TransactionCallback<T> callback, TransactionOptions options) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(options, "options");
        refuseInsideOwnTransaction();
        boolean isTracked = settings().strictTransactions();
        Thread thread = Thread.currentThread();
        if (isTracked) {
            enter(thread);
        }
        // After-commit and after-rollback work waits until every attempt is over and the thread has
        // left the transaction, so it may use this connection even in strict mode.
        List<Runnable> deferred = new ArrayList<>();
        T result = null;
        Throwable failure = null;
        try {
            result = withRetries(callback, options, deferred);
        } catch (RuntimeException | Error attemptFailure) {
            failure = attemptFailure;
        } finally {
            if (isTracked) {
                leave(thread);
            }
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

    private void enter(Thread thread) {
        synchronized (threadsInTransaction) {
            Integer count = threadsInTransaction.get(thread);
            threadsInTransaction.put(thread, count == null ? 1 : count + 1);
        }
    }

    private void leave(Thread thread) {
        synchronized (threadsInTransaction) {
            Integer count = threadsInTransaction.get(thread);
            if (count == null || count <= 1) {
                threadsInTransaction.remove(thread);
            } else {
                threadsInTransaction.put(thread, count - 1);
            }
        }
    }

    // A retry runs the whole callback again, on a fresh session. Only a concurrency failure
    // raised on the attempt's own session before the commit was sent is retried: a failed commit
    // may have landed, and a concurrency failure from elsewhere says nothing about this transaction.
    private <T> T withRetries(TransactionCallback<T> callback, TransactionOptions options,
            List<Runnable> deferred) {
        int attempts = options.attempts();
        for (int attempt = 1; ; attempt++) {
            TransactionState state = openTransaction(options);
            try {
                return runAttempt(new TransactionalConnection(settings(), state), callback, deferred);
            } catch (RuntimeException | Error failure) {
                if (!isRetryable(state) || attempt >= attempts) {
                    throw failure;
                }
            }
        }
    }

    private static boolean isRetryable(TransactionState state) {
        return !state.isExternal() && !state.isCommitAttempted() && state.hasOwnConcurrencyFailure();
    }

    private <T> T runAttempt(TransactionalConnection connection, TransactionCallback<T> callback,
            List<Runnable> deferred) {
        DriverSession session = connection.state().session();
        T result;
        try {
            result = callback.run(connection);
            connection.closeOpenChild();
            connection.commitOutermost();
        } catch (RuntimeException | Error failure) {
            rollBackAfterFailure(connection, session, failure);
            Throwable eventFailure = TransactionState.fireOutermostEnd(connection, false);
            if (eventFailure != null) {
                Sessions.suppress(failure, eventFailure);
            }
            deferred.add(afterEnd(connection, false, false));
            throw failure;
        } finally {
            connection.end();
        }
        deferred.add(afterEnd(connection, true, true));
        // The work is committed, so a wait that cannot be taken back is reported, and the session that
        // keeps it is dropped instead of handed back.
        Throwable restoreFailure = connection.state().restoreLockWait();
        if (restoreFailure != null) {
            Sessions.endAfterFailure(session, restoreFailure, true);
            Sessions.rethrow(restoreFailure);
        }
        Sessions.closeAfterSuccess(session, settings().cleanupFailureHandler());
        return result;
    }

    // The work that runs once the call is over: the attempt's callbacks, and its end event unless
    // it already fired; the first failure is rethrown.
    private static Runnable afterEnd(TransactionalConnection connection, boolean isCommitted,
            boolean isFiring) {
        return () -> {
            Throwable failure = TransactionState.finishOutermost(connection, isCommitted, isFiring);
            if (failure != null) {
                Sessions.rethrow(failure);
            }
        };
    }

    // An Error from the rollback is kept as context, and the session goes back to the driver whatever
    // the rollback threw.
    private static void rollBackAfterFailure(TransactionalConnection connection, DriverSession session,
            Throwable failure) {
        boolean hasRolledBack = false;
        try {
            connection.rollbackOutermost();
            hasRolledBack = true;
        } catch (Throwable rollbackFailure) {
            Sessions.suppress(failure, rollbackFailure);
        } finally {
            Throwable restoreFailure = connection.state().restoreLockWait();
            if (restoreFailure != null) {
                Sessions.suppress(failure, restoreFailure);
            }
            Sessions.endAfterFailure(session, failure, !hasRolledBack || restoreFailure != null);
        }
    }

    /** Opens a session and starts level 1: a real transaction, or a savepoint inside an external one. */
    private TransactionState openTransaction(TransactionOptions options) {
        DriverSession session = open(BEGIN, Collections.<Object>emptyList());
        LockWaitBegin.Handle lockWait = null;
        boolean isSessionEnded = false;
        try {
            boolean isExternal = session.inExternalTransaction();
            if (isExternal) {
                session.savepoint(TransactionState.OUTERMOST_SAVEPOINT);
            } else {
                // Inside an external transaction no begin runs, so there is nothing to apply a wait to.
                lockWait = LockWaitBegin.begin(session, options, session::begin);
            }
            boolean canRelease = driver.info().supports(DriverCapability.SAVEPOINT_RELEASE);
            TransactionState state = new TransactionState(session, isExternal, canRelease);
            state.setLockWait(lockWait);
            try {
                TransactionState.fire(settings(), TransactionPhase.BEGINNING, 1, isExternal);
            } catch (RuntimeException | Error listenerFailure) {
                boolean isUndoFailed = TransactionState.undoBegin(session, isExternal, listenerFailure);
                boolean isRestoreFailed = LockWaitBegin.restoreAfterFailure(lockWait, listenerFailure);
                // A session that could not be undone or restored is dropped, not handed back as clean.
                isSessionEnded = true;
                Sessions.endAfterFailure(session, listenerFailure, isUndoFailed || isRestoreFailed);
                throw listenerFailure;
            }
            return state;
        } catch (DriverException failure) {
            QueryException error = queryFailure(BEGIN, Collections.<Object>emptyList(), failure);
            if (!isSessionEnded) {
                Sessions.endAfterFailure(session, error, failure.getSuppressed().length > 0);
            }
            throw error;
        } catch (RuntimeException | Error failure) {
            if (!isSessionEnded) {
                Sessions.closeAfterFailure(session, failure);
            }
            throw failure;
        }
    }

    @Override
    public TransactionScope beginTransaction() {
        refuseInsideOwnTransaction();
        TransactionState state = openTransaction(TransactionOptions.none());
        Thread thread = Thread.currentThread();
        boolean isTracked = settings().strictTransactions();
        if (isTracked) {
            enter(thread);
        }
        return new TransactionScopeConnection(settings(), state, 1,
                state.isExternal() ? TransactionState.OUTERMOST_SAVEPOINT : null, null, () -> {
            if (isTracked) {
                leave(thread);
            }
        });
    }

    // A failed callback may have left a session setting it could not restore, so the session is
    // discarded rather than closed and handed back as clean.
    @Override
    public <T> T session(TransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "callback");
        refuseInsideOwnTransaction();
        boolean canRelease = getDriverInfo().supports(DriverCapability.SAVEPOINT_RELEASE);
        DriverSession session = open("session", Collections.<Object>emptyList());
        SessionConnection held = new SessionConnection(settings(), session, canRelease);
        T result;
        try {
            result = callback.run(held);
            held.end();
        } catch (RuntimeException | Error failure) {
            held.endAfterFailure(failure);
            try {
                session.discard();
            } catch (Throwable discardFailure) {
                Sessions.suppress(failure, discardFailure);
            }
            throw failure;
        }
        Sessions.closeAfterSuccess(session, settings().cleanupFailureHandler());
        return result;
    }

    @Override
    public List<LoggedQuery> pretend(PretendCallback callback) {
        return PretendConnection.record(settings(), callback);
    }

    @Override
    public int transactionLevel() {
        return 0;
    }
}
