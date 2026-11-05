package org.aristonis.diwan.database.manager;

import java.util.Optional;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;

/**
 * Builds the {@link Connection}, query factory and schema builder of one kind of database.
 *
 * <p>A {@link DatabaseManager} holds one connector per {@linkplain #driver() driver name} and asks
 * it to build a connection the first time a configured connection with that driver is used. Each
 * database module supplies its own connector; the core has no driver dependency.
 *
 * @implSpec Implementations must be safe to call from several threads, for different configs at
 *     once. They must not put a password in a URL, an exception message or a log line. The manager
 *     calls {@link #connect} at most once per connection until that connection is purged.
 */
public interface Connector {

    /**
     * Returns the driver name this connector serves.
     *
     * @return the name matched against {@link ConnectionConfig#driver()}, never {@code null} or blank
     */
    String driver();

    /**
     * Builds a connection for a config.
     *
     * <p>When the connector needs a resource that outlives the call, such as the anchor connection
     * that keeps an in-memory database alive, it keeps the resource and releases it in
     * {@link #close(Connection)}.
     *
     * @param config the connection config, never {@code null}
     * @param dataSource the data source the user registered for this connection, or {@code null}
     *     when the connector must build its own from the config
     * @return the connection, never {@code null}
     * @throws DatabaseConfigException if the config lacks a key this driver needs
     */
    Connection connect(ConnectionConfig config, DataSource dataSource);

    /**
     * Builds the query factory of a connection this connector built.
     *
     * @param connection the connection, or the connection of a transaction opened on it, never
     *     {@code null}
     * @return the query factory bound to that connection, never {@code null}
     */
    QueryFactory query(Connection connection);

    /**
     * Builds the schema builder of a connection this connector built.
     *
     * @param connection the connection, or the connection of a transaction opened on it, never
     *     {@code null}
     * @return the schema builder bound to that connection, never {@code null}
     */
    Schema schema(Connection connection);

    /**
     * Returns the lock a migrator takes around a run on this connection.
     *
     * @param config the connection config, never {@code null}
     * @param dataSource the data source the user registered for this connection, or {@code null}
     * @return the lock, or empty when the database has none; the default is empty
     */
    default Optional<MigrationLock> lock(ConnectionConfig config, DataSource dataSource) {
        return Optional.empty();
    }

    /**
     * Releases what {@link #connect} kept for a connection, once the manager drops it.
     *
     * <p>The manager calls this on purge, disconnect and close, before it closes a data source the
     * user registered. The default does nothing.
     *
     * @param connection the connection {@link #connect} returned, never {@code null}
     */
    default void close(Connection connection) {
    }
}
