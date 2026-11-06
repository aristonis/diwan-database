package org.aristonis.diwan.database.manager;

import org.aristonis.diwan.database.exception.DatabaseConfigException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * The typed settings of one named connection.
 *
 * <p>Instances are immutable and safe to share between threads. Every string form masks the
 * password, the password inside a URL, and any option whose key names a secret, so a config can
 * be logged and put in an exception message.
 */
public final class ConnectionConfig {

    /** The text shown in place of a secret. */
    public static final String MASK = "***";

    private static final int MAX_PORT = 65_535;
    private static final Pattern URL_SECRET = Pattern.compile("(?i)\\b(password|passwd|pwd)=[^&;\\s]*");
    private static final Pattern URL_USER_INFO = Pattern.compile("(?<=://)([^/:@\\s]*):[^/@\\s]*@");

    private final String name;
    private final String driver;
    private final String url;
    private final String host;
    private final Integer port;
    private final String database;
    private final String username;
    private final String password;
    private final String prefix;
    private final boolean isStrictTransactions;
    private final Map<String, String> options;

    private ConnectionConfig(Builder builder) {
        this.name = builder.name;
        this.driver = builder.driver;
        this.url = builder.url;
        this.host = builder.host;
        this.port = builder.port;
        this.database = builder.database;
        this.username = builder.username;
        this.password = builder.password;
        this.prefix = builder.prefix;
        this.isStrictTransactions = builder.isStrictTransactions;
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(builder.options));
    }

    /**
     * Starts a config for a connection.
     *
     * @param name the connection name, never {@code null} or blank
     * @return a new builder, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Replaces the secrets in a text with {@link #MASK}.
     *
     * <p>Masks {@code password=}, {@code passwd=} and {@code pwd=} values and the password of a
     * {@code scheme://user:password@host} URL. Use it before a URL goes into a message or a log.
     *
     * @param text the text, or {@code null}
     * @return the masked text, or {@code null} when {@code text} is {@code null}
     */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String withoutKeyed = URL_SECRET.matcher(text).replaceAll("$1=" + MASK);
        return URL_USER_INFO.matcher(withoutKeyed).replaceAll("$1:" + MASK + "@");
    }

    /**
     * Returns the connection name.
     *
     * @return the name, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * Returns the driver name that selects the {@link Connector}.
     *
     * @return the driver name, never {@code null} or blank
     */
    public String driver() {
        return driver;
    }

    /**
     * Returns the JDBC URL, when one was configured.
     *
     * @return the URL, which may hold a password, so mask it with {@link #mask(String)} before
     *     logging; empty when none was set
     */
    public Optional<String> url() {
        return Optional.ofNullable(url);
    }

    /**
     * Returns the host name.
     *
     * @return the host, empty when none was set
     */
    public Optional<String> host() {
        return Optional.ofNullable(host);
    }

    /**
     * Returns the port.
     *
     * @return the port between 1 and 65535, empty when none was set
     */
    public OptionalInt port() {
        return port == null ? OptionalInt.empty() : OptionalInt.of(port);
    }

    /**
     * Returns the database name or file path.
     *
     * @return the database, empty when none was set
     */
    public Optional<String> database() {
        return Optional.ofNullable(database);
    }

    /**
     * Returns the user name.
     *
     * @return the user name, empty when none was set
     */
    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    /**
     * Returns the password.
     *
     * @return the password in clear text, empty when none was set; never log it
     */
    public Optional<String> password() {
        return Optional.ofNullable(password);
    }

    /**
     * Returns the table prefix.
     *
     * @return the prefix, empty string when none was set
     */
    public String prefix() {
        return prefix;
    }

    /**
     * Returns whether the connection runs in strict transaction mode.
     *
     * @return {@code true} by default
     */
    public boolean strictTransactions() {
        return isStrictTransactions;
    }

    /**
     * Returns the driver-specific options.
     *
     * @return an unmodifiable map in insertion order, never {@code null}; values may hold secrets
     */
    public Map<String, String> options() {
        return options;
    }

    /**
     * Describes the config with every secret masked.
     *
     * @return the description, never containing the password
     */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("ConnectionConfig{name=").append(name);
        out.append(", driver=").append(driver);
        append(out, "url", mask(url));
        append(out, "host", host);
        append(out, "port", port == null ? null : port.toString());
        append(out, "database", database);
        append(out, "username", username);
        append(out, "password", password == null ? null : MASK);
        out.append(", prefix=").append(prefix);
        out.append(", strictTransactions=").append(isStrictTransactions);
        out.append(", options={");
        String separator = "";
        for (Map.Entry<String, String> entry : options.entrySet()) {
            out.append(separator).append(entry.getKey()).append('=')
                    .append(isSecretKey(entry.getKey()) ? MASK : mask(entry.getValue()));
            separator = ", ";
        }
        return out.append("}}").toString();
    }

    private static void append(StringBuilder out, String label, String value) {
        if (value != null) {
            out.append(", ").append(label).append('=').append(value);
        }
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("passwd") || lower.contains("secret")
                || lower.contains("token") || lower.equals("pwd");
    }

    /**
     * Collects the settings of a {@link ConnectionConfig}.
     *
     * <p>A builder is not thread-safe. Each setter returns the builder.
     */
    public static final class Builder {

        private final String name;
        private final Map<String, String> options = new LinkedHashMap<>();
        private String driver;
        private String url;
        private String host;
        private Integer port;
        private String database;
        private String username;
        private String password;
        private String prefix = "";
        private boolean isStrictTransactions = true;

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.trim().isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            this.name = name;
        }

        /**
         * Sets the driver name.
         *
         * @param driver the driver name, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code driver} is {@code null}
         */
        public Builder driver(String driver) {
            this.driver = Objects.requireNonNull(driver, "driver");
            return this;
        }

        /**
         * Sets the JDBC URL.
         *
         * @param url the URL, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code url} is {@code null}
         */
        public Builder url(String url) {
            this.url = Objects.requireNonNull(url, "url");
            return this;
        }

        /**
         * Sets the host.
         *
         * @param host the host, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code host} is {@code null}
         */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        /**
         * Sets the port.
         *
         * @param port the port, from 1 to 65535
         * @return this builder
         * @throws DatabaseConfigException if the port is out of range
         */
        public Builder port(int port) {
            if (port < 1 || port > MAX_PORT) {
                throw new DatabaseConfigException(
                        "Connection [" + name + "] port must be between 1 and " + MAX_PORT, name, "port");
            }
            this.port = port;
            return this;
        }

        /**
         * Sets the database name or file path.
         *
         * @param database the database, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code database} is {@code null}
         */
        public Builder database(String database) {
            this.database = Objects.requireNonNull(database, "database");
            return this;
        }

        /**
         * Sets the user name.
         *
         * @param username the user name, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code username} is {@code null}
         */
        public Builder username(String username) {
            this.username = Objects.requireNonNull(username, "username");
            return this;
        }

        /**
         * Sets the password.
         *
         * @param password the password, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code password} is {@code null}
         */
        public Builder password(String password) {
            this.password = Objects.requireNonNull(password, "password");
            return this;
        }

        /**
         * Sets the table prefix.
         *
         * @param prefix the prefix, never {@code null}; default empty
         * @return this builder
         * @throws NullPointerException if {@code prefix} is {@code null}
         */
        public Builder prefix(String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
            return this;
        }

        /**
         * Sets strict transaction mode.
         *
         * @param strictTransactions {@code true} for strict mode, the default
         * @return this builder
         */
        public Builder strictTransactions(boolean strictTransactions) {
            this.isStrictTransactions = strictTransactions;
            return this;
        }

        /**
         * Sets a driver-specific option, replacing an earlier value of the same key.
         *
         * @param key the option key, never {@code null} or blank
         * @param value the option value, never {@code null}
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if {@code key} is blank
         */
        public Builder option(String key, String value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (key.trim().isEmpty()) {
                throw new IllegalArgumentException("option key must not be blank");
            }
            options.put(key, value);
            return this;
        }

        /**
         * Builds the config.
         *
         * @return the config, never {@code null}
         * @throws DatabaseConfigException if the driver is missing or blank
         */
        public ConnectionConfig build() {
            if (driver == null || driver.trim().isEmpty()) {
                throw new DatabaseConfigException("Connection [" + name + "] needs a driver", name, "driver");
            }
            return new ConnectionConfig(this);
        }
    }
}
