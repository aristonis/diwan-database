package org.aristonis.diwan.database;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.aristonis.diwan.database.driver.DriverSession;

/** Gives sessions back to the driver without letting a close failure hide the outcome of the work. */
final class Sessions {

    private static final Logger LOGGER = Logger.getLogger(Sessions.class.getName());

    private Sessions() {
    }

    // The work's own failure is what the caller needs; losing the session on top of it is kept as
    // context rather than replacing it.
    static void closeAfterFailure(DriverSession session, Throwable failure) {
        try {
            session.close();
        } catch (Throwable closeFailure) {
            suppress(failure, closeFailure);
        }
    }

    // The work already took effect, so a failure to give the session back must not turn a completed
    // write into an error the caller would retry.
    static void closeAfterSuccess(DriverSession session, Consumer<Throwable> cleanupFailureHandler) {
        try {
            session.close();
        } catch (Throwable closeFailure) {
            reportCleanupFailure(closeFailure, cleanupFailureHandler);
        }
    }

    // A broken handler must not fail a call whose work succeeded, so its own failure is only logged.
    private static void reportCleanupFailure(Throwable closeFailure, Consumer<Throwable> handler) {
        try {
            handler.accept(closeFailure);
        } catch (Throwable handlerFailure) {
            suppress(handlerFailure, closeFailure);
            LOGGER.log(Level.WARNING, "The cleanup failure handler failed", handlerFailure);
        }
    }

    /** Attaches a secondary failure to the primary one, skipping self-suppression. */
    static void suppress(Throwable failure, Throwable secondary) {
        if (secondary != failure) {
            failure.addSuppressed(secondary);
        }
    }

    /** Rethrows an unchecked failure as is; anything else is wrapped so it is never lost. */
    static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Ending the transaction failed", failure);
    }
}
