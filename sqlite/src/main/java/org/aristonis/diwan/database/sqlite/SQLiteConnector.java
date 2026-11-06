package org.aristonis.diwan.database.sqlite;

import org.aristonis.diwan.database.sqlite.SingleConnectionDataSource;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.jdbc.DriverManagerDataSource;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.Connector;
import org.aristonis.diwan.database.exception.DatabaseConfigException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;

/**
 * The {@link Connector} for SQLite, driver name {@code sqlite}.
 *
 * <p>The {@code database} setting is a file path or {@code :memory:}. A {@code url} setting, when
 * present, replaces both and is used as given. The {@code foreign_keys} option turns foreign key
 * enforcement on or off for every connection; it is on unless set to {@code false}, as in Laravel.
 *
 * <p>An in-memory database ({@code :memory:}, or a {@code url} of {@code jdbc:sqlite::memory:},
 * {@code jdbc:sqlite:file::memory:} or any URL with {@code mode=memory}) is private to one JDBC
 * connection in SQLite. The connector therefore opens one connection, keeps it for the life of the
 * Diwan connection and lends it to one session at a time; a second session waits until the first
 * ends, up to the {@code busy_timeout} option in milliseconds (default 30000), then fails with an
 * {@link SQLException}. The data lives until {@link #close(Connection)} runs, that is, until the
 * manager purges or closes the connection. A session that needs the database while another session
 * of the same thread holds it, such as a seeder that queries inside its own transaction, must use
 * the transaction's connection.
 *
 * <p>When the application supplies a {@link DataSource}, it is used as is: no URL is built, no
 * option is applied and no connection is held back.
 *
 * <p>Instances are safe to share across threads and across connection names.
 */
public final class SQLiteConnector implements Connector {

    /** The driver name this connector answers to. */
    public static final String DRIVER = "sqlite";

    private static final Logger LOGGER = Logger.getLogger(SQLiteConnector.class.getName());
    private static final String URL_PREFIX = "jdbc:sqlite:";
    private static final String MEMORY = ":memory:";
    private static final String FOREIGN_KEYS = "foreign_keys";
    private static final String BUSY_TIMEOUT = "busy_timeout";
    private static final long DEFAULT_BUSY_TIMEOUT_MILLIS = 30_000L;

    // The single-connection sources are looked up by the identity of the connection they serve.
    private final Map<Connection, SingleConnectionDataSource> memorySources =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * Creates a connector.
     */
    public SQLiteConnector() {
    }

    @Override
    public String driver() {
        return DRIVER;
    }

    /**
     * {@inheritDoc}
     *
     * @throws DatabaseConfigException if neither {@code url} nor {@code database} is set, or if
     *     {@code foreign_keys} is not a boolean
     * @throws IllegalStateException if the in-memory database cannot be opened
     */
    @Override
    public Connection connect(ConnectionConfig config, DataSource dataSource) {
        String url = dataSource == null ? jdbcUrl(config) : null;
        SingleConnectionDataSource memory = url != null && isMemoryUrl(url) ? openMemory(config, url) : null;
        DataSource source = dataSource != null ? dataSource : memory != null ? memory : newDataSource(config, url);
        try {
            Connection connection = Connection.builder().name(config.name()).dataSource(source)
                    .tablePrefix(config.prefix()).strictTransactions(config.strictTransactions()).build();
            if (memory != null) {
                memorySources.put(connection, memory);
            }
            return connection;
        } catch (RuntimeException | Error e) {
            closeQuietly(memory);
            throw e;
        }
    }

    @Override
    public QueryFactory query(Connection connection) {
        return SQLiteQuery.of(connection);
    }

    @Override
    public Schema schema(Connection connection) {
        return SQLiteSchema.of(connection);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closes the connection of an in-memory database, which drops the database. Does nothing for
     * a connection without one.
     */
    @Override
    public void close(Connection connection) {
        closeQuietly(memorySources.remove(connection));
    }

    // Package-private so tests can check the URL without opening a database.
    static String jdbcUrl(ConnectionConfig config) {
        String url = config.url().orElse(null);
        if (url != null) {
            return withForeignKeys(url, config);
        }
        String database = config.database().orElse("");
        if (database.trim().isEmpty()) {
            throw new DatabaseConfigException(
                    "Connection [" + config.name() + "] needs a database path or :memory: (key database)",
                    config.name(), "database");
        }
        return withForeignKeys(URL_PREFIX + database.trim(), config);
    }

    private static String withForeignKeys(String url, ConnectionConfig config) {
        if (url.toLowerCase(Locale.ROOT).contains(FOREIGN_KEYS + "=")) {
            return url;
        }
        String raw = config.options().get(FOREIGN_KEYS);
        boolean isEnabled = raw == null || parseBoolean(config, raw);
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + FOREIGN_KEYS + "=" + isEnabled;
    }

    private static boolean parseBoolean(ConnectionConfig config, String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("1") || text.equals("on")) {
            return true;
        }
        if (text.equals("false") || text.equals("0") || text.equals("off")) {
            return false;
        }
        throw new DatabaseConfigException("Connection [" + config.name() + "] option " + FOREIGN_KEYS
                + " must be true or false, got [" + raw + "]", config.name(), FOREIGN_KEYS);
    }

    // Package-private so tests can check which URLs get a single connection.
    static boolean isMemoryUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(URL_PREFIX)) {
            return false;
        }
        int query = lower.indexOf('?');
        String target = query < 0 ? lower.substring(URL_PREFIX.length()) : lower.substring(URL_PREFIX.length(), query);
        return target.isEmpty() || MEMORY.equals(target) || "file::memory:".equals(target)
                || lower.contains("mode=memory");
    }

    private static DataSource newDataSource(ConnectionConfig config, String url) {
        return new DriverManagerDataSource(url, config.username().orElse(null), config.password().orElse(null));
    }

    private static SingleConnectionDataSource openMemory(ConnectionConfig config, String url) {
        long timeout = busyTimeoutMillis(config);
        java.sql.Connection real;
        try {
            real = newDataSource(config, url).getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Cannot open the in-memory database of connection [" + config.name() + "]", e);
        }
        return new SingleConnectionDataSource(config.name(), real, timeout);
    }

    private static long busyTimeoutMillis(ConnectionConfig config) {
        String raw = config.options().get(BUSY_TIMEOUT);
        if (raw == null) {
            return DEFAULT_BUSY_TIMEOUT_MILLIS;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // Falls through to the same message as a negative number.
        }
        throw new DatabaseConfigException("Connection [" + config.name() + "] option " + BUSY_TIMEOUT
                + " must be a number of milliseconds, zero or more, got [" + raw + "]", config.name(), BUSY_TIMEOUT);
    }

    private static void closeQuietly(SingleConnectionDataSource memory) {
        if (memory == null) {
            return;
        }
        try {
            memory.close();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to close the in-memory SQLite database", e);
        }
    }
}
