package org.aristonis.diwan.database.manager;

import org.aristonis.diwan.database.exception.DatabaseConfigException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.aristonis.diwan.database.config.ConfigInterpolator;
import org.aristonis.diwan.database.config.ConfigSource;
import org.aristonis.diwan.database.config.EnvConfigSource;

/**
 * The set of named connection configs and the name of the default one.
 *
 * <p>Read from a {@link ConfigSource} with {@link #from(ConfigSource)}, which uses these keys:
 *
 * <ul>
 *   <li>{@code database.default}: the default connection name, required;
 *   <li>{@code database.connections.<name>.driver}: required;
 *   <li>{@code database.connections.<name>.url}, {@code host}, {@code port}, {@code database},
 *       {@code username}, {@code password}, {@code prefix}, {@code strict_transactions};
 *   <li>any other key directly under a connection becomes an entry of
 *       {@link ConnectionConfig#options()}.
 * </ul>
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class DatabaseConfig {

    private static final String DEFAULT_KEY = "database.default";
    private static final String CONNECTIONS_KEY = "database.connections";
    private static final String STRICT_KEY = "strict_transactions";

    private final String defaultConnection;
    private final Map<String, ConnectionConfig> connections;

    private DatabaseConfig(String defaultConnection, Map<String, ConnectionConfig> connections) {
        this.defaultConnection = defaultConnection;
        this.connections = Collections.unmodifiableMap(new LinkedHashMap<>(connections));
    }

    /**
     * Starts a config in code.
     *
     * @return a new builder, never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads the config from a source, resolving {@code ${NAME}} references from the process
     * environment.
     *
     * @param source the config source, never {@code null}
     * @return the config, never {@code null}
     * @throws NullPointerException if {@code source} is {@code null}
     * @throws DatabaseConfigException if a key is missing or invalid, or a reference cannot be resolved
     */
    public static DatabaseConfig from(ConfigSource source) {
        return from(source, EnvConfigSource.system());
    }

    /**
     * Reads the config from a source, resolving {@code ${NAME}} references from another source.
     *
     * @param source the config source, never {@code null}
     * @param vars the source of variable values, never {@code null}
     * @return the config, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws DatabaseConfigException if a key is missing or invalid, or a reference cannot be resolved
     */
    public static DatabaseConfig from(ConfigSource source, ConfigSource vars) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(vars, "vars");
        Builder builder = new Builder();
        String defaultName = source.get(DEFAULT_KEY)
                .map(raw -> ConfigInterpolator.resolve(DEFAULT_KEY, raw, vars))
                .orElseThrow(() -> new DatabaseConfigException(
                        "Missing config key [" + DEFAULT_KEY + "]", null, DEFAULT_KEY));
        builder.defaultConnection(defaultName);
        for (String name : source.keys(CONNECTIONS_KEY)) {
            builder.connection(readConnection(source, vars, name));
        }
        return builder.build();
    }

    private static ConnectionConfig readConnection(ConfigSource source, ConfigSource vars, String name) {
        String base = CONNECTIONS_KEY + "." + name;
        ConnectionConfig.Builder out = ConnectionConfig.builder(name);
        for (String child : source.keys(base)) {
            String key = base + "." + child;
            Optional<String> raw = source.get(key);
            if (raw.isPresent()) {
                apply(out, name, child, key, ConfigInterpolator.resolve(key, raw.get(), vars));
            }
        }
        return out.build();
    }

    private static void apply(ConnectionConfig.Builder out, String name, String child, String key, String value) {
        switch (child) {
            case "driver":
                out.driver(value);
                break;
            case "url":
                out.url(value);
                break;
            case "host":
                out.host(value);
                break;
            case "port":
                out.port(parsePort(name, key, value));
                break;
            case "database":
                out.database(value);
                break;
            case "username":
                out.username(value);
                break;
            case "password":
                out.password(value);
                break;
            case "prefix":
                out.prefix(value);
                break;
            case STRICT_KEY:
                out.strictTransactions(parseBoolean(name, key, value));
                break;
            default:
                out.option(child, value);
                break;
        }
    }

    // The value is left out of the messages: a mistyped secret must not reach a log.
    private static int parsePort(String name, String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new DatabaseConfigException(
                    "Config key [" + key + "] must be a whole number", name, key);
        }
    }

    private static boolean parseBoolean(String name, String key, String value) {
        String text = value.trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new DatabaseConfigException("Config key [" + key + "] must be true or false", name, key);
    }

    /**
     * Returns the name of the default connection.
     *
     * @return the name of a configured connection, never {@code null}
     */
    public String defaultConnection() {
        return defaultConnection;
    }

    /**
     * Looks up one connection config.
     *
     * @param name the connection name, may be {@code null}
     * @return the config, empty when no connection has that name
     */
    public Optional<ConnectionConfig> connection(String name) {
        return Optional.ofNullable(name == null ? null : connections.get(name));
    }

    /**
     * Returns the configured connection names.
     *
     * @return the names in natural order, unmodifiable, never {@code null}
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(new TreeSet<>(connections.keySet()));
    }

    /**
     * Describes the config by its names only.
     *
     * @return the default name and the connection names, never containing a setting value
     */
    @Override
    public String toString() {
        return "DatabaseConfig{default=" + defaultConnection + ", connections=" + names() + "}";
    }

    /**
     * Collects connection configs in code.
     *
     * <p>A builder is not thread-safe.
     */
    public static final class Builder {

        private final Map<String, ConnectionConfig> connections = new LinkedHashMap<>();
        private String defaultConnection;

        private Builder() {
        }

        /**
         * Sets the default connection name.
         *
         * @param name the name of a connection added to this builder, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public Builder defaultConnection(String name) {
            this.defaultConnection = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Adds a connection config.
         *
         * @param config the config, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code config} is {@code null}
         * @throws DatabaseConfigException if a connection with that name was already added
         */
        public Builder connection(ConnectionConfig config) {
            Objects.requireNonNull(config, "config");
            if (connections.containsKey(config.name())) {
                throw new DatabaseConfigException(
                        "Connection [" + config.name() + "] is configured twice", config.name(), null);
            }
            connections.put(config.name(), config);
            return this;
        }

        /**
         * Checks the parts and builds the config.
         *
         * @return the config, never {@code null}
         * @throws DatabaseConfigException if no default name was set or it names no added connection
         */
        public DatabaseConfig build() {
            if (defaultConnection == null) {
                throw new DatabaseConfigException(
                        "Missing config key [" + DEFAULT_KEY + "]", null, DEFAULT_KEY);
            }
            if (!connections.containsKey(defaultConnection)) {
                throw new DatabaseConfigException("Default connection [" + defaultConnection
                        + "] is not configured. Known connections: " + new TreeSet<>(connections.keySet()),
                        defaultConnection, DEFAULT_KEY);
            }
            return new DatabaseConfig(defaultConnection, connections);
        }
    }
}
