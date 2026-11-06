package org.aristonis.diwan.database.exception;

/**
 * The parent of every failure Diwan reports from the database or from a run, such as a failed
 * statement, a lost connection or a failed migration.
 *
 * <p>Catch it to handle any such failure in one place. Mistakes in the calling code are reported
 * with the JDK's own {@link IllegalArgumentException} and {@link IllegalStateException} instead,
 * so they do not extend this class.
 */
public class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public DatabaseException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and the failure that caused it.
     *
     * @param message the detail message
     * @param cause the underlying failure, or {@code null}
     */
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
