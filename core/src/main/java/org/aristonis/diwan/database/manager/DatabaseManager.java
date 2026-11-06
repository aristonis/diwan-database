package org.aristonis.diwan.database.manager;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.event.ConnectionListener;
import org.aristonis.diwan.database.migrations.event.MigrationListener;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.seeding.SeedOptions;
import org.aristonis.diwan.database.seeding.SeedRunner;
import org.aristonis.diwan.database.seeding.Seeder;

/**
 * The entry point that builds named connections from a {@link DatabaseConfig} the first time each
 * is used and hands out their {@link Connection}, {@link QueryFactory}, {@link Schema} and
 * {@link Migrator.Builder}.
 *
 * <p>Each name is built once, even when several threads ask at the same moment, and building one
 * name never blocks another. The manager keeps what the {@link Connector} built until
 * {@link #purge(String)}, {@link #disconnect(String)} or {@link #close()}.
 *
 * <p>Instances are safe to share between threads. Closing the manager, or purging a name, while
 * another thread still runs a statement on that connection closes the connection under it; that
 * statement may fail with the driver's own error, as it does in Laravel.
 *
 * @apiNote The manager does not pool: without a registered data source a connector builds a plain
 *     one from the config, and every session opens its own physical connection. A server that
 *     needs a pool registers one with {@link Builder#dataSource(String, Supplier)}.
 */
public final class DatabaseManager implements AutoCloseable {

    private final DatabaseConfig config;
    private final Map<String, Connector> connectors;
    private final Map<String, Supplier<? extends DataSource>> dataSources;
    private final ConcurrentMap<String, ManagedConnection> holders = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<String>> overrides = new ThreadLocal<>();
    private final ManagerListeners listeners;
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private volatile String defaultName;

    private DatabaseManager(Builder builder) {
        this.config = builder.config;
        this.connectors = Collections.unmodifiableMap(new LinkedHashMap<>(builder.connectors));
        this.dataSources = Collections.unmodifiableMap(new LinkedHashMap<>(builder.dataSources));
        this.listeners = builder.listeners.copy();
        this.defaultName = config.defaultConnection();
    }

    /**
     * Starts a manager.
     *
     * @return a new builder, never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the default connection, built on first use.
     *
     * @return the connection, never {@code null}
     * @throws IllegalStateException if the manager is closed
     */
    public Connection connection() {
        return connection(getDefaultConnection());
    }

    /**
     * Returns a named connection, built on first use.
     *
     * @param name the connection name, never {@code null}
     * @return the connection, never {@code null}; the same instance until the name is purged
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if no connection or no connector matches, listing the known ones
     * @throws IllegalStateException if the manager is closed
     */
    public Connection connection(String name) {
        return parts(name).connection();
    }

    /**
     * Returns the query factory of the default connection.
     *
     * @return the factory, never {@code null}
     * @throws IllegalStateException if the manager is closed
     */
    public QueryFactory query() {
        return query(getDefaultConnection());
    }

    /**
     * Returns the query factory of a named connection.
     *
     * @param name the connection name, never {@code null}
     * @return the factory, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if no connection or no connector matches
     * @throws IllegalStateException if the manager is closed
     */
    public QueryFactory query(String name) {
        return parts(name).query();
    }

    /**
     * Returns the schema builder of the default connection.
     *
     * @return the schema builder, never {@code null}
     * @throws IllegalStateException if the manager is closed
     */
    public Schema schema() {
        return schema(getDefaultConnection());
    }

    /**
     * Returns the schema builder of a named connection.
     *
     * @param name the connection name, never {@code null}
     * @return the schema builder, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if no connection or no connector matches
     * @throws IllegalStateException if the manager is closed
     */
    public Schema schema(String name) {
        return parts(name).schema();
    }

    /**
     * Starts a migrator on a named connection, with that connector's lock when it has one.
     *
     * <p>The connection is also added under its own name, so a migration that names it through
     * {@code Migration.connection()} runs there.
     *
     * @param name the connection name, never {@code null}
     * @return a migrator builder to add migrations to, already carrying the manager's migration
     *     listeners, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if no connection or no connector matches
     * @throws IllegalStateException if the manager is closed
     */
    public Migrator.Builder migrator(String name) {
        ManagedConnection.Parts parts = parts(name);
        Migrator.Builder builder = Migrator.builder(parts.schema()).connection(name, parts.schema());
        listeners.migration().forEach(builder::listener);
        Optional<MigrationLock> lock = parts.lock();
        return lock.isPresent() ? builder.lock(lock.get()) : builder;
    }

    /**
     * Returns the name of the connection {@link #connection()} uses on this thread.
     *
     * @return the innermost {@link #usingConnection(String, Supplier)} name of this thread, else
     *     the default name
     * @throws IllegalStateException if the manager is closed
     */
    public String getDefaultConnection() {
        ensureOpen();
        Deque<String> stack = overrides.get();
        return stack == null ? defaultName : stack.peek();
    }

