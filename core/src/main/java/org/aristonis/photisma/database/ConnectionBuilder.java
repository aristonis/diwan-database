package org.aristonis.photisma.database;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.aristonis.photisma.database.driver.Driver;
import org.aristonis.photisma.database.jdbc.DataSourceConnectionProvider;
import org.aristonis.photisma.database.jdbc.JdbcConnectionProvider;
import org.aristonis.photisma.database.jdbc.JdbcDriver;

/**
 * Configures and builds a root {@link Connection}; obtained from {@link Connection#builder()}.
 *
 * <p>A name and exactly one driver source are required: {@link #driver(Driver)},
 * {@link #dataSource(DataSource)} or {@link #connectionProvider(JdbcConnectionProvider)}. The other settings have defaults: an empty
 * table prefix, bindings hidden in error messages, strict transactions on, and cleanup failures
 * logged as warnings through {@code java.util.logging}.
 *
 * <p>A builder is not thread-safe. The connection it builds keeps a snapshot of the settings, so
 * changing the builder afterwards does not affect it.
 */
public final class ConnectionBuilder {

    // The prefix is pasted into identifiers by the grammars, so it must not be able to carry SQL.
    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]*");

    private static final Logger LOGGER = Logger.getLogger(ConnectionBuilder.class.getName());

    private String name;
    private Driver driver;
    private String tablePrefix = "";
    private boolean showBindingsInErrors;
    private boolean strictTransactions = true;
    private Consumer<Throwable> cleanupFailureHandler = ConnectionBuilder::logCleanupFailure;

    ConnectionBuilder() {
    }

    /**
     * Sets the connection name, which appears in every {@link QueryException}.
     *
     * @param name the name, never {@code null} or blank
     * @return this builder
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is empty or only whitespace
     */
    public ConnectionBuilder name(String name) {
        Objects.requireNonNull(name, "name");
        // trim() rather than isBlank(): isBlank is missing on Android 9.
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        return this;
    }

    /**
     * Sets the driver that opens sessions on the database.
     *
     * @param driver the driver, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code driver} is {@code null}
     * @throws IllegalStateException if a driver source was already set
     */
    public ConnectionBuilder driver(Driver driver) {
        Objects.requireNonNull(driver, "driver");
        if (this.driver != null) {
            throw new IllegalStateException("a driver source is already set");
        }
        this.driver = driver;
        return this;
    }

    /**
     * Sets a JDBC data source as the driver source; each session takes a connection from it and
     * closes the connection when done.
     *
     * @param dataSource the data source, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code dataSource} is {@code null}
     * @throws IllegalStateException if a driver source was already set
     */
    public ConnectionBuilder dataSource(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return driver(new JdbcDriver(new DataSourceConnectionProvider(dataSource)));
    }

    /**
     * Sets a JDBC connection provider as the driver source.
     *
     * @apiNote Use this to join transactions managed elsewhere, such as Spring's: a provider that
     *     reports them as external makes Photisma nest inside them with savepoints.
     * @param provider the provider, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code provider} is {@code null}
     * @throws IllegalStateException if a driver source was already set
     */
    public ConnectionBuilder connectionProvider(JdbcConnectionProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return driver(new JdbcDriver(provider));
    }

    /**
     * Sets the prefix added to table names.
     *
     * @param tablePrefix the prefix, never {@code null}, may be empty, only ASCII letters, digits and
     *     underscores; the default is empty
     * @return this builder
     * @throws NullPointerException if {@code tablePrefix} is {@code null}
     * @throws IllegalArgumentException if {@code tablePrefix} holds any other character
     */
    public ConnectionBuilder tablePrefix(String tablePrefix) {
        Objects.requireNonNull(tablePrefix, "tablePrefix");
        if (!TABLE_PREFIX.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("tablePrefix may hold only ASCII letters, digits and underscores");
        }
        this.tablePrefix = tablePrefix;
        return this;
    }

    /**
     * Sets whether binding values appear in {@link QueryException} messages.
     *
     * @apiNote Bindings can hold personal data, which is why the default is {@code false}. The
     *     exception carries them in {@link QueryException#getBindings()} either way.
     * @param showBindingsInErrors {@code true} to append binding values to error messages
     * @return this builder
     */
    public ConnectionBuilder showBindingsInErrors(boolean showBindingsInErrors) {
        this.showBindingsInErrors = showBindingsInErrors;
        return this;
    }

    /**
     * Sets whether the root connection refuses calls from a thread that is inside a transaction it
     * opened.
     *
     * @apiNote Such a call would borrow a second connection outside the transaction, so its writes
     *     escape the rollback and a small pool can deadlock. Switch it off only for code that writes
     *     outside the transaction on purpose, such as an audit log. The default is {@code true}.
     * @param strictTransactions {@code true} to refuse such calls
     * @return this builder
     */
    public ConnectionBuilder strictTransactions(boolean strictTransactions) {
        this.strictTransactions = strictTransactions;
        return this;
    }

    /**
     * Sets the handler that receives failures to give a session back after the work succeeded.
     *
     * @apiNote Such a failure never fails the call, because the work already took effect. The
     *     default handler logs it as a warning through {@code java.util.logging}.
     * @param handler the handler, never {@code null}; it may be called from any thread that uses
     *     the connection
     * @return this builder
     * @throws NullPointerException if {@code handler} is {@code null}
     */
    public ConnectionBuilder onCleanupFailure(Consumer<Throwable> handler) {
        this.cleanupFailureHandler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Builds the root connection.
     *
     * @return a new connection, safe to share between threads, never {@code null}
     * @throws IllegalStateException if no name or no driver source was set
     */
    public Connection build() {
        if (name == null) {
            throw new IllegalStateException("a connection name is required");
        }
        if (driver == null) {
            throw new IllegalStateException("a driver source is required");
        }
        return new RootConnection(driver, new ConnectionSettings(
                name, tablePrefix, showBindingsInErrors, strictTransactions, cleanupFailureHandler));
    }

    private static void logCleanupFailure(Throwable failure) {
        LOGGER.log(Level.WARNING, "Failed to give a database session back after the work succeeded", failure);
    }
}
