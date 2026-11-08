package org.aristonis.diwan.database.eloquent.exception;

import java.util.Objects;

/**
 * Signals that a cast could not be parsed, applied or reversed for a model attribute.
 *
 * <p>Thrown for a bad cast spec, stored data the cast cannot read, an input of the wrong type, and
 * any runtime failure inside a user cast. The message names the model class, the attribute key,
 * the cast name and the cause's type. It never carries attribute values or the cause's message,
 * which may contain them (ADR 0005).
 */
public class InvalidCastException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The model class, or null when not yet known. */
    private final transient Class<?> modelClass;

    /** The attribute key. */
    private final String key;

    /** The cast name or spec. */
    private final String castName;

    /**
     * Creates an exception without a cause.
     *
     * @param modelClass the model class, or null when not yet known
     * @param key the attribute key
     * @param castName the cast name or spec
     * @throws NullPointerException if {@code key} or {@code castName} is null
     */
    public InvalidCastException(Class<?> modelClass, String key, String castName) {
        this(modelClass, key, castName, null);
    }

    /**
     * Creates an exception wrapping a cause.
     *
     * @param modelClass the model class, or null when not yet known
     * @param key the attribute key
     * @param castName the cast name or spec
     * @param cause the underlying failure, or null; only its type enters the message
     * @throws NullPointerException if {@code key} or {@code castName} is null
     */
    public InvalidCastException(Class<?> modelClass, String key, String castName, Throwable cause) {
        this(modelClass, key, castName, null, cause);
    }

    /**
     * Creates an exception that states which rule the value broke.
     *
     * @param modelClass the model class, or null when not yet known
     * @param key the attribute key
     * @param castName the cast name or spec
     * @param reason a fixed sentence naming the rule broken, never the value; null for none
     * @param cause the underlying failure, or null; only its type enters the message
     * @throws NullPointerException if {@code key} or {@code castName} is null
     */
    public InvalidCastException(Class<?> modelClass, String key, String castName, String reason,
            Throwable cause) {
        super(message(modelClass, Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(castName, "castName"), reason, cause), cause);
        this.modelClass = modelClass;
        this.key = key;
        this.castName = castName;
    }

    private static String message(Class<?> modelClass, String key, String castName, String reason,
            Throwable cause) {
        String model = modelClass == null ? "unknown model" : modelClass.getName();
        String text = "Invalid cast [" + castName + "] for attribute [" + key + "] on [" + model + "]";
        if (reason != null) {
            return text + ": " + reason;
        }
        return cause == null ? text : text + ": " + cause.getClass().getName();
    }

    /**
     * Returns the model class.
     *
     * @return the model class, or null when not known
     */
    public Class<?> getModelClass() {
        return modelClass;
    }

    /**
     * Returns the attribute key.
     *
     * @return the attribute key
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the cast name.
     *
     * @return the cast name or spec
     */
    public String getCastName() {
        return castName;
    }
}
