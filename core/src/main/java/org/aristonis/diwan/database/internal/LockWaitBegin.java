package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.TransactionOptions;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.LockWaitGuard;

/**
 * Starts the outermost transaction of a session, applying the lock wait of the options around the
 * begin, and hands back the handle that takes the wait back once the transaction ended.
 */
final class LockWaitBegin {

    static final String NESTED_MESSAGE = "A lock wait applies only to the outermost transaction";

    private LockWaitBegin() {
    }

    /**
     * Runs {@code begin} with the lock wait of {@code options} around it.
     *
     * <p>With a wait: {@code beforeBegin}, the begin, then {@code afterBegin}. When any step fails the
     * wait is restored at once, a restore failure is added to the failure as suppressed, and the
     * failure is rethrown, after a rollback when the transaction had begun; no handle is returned then.
     *
     * @param session the session that begins, never {@code null}
     * @param options the options of the transaction, never {@code null}
     * @param begin the command that starts the transaction, never {@code null}
     * @return the handle to {@link Handle#restore()} after the transaction ended, or {@code null}
     *     when the options carry no lock wait
     */
    static Handle begin(DriverSession session, TransactionOptions options, Runnable begin) {
        if (!options.lockWaitMillis().isPresent()) {
            begin.run();
            return null;
        }
        Handle handle = new Handle(session.lockWait(options.lockWaitMillis().getAsLong()));
        boolean isBegun = false;
        try {
            handle.guard.beforeBegin();
            begin.run();
            isBegun = true;
            handle.guard.afterBegin();
        } catch (RuntimeException | Error failure) {
            if (isBegun) {
                rollBackQuietly(session, failure);
            }
            Throwable restoreFailure = handle.restore();
            if (restoreFailure != null) {
                Sessions.suppress(failure, restoreFailure);
            }
            throw failure;
        }
        return handle;
    }

    // The transaction is open but its wait could not be applied, so it is undone before the restore.
    private static void rollBackQuietly(DriverSession session, Throwable failure) {
        try {
            session.rollback();
        } catch (RuntimeException | Error rollbackFailure) {
            Sessions.suppress(failure, rollbackFailure);
        }
    }

    /**
     * Restores the wait of a handle that may be {@code null}, attaching a failure to {@code failure}.
     *
     * @param handle the handle from {@link #begin}, may be {@code null}
     * @param failure the failure of the work that gets the restore failure as suppressed
     * @return {@code true} if the restore failed, which leaves the session in an unknown state
     */
    static boolean restoreAfterFailure(Handle handle, Throwable failure) {
        Throwable restoreFailure = handle == null ? null : handle.restore();
        if (restoreFailure == null) {
            return false;
        }
        Sessions.suppress(failure, restoreFailure);
        return true;
    }

    /** Takes back the lock wait of one transaction, at most once. */
    static final class Handle {

        private final LockWaitGuard guard;
        private boolean isRestored;

        private Handle(LockWaitGuard guard) {
            this.guard = guard;
        }

        /** Restores the wait the first time it is called; returns the failure instead of throwing it. */
        Throwable restore() {
            if (isRestored) {
                return null;
            }
            isRestored = true;
            try {
                guard.restore();
                return null;
            } catch (RuntimeException | Error failure) {
                return failure;
            }
        }
    }
}
