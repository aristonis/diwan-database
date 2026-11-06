package org.aristonis.diwan.database.eloquent.exception;

import org.aristonis.diwan.database.exception.DatabaseException;

/**
 * The parent of every failure the model layer reports, such as a model that was not found or a
 * relation that is not defined.
 *
 * <p>It extends {@link DatabaseException}, so one {@code catch} of that class covers the models
 * too. Mistakes in the calling code are reported with {@link IllegalArgumentException} and
 * {@link IllegalStateException} instead.
 */
public class EloquentException extends DatabaseException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public EloquentException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and the failure that caused it.
     *
     * @param message the detail message
     * @param cause the underlying failure, or {@code null}
     */
    public EloquentException(String message, Throwable cause) {
        super(message, cause);
    }
}
