package org.aristonis.diwan.database.seeding;

import java.util.Objects;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;

/**
 * What a {@link Seeder} reads and writes through during one run.
 *
 * <p>The connection, query factory and schema are bound to the run: in a transaction run they are
 * the transaction's, and every seeder called from here receives the same ones. A context is used
 * by one thread, the thread that runs the seeders; it is not thread-safe.
 */
public final class SeedContext {

    private final SeedRunner runner;
    private final DatabaseManager manager;
    private final Connection connection;
    private final QueryFactory query;
    private final Schema schema;

    SeedContext(SeedRunner runner, DatabaseManager manager, Connection connection, QueryFactory query,
            Schema schema) {
        this.runner = runner;
        this.manager = manager;
        this.connection = connection;
        this.query = query;
        this.schema = schema;
    }

    /**
     * Returns the manager that started the run, for a seeder that needs another connection.
     *
     * @return the manager, never {@code null}
     */
    public DatabaseManager manager() {
        return manager;
    }

    /**
     * Returns the connection of the run.
     *
     * @return the connection, or the transaction's connection in a transaction run, never {@code null}
     */
    public Connection connection() {
        return connection;
    }

    /**
     * Returns the query factory of the run.
     *
     * @return the factory bound to {@link #connection()}, never {@code null}
     */
    public QueryFactory query() {
        return query;
    }

    /**
     * Returns the schema builder of the run.
     *
     * @return the schema builder bound to {@link #connection()}, never {@code null}
     */
    public Schema schema() {
        return schema;
    }

    /**
     * Runs seeders, one after another, reporting each to the listener.
     *
     * @param seeders creates each seeder, never {@code null}, without {@code null} elements
     * @return this context
     * @throws NullPointerException if an argument is {@code null}, or a supplier returns {@code null}
     * @throws IllegalStateException if a seeder is already running, naming the chain, such as
     *     {@code Seeder cycle: A -> B -> A}
     */
    @SafeVarargs
    public final SeedContext call(Supplier<? extends Seeder>... seeders) {
        if (seeders == null) {
            throw new NullPointerException("seeders");
        }
        for (Supplier<? extends Seeder> seeder : seeders) {
            invoke(seeder, false, false);
        }
        return this;
    }

    /**
     * Runs seeders that have not run yet in this run.
     *
     * <p>A seeder counts as run once any {@code call} variant started it. The record lasts for one
     * {@code DatabaseManager.seed} call, so the next run starts empty.
     *
     * @param seeders creates each seeder, never {@code null}, without {@code null} elements
     * @return this context
     * @throws NullPointerException if an argument is {@code null}, or a supplier returns {@code null}
     * @throws IllegalStateException if a seeder is already running through a plain call
     */
    @SafeVarargs
    public final SeedContext callOnce(Supplier<? extends Seeder>... seeders) {
        if (seeders == null) {
            throw new NullPointerException("seeders");
        }
        for (Supplier<? extends Seeder> seeder : seeders) {
            invoke(seeder, true, false);
        }
        return this;
    }

    /**
     * Runs seeders without reporting them to the listener.
     *
     * @param seeders creates each seeder, never {@code null}, without {@code null} elements
     * @return this context
     * @throws NullPointerException if an argument is {@code null}, or a supplier returns {@code null}
     * @throws IllegalStateException if a seeder is already running, naming the chain
     */
    @SafeVarargs
    public final SeedContext callSilent(Supplier<? extends Seeder>... seeders) {
        if (seeders == null) {
            throw new NullPointerException("seeders");
        }
        for (Supplier<? extends Seeder> seeder : seeders) {
            invoke(seeder, false, true);
        }
        return this;
    }

    private void invoke(Supplier<? extends Seeder> seeder, boolean isOnce, boolean isSilent) {
        runner.invoke(this, Objects.requireNonNull(seeder, "seeder"), isOnce, isSilent);
    }
}
