package org.aristonis.diwan.database.eloquent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * Maps connection names to query factories, so a model reaches the database named by its
 * {@code connectionName()}.
 *
 * <p>Pass a resolver to {@link ModelQuery#using(ConnectionResolver)}, or bind a model to one
 * factory with {@link ModelQuery#on(QueryFactory)}. For the Laravel-like path where
 * {@code new User().save()} just works, install a process-wide default with
 * {@link #setDefault(ConnectionResolver)}. Registration and lookup are safe across threads.
 */
public final class ConnectionResolver {

    private static final AtomicReference<ConnectionResolver> DEFAULT = new AtomicReference<>();

    private final ConcurrentMap<String, QueryFactory> factories = new ConcurrentHashMap<>();
    private volatile String defaultConnection = "default";
    private final DatabaseManager manager;

    /**
     * Creates an empty resolver whose default connection name is {@code default}.
     */
    public ConnectionResolver() {
        this.manager = null;
    }

    private ConnectionResolver(DatabaseManager manager) {
        this.manager = manager;
    }

    /**
     * Creates a resolver that reads its connections from a database manager.
     *
     * <p>A name is resolved with {@link DatabaseManager#query(String)}, so a connection is built
     * the first time a model uses it; {@code null} takes the manager's default connection. The
     * resolver owns no state: {@link #register(String, QueryFactory)} and
     * {@link #setDefaultConnection(String)} throw {@link UnsupportedOperationException}; change
     * the manager instead.
     *
     * @param manager the database manager, never {@code null}
     * @return the manager-backed resolver, never {@code null}
     * @throws NullPointerException if {@code manager} is {@code null}
     */
    public static ConnectionResolver of(DatabaseManager manager) {
        return new ConnectionResolver(Objects.requireNonNull(manager, "manager"));
    }

    private UnsupportedOperationException managed(String operation) {
        return new UnsupportedOperationException(
                operation + " is not supported: this resolver reads from " + manager + "; change the manager instead");
    }

    /**
     * Registers a query factory under a name, replacing any factory registered under it.
     *
     * @param name the connection name, never {@code null}
     * @param factory the query factory, never {@code null}
     * @return this resolver
     * @throws NullPointerException if an argument is {@code null}
     * @throws UnsupportedOperationException if this resolver is backed by a database manager
     */
    public ConnectionResolver register(String name, QueryFactory factory) {
        if (manager != null) {
            throw managed("register");
        }
        factories.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(factory, "factory"));
        return this;
    }

    /**
     * Returns whether a factory is registered under a name.
     *
     * @param name the connection name, never {@code null}
     * @return whether it is registered; for a manager-backed resolver, whether the manager has it configured, built or not
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public boolean hasConnection(String name) {
        if (manager != null) {
            return manager.hasConnection(name);
        }
        return factories.containsKey(Objects.requireNonNull(name, "name"));
    }

    /**
     * Returns the factory registered under a name.
     *
     * @param name the connection name, or {@code null} for the default connection
     * @return the query factory, never {@code null}
     * @throws IllegalStateException if no factory is registered under the name:
     *     {@code "No connection is registered as [name]"}
     */
    public QueryFactory resolve(String name) {
        if (manager != null) {
            return name != null ? manager.query(name) : manager.query(manager.getDefaultConnection());
        }
        String key = name != null ? name : defaultConnection;
        QueryFactory factory = factories.get(key);
        if (factory == null) {
            throw new IllegalStateException("No connection is registered as [" + key + "]");
        }
        return factory;
    }

    /**
     * Returns the name used for models without a connection name.
     *
     * @return the default connection name, never {@code null}
     */
    public String getDefaultConnection() {
        if (manager != null) {
            return manager.getDefaultConnection();
        }
        return defaultConnection;
    }

    /**
     * Sets the name used for models without a connection name.
     *
     * @param name the default connection name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws UnsupportedOperationException if this resolver is backed by a database manager
     */
    public void setDefaultConnection(String name) {
        if (manager != null) {
            throw managed("setDefaultConnection");
        }
        defaultConnection = Objects.requireNonNull(name, "name");
    }

    /**
     * Installs a resolver as the process-wide default for models not bound to a factory or
     * resolver. Closing the returned handle puts back the resolver that was the default before,
     * if this one is still installed.
     *
     * @apiNote Process-wide state: every thread and every model class sees it. Prefer
     *     {@link ModelQuery#on(QueryFactory)} or {@link ModelQuery#using(ConnectionResolver)}; in
     *     tests, install with try-with-resources or call {@link #resetDefault()} afterwards.
     * @param resolver the resolver, never {@code null}
     * @return the handle that uninstalls it, never {@code null}
     * @throws NullPointerException if {@code resolver} is {@code null}
     */
    public static Installation setDefault(ConnectionResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        ConnectionResolver previous = DEFAULT.getAndSet(resolver);
        return new Installation(resolver, previous);
    }

    /**
     * Returns the process-wide default resolver.
     *
     * @return the resolver, or empty when none is installed
     */
    public static Optional<ConnectionResolver> getDefault() {
        return Optional.ofNullable(DEFAULT.get());
    }

    /**
     * Removes the process-wide default resolver.
     */
    public static void resetDefault() {
        DEFAULT.set(null);
    }

    /**
     * The handle of an installed default resolver; closing it restores the previous default.
     */
    public static final class Installation implements AutoCloseable {

        private final ConnectionResolver installed;
        private final ConnectionResolver previous;

        private Installation(ConnectionResolver installed, ConnectionResolver previous) {
            this.installed = installed;
            this.previous = previous;
        }

        /**
         * Restores the previous default if this handle's resolver is still installed; otherwise
         * does nothing. Safe to call more than once.
         */
        @Override
        public void close() {
            DEFAULT.compareAndSet(installed, previous);
        }
    }
}
