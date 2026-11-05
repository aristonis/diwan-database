package org.aristonis.diwan.database.seeding;

import java.util.Objects;
import java.util.Optional;

/**
 * The settings of one seed run.
 *
 * <p>Instances are immutable; each setter returns a new instance. Safe to share between threads.
 */
public final class SeedOptions {

    private static final SeedOptions DEFAULTS = new SeedOptions(null, false, SeederListener.NONE);

    private final String connectionName;
    private final boolean isInTransaction;
    private final SeederListener listener;

    private SeedOptions(String connectionName, boolean isInTransaction, SeederListener listener) {
        this.connectionName = connectionName;
        this.isInTransaction = isInTransaction;
        this.listener = listener;
    }

    /**
     * Returns the default options: the manager's default connection, no transaction, no listener.
     *
     * @return the options, never {@code null}
     */
    public static SeedOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Selects the connection to seed.
     *
     * @param name a configured connection name, never {@code null}
     * @return new options with that connection
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public SeedOptions connection(String name) {
        return new SeedOptions(Objects.requireNonNull(name, "name"), isInTransaction, listener);
    }

    /**
     * Chooses whether the whole run is one transaction.
     *
     * <p>In a transaction every seeder, nested ones included, writes through the transaction's
     * connection, and a failure rolls everything back. Without one, rows written before a failure stay.
     *
     * @param inTransaction {@code true} to wrap the run in a transaction
     * @return new options with that setting
     */
    public SeedOptions inTransaction(boolean inTransaction) {
        return new SeedOptions(connectionName, inTransaction, listener);
    }

    /**
     * Sets the listener.
     *
     * @param listener the listener, never {@code null}
     * @return new options with that listener
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public SeedOptions listener(SeederListener listener) {
        return new SeedOptions(connectionName, isInTransaction, Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Returns the selected connection name.
     *
     * @return the name, empty when the manager's default is used
     */
    public Optional<String> connection() {
        return Optional.ofNullable(connectionName);
    }

    /**
     * Returns whether the run is wrapped in a transaction.
     *
     * @return {@code true} when it is; {@code false} by default
     */
    public boolean isInTransaction() {
        return isInTransaction;
    }

    /**
     * Returns the listener.
     *
     * @return the listener, never {@code null}
     */
    public SeederListener listener() {
        return listener;
    }
}
