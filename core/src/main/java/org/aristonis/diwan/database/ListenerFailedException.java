package org.aristonis.diwan.database;

/**
 * Reports that a listener failed after its statement had already succeeded.
 *
 * <p>The statement's effects are not undone; inside a transaction the caller decides whether to
 * roll back. The {@linkplain #getCause() cause} is the first exception a listener threw, and any
 * later listener failures are suppressed onto it.
 *
 * <p>Instances are immutable after construction and safe to share between threads.
 */
public final class ListenerFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail message, never {@code null}
     * @param cause the listener's exception, never {@code null}
     */
    ListenerFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
