package org.aristonis.diwan.database.manager;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;

/**
 * Holds one named connection of a {@link DatabaseManager}: built on first use, closed once.
 *
 * <p>Each holder has its own lock, so building one connection never blocks another name and the
 * manager's map is never locked while a connector runs. After {@code retire()} the holder never
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
    private final ReentrantLock lock = new ReentrantLock();

    /** The built parts; {@code null} before the first build and after retiring. */
    private volatile Parts parts;

    /** Set once, under {@code lock}, when the holder has been closed. */
    private volatile boolean isRetired;

    /** The data source taken from the user's supplier, kept to close it. */
    private DataSource userDataSource;

    ManagedConnection(ConnectionConfig config, Connector connector, Supplier<? extends DataSource> supplier) {
        this.config = Objects.requireNonNull(config, "config");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.dataSourceSupplier = supplier;
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
     */
    Parts parts() {
        Parts ready = parts;
        if (ready != null) {
            return ready;
        }
        lock.lock();
        try {
            if (isRetired) {
                return null;
            }
            if (parts == null) {
                parts = build();
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
            QueryFactory query = connector.query(connection);
            Schema schema = connector.schema(connection);
            DataSource lockSource = source;
            return new Parts(connection, query, schema, connector::query, connector::schema,
                    () -> connector.lock(config, lockSource));
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
