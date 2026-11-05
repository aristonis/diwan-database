package org.aristonis.diwan.database.pgsql;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Optional;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.jdbc.DriverManagerDataSource;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.Connector;
import org.aristonis.diwan.database.manager.DatabaseConfigException;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;

/**
 * The {@link Connector} for PostgreSQL, driver name {@code pgsql}.
 *
 * <p>A connection is described either by a {@code url}, used as given, or by {@code host},
 * {@code port} (default 5432) and {@code database}. The {@code sslmode} option and the
 * {@code search_path} option (also spelled {@code schema}) are added to the URL as the
 * {@code sslmode} and {@code currentSchema} parameters of the PostgreSQL JDBC driver, unless the
 * URL already sets them. The user name and password are never put in the URL; they go to the
 * driver separately. The PostgreSQL JDBC driver must be on the application's class path.
 *
 * <p>The migration lock is a {@link PostgresMigrationLock} over the connection's data source.
 *
 * <p>Instances hold no state and are safe to share across threads.
 */
public final class PostgresConnector implements Connector {

    /** The driver name this connector answers to. */
    public static final String DRIVER = "pgsql";

    /** The port used when a connection sets none. */
    public static final int DEFAULT_PORT = 5432;

    private static final String URL_PREFIX = "jdbc:postgresql://";

    /**
     * Creates a connector.
     */
    public PostgresConnector() {
    }

    @Override
    public String driver() {
        return DRIVER;
    }

    /**
     * {@inheritDoc}
     *
     * @throws DatabaseConfigException if neither {@code url} nor {@code host} and
     *     {@code database} are set
     */
    @Override
    public Connection connect(ConnectionConfig config, DataSource dataSource) {
        return Connection.builder().name(config.name()).dataSource(sourceOf(config, dataSource))
                .tablePrefix(config.prefix()).strictTransactions(config.strictTransactions()).build();
    }

    @Override
    public QueryFactory query(Connection connection) {
        return PostgresQuery.of(connection);
    }

    @Override
    public Schema schema(Connection connection) {
        return PostgresSchema.of(connection);
    }

    /**
     * {@inheritDoc}
     *
     * @return a {@link PostgresMigrationLock} over the supplied data source, or over a new one
     *     built from the settings when none is supplied
     */
    @Override
    public Optional<MigrationLock> lock(ConnectionConfig config, DataSource dataSource) {
        return Optional.of(new PostgresMigrationLock(sourceOf(config, dataSource)));
    }

    private static DataSource sourceOf(ConnectionConfig config, DataSource dataSource) {
        if (dataSource != null) {
            return dataSource;
        }
        return new DriverManagerDataSource(jdbcUrl(config), config.username().orElse(null),
                config.password().orElse(null));
    }

    // Package-private so tests can check the URL without a server.
    static String jdbcUrl(ConnectionConfig config) {
        String base = config.url().orElseGet(() -> hostUrl(config));
        base = withParameter(base, "sslmode", config.options().get("sslmode"));
        String schema = config.options().get("search_path");
        if (schema == null) {
            schema = config.options().get("schema");
        }
        return withParameter(base, "currentSchema", schema);
    }

    private static String hostUrl(ConnectionConfig config) {
        String host = config.host().orElseThrow(() -> new DatabaseConfigException(
                "Connection [" + config.name() + "] needs a url or a host (key host)", config.name(), "host"));
        String database = config.database().orElse("");
        if (database.trim().isEmpty()) {
            throw new DatabaseConfigException(
                    "Connection [" + config.name() + "] needs a database (key database)", config.name(), "database");
        }
        String hostText = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        int port = config.port().orElse(DEFAULT_PORT);
        return URL_PREFIX + hostText + ":" + port + "/" + encode(database);
    }

    private static String withParameter(String url, String name, String value) {
        if (value == null || value.trim().isEmpty() || hasParameter(url, name)) {
            return url;
        }
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + name + "=" + encode(value.trim());
    }

    private static boolean hasParameter(String url, String name) {
        int query = url.indexOf('?');
        return query >= 0 && url.substring(query).matches("(?s).*[?&]" + name + "=.*");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }
}
