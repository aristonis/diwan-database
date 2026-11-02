package org.aristonis.diwan.database.eloquent.json;

/**
 * Signals that text is not valid JSON or that a value cannot be written as JSON.
 *
 * <p>The message carries a position and a reason, or the class name of an unsupported value. It
 * never carries the input text or the value itself, so it is safe to log.
 */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the position and reason, never the input
     */
    public JsonException(String message) {
        super(message);
    }
}