    /**
     * Changes the default connection for every thread.
     *
     * @param name the name of a configured connection, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if no connection has that name
     * @throws IllegalStateException if the manager is closed
     */
    public void setDefaultConnection(String name) {
        ensureOpen();
        this.defaultName = configOf(name).name();
    }

    /**
     * Runs work with another connection as this thread's default, then restores it.
     *
     * <p>Only the calling thread is affected; other threads keep their default. The previous
     * default returns when the work ends, normally or by an exception.
     *
     * @param <R> the type of the result
     * @param name the name of a configured connection, never {@code null}
     * @param work the work, never {@code null}
     * @return the result of {@code work}, may be {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if no connection has that name
     * @throws IllegalStateException if the manager is closed
     */
    public <R> R usingConnection(String name, Supplier<R> work) {
        Objects.requireNonNull(work, "work");
        String known = configOf(name).name();
        Deque<String> stack = overrides.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            overrides.set(stack);
        }
        stack.push(known);
        try {
            return work.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                overrides.remove();
            }
        }
    }

    /**
     * Runs work with another connection as this thread's default, then restores it.
     *
     * @param name the name of a configured connection, never {@code null}
     * @param work the work, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if no connection has that name
     * @throws IllegalStateException if the manager is closed
     * @see #usingConnection(String, Supplier)
     */
    public void usingConnection(String name, Runnable work) {
        Objects.requireNonNull(work, "work");
        usingConnection(name, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Returns whether a connection is configured under a name, built or not.
     *
     * @param name the connection name, never {@code null}
     * @return {@code true} when the config has a connection with that name
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public boolean hasConnection(String name) {
        return config.connection(Objects.requireNonNull(name, "name")).isPresent();
    }

    /**
     * Returns the names of the connections built so far.
     *
     * @return the names in natural order, a snapshot that is unmodifiable, never {@code null}
     */
    public Set<String> getConnections() {
        Set<String> built = new TreeSet<>();
        for (Map.Entry<String, ManagedConnection> entry : holders.entrySet()) {
            if (entry.getValue().isBuilt()) {
                built.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(built);
    }

    /**
     * Closes a connection and forgets it; the next use builds a new one from the same config.
     *
     * <p>Does nothing when the name was never built.
     *
     * @param name the connection name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if no connection has that name
     * @throws IllegalStateException if the manager is closed, or the connector or a registered data
     *     source failed to close
     */
    public void purge(String name) {
        ensureOpen();
        String known = configOf(name).name();
        // Remove first, then close: a thread that built nothing yet gets a fresh holder, not a closed one.
        ManagedConnection removed = holders.remove(known);
        if (removed != null) {
            removed.retire();
        }
    }

    /**
     * Closes a connection like {@link #purge(String)}.
     *
     * <p>Laravel keeps the connection object and drops only its PDO; here the config is what
     * persists, so both calls end in the same state.
     *
     * @param name the connection name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if no connection has that name
     * @throws IllegalStateException if the manager is closed, or closing failed
     */
    public void disconnect(String name) {
        purge(name);
    }

    /**
     * Closes a connection and builds a new one.
     *
     * @param name the connection name, never {@code null}
     * @return the new connection, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if no connection or no connector matches
     * @throws IllegalStateException if the manager is closed, or closing failed
     */
    public Connection reconnect(String name) {
        purge(name);
        return connection(name);
    }

    /**
     * Runs a seeder with the default connection, outside a transaction.
     *
     * @param root creates the first seeder, never {@code null}
     * @throws NullPointerException if {@code root} is {@code null}
     * @throws IllegalStateException if the manager is closed or a seeder cycle is found
     */
    public void seed(Supplier<? extends Seeder> root) {
        seed(root, SeedOptions.defaults());
    }

    /**
     * Runs a seeder.
     *
     * @param root creates the first seeder, never {@code null}
     * @param options which connection, whether to use a transaction, and the listener, never
     *     {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the connection named in the options is unknown
     * @throws IllegalStateException if the manager is closed or a seeder cycle is found
     * @throws RuntimeException any failure of a seeder, rethrown after the transaction rolled back
     */
    public void seed(Supplier<? extends Seeder> root, SeedOptions options) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(options, "options");
        String name = options.connection().orElseGet(this::getDefaultConnection);
        ManagedConnection.Parts parts = parts(name);
        SeedRunner.run(this, parts.connection(), parts::queryOf, parts::schemaOf, root, options);
    }

    /**
     * Closes every built connection. Calling it again does nothing.
     *
     * <p>Every holder is closed even when one fails; the first failure is thrown after the rest,
     * with later ones attached as suppressed. Afterwards every other method throws
     * {@link IllegalStateException}.
     *
     * @throws IllegalStateException if a connector or a registered data source failed to close
     */
    @Override
    public void close() {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        for (String name : holders.keySet()) {
            ManagedConnection removed = holders.remove(name);
            if (removed == null) {
                continue;
            }
            try {
                removed.retire();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private ManagedConnection.Parts parts(String name) {
        ConnectionConfig connectionConfig = configOf(name);
        Connector connector = connectorOf(connectionConfig);
        while (true) {
            ManagedConnection holder = holders.computeIfAbsent(connectionConfig.name(),
                    key -> new ManagedConnection(connectionConfig, connector, dataSources.get(key), listeners));
            ManagedConnection.Parts parts = holder.parts();
            if (parts == null) {
                // Retired between the lookup and the build: drop it if it is still mapped, and retry.
                holders.remove(connectionConfig.name(), holder);
                ensureOpen();
                continue;
            }
            if (isClosed.get()) {
                holders.remove(connectionConfig.name(), holder);
                IllegalStateException closed = closedFailure();
                try {
                    holder.retire();
                } catch (RuntimeException e) {
                    closed.addSuppressed(e);
                }
                throw closed;
            }
            return parts;
        }
    }

    private ConnectionConfig configOf(String name) {
        Objects.requireNonNull(name, "name");
        ensureOpen();
        return config.connection(name).orElseThrow(() -> new IllegalArgumentException(
                "Connection [" + name + "] is not configured. Known connections: " + config.names()));
    }

    private Connector connectorOf(ConnectionConfig connectionConfig) {
        Connector connector = connectors.get(connectionConfig.driver());
        if (connector == null) {
            throw new IllegalArgumentException("No connector is registered for driver ["
                    + connectionConfig.driver() + "] of connection [" + connectionConfig.name()
                    + "]. Known drivers: " + new TreeSet<>(connectors.keySet()));
        }
        return connector;
    }

    private void ensureOpen() {
        if (isClosed.get()) {
            throw closedFailure();
        }
    }

    private static IllegalStateException closedFailure() {
        return new IllegalStateException("The database manager is closed");
    }

    /**
     * Collects what a {@link DatabaseManager} needs and checks it before the first use.
     *
     * <p>A builder is not thread-safe.
     */
    public static final class Builder {

        private final Map<String, Connector> connectors = new LinkedHashMap<>();
        private final Map<String, Supplier<? extends DataSource>> dataSources = new LinkedHashMap<>();
        private final ManagerListeners listeners = new ManagerListeners();
        private DatabaseConfig config;

        private Builder() {
        }

        /**
         * Sets the connection configs.
         *
         * @param config the config, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code config} is {@code null}
         */
        public Builder config(DatabaseConfig config) {
            this.config = Objects.requireNonNull(config, "config");
            return this;
        }

        /**
         * Registers connectors, each serving the driver name it reports.
         *
         * @param connectors the connectors, never {@code null}, without {@code null} elements
         * @return this builder
         * @throws NullPointerException if the array or an element is {@code null}
         * @throws IllegalArgumentException if a driver name is registered twice
         */
        public Builder connector(Connector... connectors) {
            for (Connector connector : Objects.requireNonNull(connectors, "connectors")) {
                Objects.requireNonNull(connector, "connector");
                String driver = connector.driver();
                if (this.connectors.putIfAbsent(driver, connector) != null) {
                    throw new IllegalArgumentException("The driver [" + driver + "] is registered twice");
                }
            }
            return this;
        }

        /**
         * Supplies the data source of one connection, such as a pooled one, instead of the one the
         * connector would build from the config.
         *
         * <p>The supplier runs once per build, under that connection's own lock. When the data
         * source is {@link AutoCloseable} the manager closes it after the connector, on purge,
         * disconnect and close.
         *
         * @param name a configured connection name, never {@code null}
         * @param supplier creates the data source, never {@code null}; must not return {@code null}
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if a supplier is already registered for the name
         */
        public Builder dataSource(String name, Supplier<? extends DataSource> supplier) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(supplier, "supplier");
            if (dataSources.putIfAbsent(name, supplier) != null) {
                throw new IllegalArgumentException("A data source is already registered for [" + name + "]");
            }
            return this;
        }

        /**
         * Attaches a listener to every connection the manager builds or rebuilds, before it is published.
         *
         * @param listener the listener, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code listener} is {@code null}
         */
        public Builder listener(ConnectionListener listener) {
            listeners.addConnection(listener);
            return this;
        }

        /**
         * Registers a listener told, before it is published, of each connection the manager builds.
         *
         * @param listener the listener, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code listener} is {@code null}
         */
        public Builder managerListener(ManagerListener listener) {
            listeners.addManager(listener);
            return this;
        }

        /**
         * Adds a listener to every migrator builder that {@link #migrator(String)} returns.
         *
         * @param listener the listener, never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code listener} is {@code null}
         */
        public Builder migrationListener(MigrationListener listener) {
            listeners.addMigration(listener);
            return this;
        }

        /**
         * Checks the parts and builds the manager. No connection is opened yet.
         *
         * @return the manager, never {@code null}
         * @throws IllegalStateException if no config was set
         * @throws IllegalArgumentException if a data source names a connection that is not configured
         */
        public DatabaseManager build() {
            if (config == null) {
                throw new IllegalStateException("A database config is required");
            }
            for (String name : dataSources.keySet()) {
                if (!config.connection(name).isPresent()) {
                    throw new IllegalArgumentException("A data source is registered for [" + name
                            + "], which is not configured. Known connections: " + config.names());
                }
            }
            return new DatabaseManager(this);
        }
    }
}
