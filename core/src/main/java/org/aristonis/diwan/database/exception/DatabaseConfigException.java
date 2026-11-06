package org.aristonis.diwan.database.exception;

import java.util.Optional;

/**
 * Reports a configuration value that is missing or invalid.
 *
 * <p>The message and the two accessors name the connection and the key, never the value, so a
 * password that was typed wrongly cannot leak through the error.
 */
public final class DatabaseConfigException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The connection the key belongs to, or {@code null}. */
    private final String connection;

    /** The config key, or {@code null}. */
    private final String key;

    /**
     * Creates the exception.
     *
     * @param message what is wrong, never containing a config value
     * @param connection the connection name, or {@code null} when the problem is not tied to one
     * @param key the config key, or {@code null} when the problem is not tied to one
     */
    public DatabaseConfigException(String message, String connection, String key) {
        super(message);
        this.connection = connection;
        this.key = key;
    }

    /**
     * Returns the connection the problem belongs to.
     *
     * @return the connection name, empty when the problem is not tied to a connection
     */
    public Optional<String> connection() {
        return Optional.ofNullable(connection);
    }

    /**
     * Returns the config key the problem belongs to.
     *
     * @return the key, empty when the problem is not tied to a key
     */
    public Optional<String> key() {
        return Optional.ofNullable(key);
    }
}
