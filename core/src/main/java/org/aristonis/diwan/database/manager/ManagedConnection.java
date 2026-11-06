package org.aristonis.diwan.database.manager;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.event.ConnectionListener;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;

/**
 * Holds one named connection of a {@link DatabaseManager}: built on first use, closed once.
 *
 * <p>Each holder has its own lock, so building one connection never blocks another name and the
 * manager's map is never locked while a connector runs. A build that fails closes what it opened and
 * leaves the holder buildable, so the next call tries again. After {@code retire()} the holder never
 * builds again; the manager then drops it and starts a fresh one.
 */
final class ManagedConnection {

    /** What one successful build produced. */
    static final class Parts {

        private final Connection connection;
        private final QueryFactory query;
        private final Schema schema;
        private final Function<Connection, QueryFactory> queryOf;
        private final Function<Connection, Schema> schemaOf;
        private final Supplier<Optional<MigrationLock>> lockSource;

        Parts(Connection connection, QueryFactory query, Schema schema,
                Function<Connection, QueryFactory> queryOf, Function<Connection, Schema> schemaOf,
                Supplier<Optional<MigrationLock>> lockSource) {
            this.connection = connection;
            this.query = query;
            this.schema = schema;
            this.queryOf = queryOf;
            this.schemaOf = schemaOf;
            this.lockSource = lockSource;
        }

        Connection connection() {
            return connection;
        }

        QueryFactory query() {
            return query;
        }

        Schema schema() {
            return schema;
        }

        /** Builds the query factory of a transaction's connection with the same connector. */
        QueryFactory queryOf(Connection other) {
            return queryOf.apply(other);
        }

        /** Builds the schema builder of a transaction's connection with the same connector. */
        Schema schemaOf(Connection other) {
            return schemaOf.apply(other);
        }

        Optional<MigrationLock> lock() {
            return lockSource.get();
        }
    }

    private final ConnectionConfig config;
    private final Connector connector;
    private final Supplier<? extends DataSource> dataSourceSupplier;
    private final ManagerListeners listeners;
    private final ReentrantLock lock = new ReentrantLock();

    /** The thread running {@code build()}, so a listener asking for this name again is refused. */
    private volatile Thread builder;

    /** The built parts; {@code null} before the first build and after retiring. */
    private volatile Parts parts;

    /** Set once, under {@code lock}, when the holder has been closed. */
    private volatile boolean isRetired;

    /** The data source taken from the user's supplier, kept to close it. */
    private DataSource userDataSource;

    ManagedConnection(ConnectionConfig config, Connector connector, Supplier<? extends DataSource> supplier,
            ManagerListeners listeners) {
        this.config = Objects.requireNonNull(config, "config");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.dataSourceSupplier = supplier;
        this.listeners = Objects.requireNonNull(listeners, "listeners");
    }

    /** Returns whether a build has succeeded and the holder was not retired since. */
    boolean isBuilt() {
        return parts != null;
    }

    /**
     * Returns the parts, building them on the first call.
     *
     * @return the parts, or {@code null} when the holder was retired and the caller must ask the
     *     manager for a fresh one
     * @throws IllegalStateException if a listener running inside the build asks for this same
     *     connection on the building thread
     */
    Parts parts() {
        Parts ready = parts;
        if (ready != null) {
            return ready;
        }
        lock.lock();
        try {
            // The lock is reentrant, so without this check the listener's call would build a second time.
            if (builder == Thread.currentThread()) {
                throw new IllegalStateException("A listener asked for connection [" + config.name()
                        + "] while it was being built; use the connection of the event instead");
            }
            if (isRetired) {
                return null;
            }
            if (parts == null) {
                parts = buildMarked();
            }
            return parts;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes what was built and stops the holder from building again.
     *
     * <p>Waits for a build in progress, so a connection is never left open behind a retired holder.
     *
     * @throws RuntimeException the first failure of the connector or the data source, with later
     *     failures added as suppressed
     */
    void retire() {
        lock.lock();
        try {
            isRetired = true;
            Parts built = parts;
            parts = null;
            DataSource source = userDataSource;
            userDataSource = null;
            RuntimeException failure = release(built == null ? null : built.connection(), source);
            if (failure != null) {
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    private Parts buildMarked() {
        builder = Thread.currentThread();
        try {
            return build();
        } finally {
            builder = null;
        }
    }

    // Listeners attach and connectionEstablished fires before the parts are published, so no caller
    // sees a connection without them; a failure here is handled like a failing connector.
    private Parts build() {
        DataSource source = null;
        Connection connection = null;
        try {
            if (dataSourceSupplier != null) {
                source = dataSourceSupplier.get();
                if (source == null) {
                    throw new IllegalStateException(
                            "The data source supplier of connection [" + config.name() + "] returned null");
                }
                userDataSource = source;
            }
            connection = Objects.requireNonNull(connector.connect(config, source), "connector returned no connection");
            for (ConnectionListener listener : listeners.connection()) {
                connection.events().addListener(listener);
            }
            QueryFactory query = connector.query(connection);
            Schema schema = connector.schema(connection);
            DataSource lockSource = source;
            Parts built = new Parts(connection, query, schema, connector::query, connector::schema,
                    () -> connector.lock(config, lockSource));
            ConnectionEstablished event = new ConnectionEstablished(config.name(), connector.driver(), connection);
            for (ManagerListener listener : listeners.manager()) {
                listener.connectionEstablished(event);
            }
            return built;
        } catch (RuntimeException | Error failure) {
            userDataSource = null;
            RuntimeException closing = release(connection, source);
            if (closing != null) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    // Both resources are always tried: a connector that fails to close must not leak the pool.
    private RuntimeException release(Connection connection, DataSource source) {
        RuntimeException first = null;
        if (connection != null) {
            try {
                connector.close(connection);
            } catch (RuntimeException e) {
                first = e;
            }
        }
        if (source instanceof AutoCloseable) {
            try {
                ((AutoCloseable) source).close();
            } catch (Exception e) {
                RuntimeException wrapped = e instanceof RuntimeException
                        ? (RuntimeException) e
                        : new IllegalStateException(
                                "Failed to close the data source of connection [" + config.name() + "]", e);
                if (first == null) {
                    first = wrapped;
                } else {
                    first.addSuppressed(wrapped);
                }
            }
        }
        return first;
    }
}
