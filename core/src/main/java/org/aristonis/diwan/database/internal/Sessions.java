package org.aristonis.diwan.database.internal;

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

    // A session whose connection is gone, or whose rollback failed, is in a state nobody can vouch
    // for, so it is dropped instead of handed back.
    static void endAfterFailure(DriverSession session, Throwable failure, boolean isUnknownState) {
        if (!isUnknownState && !isConnectionLost(failure)) {
            closeAfterFailure(session, failure);
            return;
        }
        try {
            session.discard();
        } catch (Throwable discardFailure) {
            suppress(failure, discardFailure);
        }
    }

    // SQL state class 08 is a connection exception in every database's numbering; 57P01 to 57P03
    // are PostgreSQL shutting down or restarting, which end the connection the same way.
    static boolean isConnectionLost(Throwable failure) {
        return isConnectionLost(failure, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    // One set across the whole walk, so exceptions that suppress each other cannot loop forever.
    private static boolean isConnectionLost(Throwable failure, java.util.Set<Throwable> seen) {
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof org.aristonis.diwan.database.exception.DriverException
                    && ((org.aristonis.diwan.database.exception.DriverException) t).kinds()
                            .contains(org.aristonis.diwan.database.driver.DriverFailureKind.CONNECTION_LOST)) {
                return true;
            }
            if (t instanceof org.aristonis.diwan.database.exception.DriverException) {
                String state = ((org.aristonis.diwan.database.exception.DriverException) t).sqlState().orElse("");
                if (state.startsWith("08") || state.equals("57P01") || state.equals("57P02")
                        || state.equals("57P03")) {
                    return true;
                }
            }
            for (Throwable suppressed : t.getSuppressed()) {
                if (!seen.contains(suppressed) && isConnectionLost(suppressed, seen)) {
                    return true;
                }
            }
        }
        return false;
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
