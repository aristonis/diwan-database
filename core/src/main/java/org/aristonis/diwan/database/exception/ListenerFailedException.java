package org.aristonis.diwan.database.exception;

import org.aristonis.diwan.database.Internal;

/**
 * Reports that a listener failed after its statement had already succeeded.
 *
 * <p>The statement's effects are not undone; inside a transaction the caller decides whether to
 * roll back. The {@linkplain #getCause() cause} is the first exception a listener threw, and any
 * later listener failures are suppressed onto it.
 *
 * <p>Instances are immutable after construction and safe to share between threads.
 */
public final class ListenerFailedException extends DatabaseException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message, never {@code null}
     * @param cause the listener's exception, never {@code null}
     */
    @Internal
    public ListenerFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
