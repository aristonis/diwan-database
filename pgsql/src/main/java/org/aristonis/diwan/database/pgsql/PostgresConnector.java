package org.aristonis.diwan.database.pgsql;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.config.LockWaitOption;
import org.aristonis.diwan.database.jdbc.DriverManagerDataSource;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.Connector;
import org.aristonis.diwan.database.exception.DatabaseConfigException;
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
 * <p>The {@code lock_wait} option, a duration such as {@code 5s} or {@code 500ms}, sets the
 * {@code lock_timeout} of every session through the {@code options} parameter of the URL; a value the
 * URL already has in {@code options} is kept and the setting is added after it.
 *
 * <p>The optional {@code timezone} option, an IANA name such as {@code UTC} or an offset such as
 * {@code +03:00}, runs {@code SET TIME ZONE} on every connection handed out (one statement per
 * checkout), as Laravel does after connecting, whether the data source is built from the settings or
 * supplied. It is off by default.
 * Only letters, digits and {@code / _ + - :} are accepted. A pool that resets sessions
 * ({@code DISCARD ALL} or {@code RESET ALL}) undoes it, so with such a pool set the zone in the pool's
 * init SQL or on the server instead.
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

    private static final Pattern OPTIONS_PARAMETER = Pattern.compile("[?&]options=");
    private static final String TIMEZONE_OPTION = "timezone";
    private static final Pattern TIME_ZONE_VALUE = Pattern.compile("[A-Za-z0-9/_+:-]+");
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
     *     {@code database} are set, or the {@code timezone} option holds a disallowed character
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
        String zone = timeZoneOf(config);
        DataSource source = dataSource != null ? dataSource
                : new DriverManagerDataSource(jdbcUrl(config), config.username().orElse(null),
                        config.password().orElse(null));
        return zone == null ? source : new SessionTimeZoneDataSource(source, zone);
    }

    // Package-private so tests can check the URL without a server.
    static String jdbcUrl(ConnectionConfig config) {
        String base = config.url().orElseGet(() -> hostUrl(config));
        base = withParameter(base, "sslmode", config.options().get("sslmode"));
        String schema = config.options().get("search_path");
        if (schema == null) {
            schema = config.options().get("schema");
        }
        return withLockTimeout(withParameter(base, "currentSchema", schema), config);
    }

    private static String withLockTimeout(String url, ConnectionConfig config) {
        String raw = config.options().get(LockWaitOption.KEY);
        if (raw == null) {
            return url;
        }
        long millis = LockWaitOption.parseMillis(config.name(), LockWaitOption.KEY, raw);
        return withServerSetting(url, "-c lock_timeout=" + millis);
    }

    // The validated time zone to set on each connection handed out, or null when the option is off.
    private static String timeZoneOf(ConnectionConfig config) {
        String raw = config.options().get(TIMEZONE_OPTION);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String zone = raw.trim();
        if (!TIME_ZONE_VALUE.matcher(zone).matches()) {
            throw new DatabaseConfigException("Connection [" + config.name() + "] option " + TIMEZONE_OPTION
                    + " may hold only letters, digits and / _ + - :", config.name(), TIMEZONE_OPTION);
        }
        return zone;
    }

    // Adds one "-c name=value" setting to the options parameter, after any value the URL already has.
    private static String withServerSetting(String url, String setting) {
        int start = optionsValueStart(url);
        if (start < 0) {
            return url + (url.indexOf('?') >= 0 ? "&" : "?") + "options=" + encode(setting);
        }
        int end = url.indexOf('&', start);
        int stop = end < 0 ? url.length() : end;
        return url.substring(0, stop) + "%20" + encode(setting) + url.substring(stop);
    }

    // The index just after "options=" in the query, or -1 when the URL has no such parameter.
    private static int optionsValueStart(String url) {
        Matcher matcher = OPTIONS_PARAMETER.matcher(url);
        return matcher.find() ? matcher.end() : -1;
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
