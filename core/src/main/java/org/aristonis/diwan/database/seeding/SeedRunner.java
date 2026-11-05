package org.aristonis.diwan.database.seeding;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;

/**
 * Runs one seed run: keeps the record of what ran, the chain of running seeders and the listener.
 *
 * <p>The record is per run, so a second run in the same process starts empty. Laravel keeps it in a
 * static that is never reset, which makes a second run in a test skip every {@code callOnce}.
 *
 * <p>A seeder is recorded when it starts, not when it finishes, so {@code callOnce} skips a seeder
 * that already started in this run even if it failed, as Laravel does.
 *
 * @apiNote Callers use {@code DatabaseManager.seed}; this class is public only because the manager
 *     lives in another package.
 */
public final class SeedRunner {

    private static final String CHAIN_SEPARATOR = " -> ";

    private final SeederListener listener;
    private final Set<Class<?>> called = new HashSet<>();
    private final Deque<Class<?>> active = new ArrayDeque<>();

    private SeedRunner(SeederListener listener) {
        this.listener = listener;
    }

    /**
     * Runs a root seeder, in a transaction when the options ask for one.
     *
     * <p>In a transaction the context is bound to the connection the transaction callback receives,
     * and the query factory and schema are rebuilt on it, so nothing a seeder writes can escape the
     * transaction.
     *
     * @param manager the manager the context hands to seeders, never {@code null}
     * @param connection the connection to seed, never {@code null}
     * @param queryOf builds a query factory for a connection, never {@code null}
     * @param schemaOf builds a schema builder for a connection, never {@code null}
     * @param root creates the first seeder, never {@code null}
     * @param options the run options, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a seeder cycle is found
     * @throws RuntimeException any failure of a seeder, rethrown after the rollback
     */
    public static void run(DatabaseManager manager, Connection connection,
            Function<Connection, QueryFactory> queryOf, Function<Connection, Schema> schemaOf,
            Supplier<? extends Seeder> root, SeedOptions options) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(queryOf, "queryOf");
        Objects.requireNonNull(schemaOf, "schemaOf");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(options, "options");
        SeedRunner runner = new SeedRunner(options.listener());
        if (options.isInTransaction()) {
            connection.transaction(inner -> {
                runner.start(runner.bind(manager, inner, queryOf, schemaOf), root);
                return null;
            });
        } else {
            runner.start(runner.bind(manager, connection, queryOf, schemaOf), root);
        }
    }

    private SeedContext bind(DatabaseManager manager, Connection connection,
            Function<Connection, QueryFactory> queryOf, Function<Connection, Schema> schemaOf) {
        return new SeedContext(this, manager, connection, queryOf.apply(connection), schemaOf.apply(connection));
    }

    private void start(SeedContext context, Supplier<? extends Seeder> root) {
        invoke(context, root, false, false);
    }

    /** Runs one seeder for {@code SeedContext}; not for other callers. */
    void invoke(SeedContext context, Supplier<? extends Seeder> supplier, boolean isOnce, boolean isSilent) {
        Seeder seeder = Objects.requireNonNull(supplier.get(), "a seeder supplier returned null");
        Class<?> type = seeder.getClass();
        if (isOnce && called.contains(type)) {
            return;
        }
        if (active.contains(type)) {
            throw new IllegalStateException("Seeder cycle: " + chain(type));
        }
        called.add(type);
        active.addLast(type);
        long begin = System.nanoTime();
        try {
            if (!isSilent) {
                listener.started(type);
            }
            seeder.run(context);
        } catch (RuntimeException | Error failure) {
            if (!isSilent) {
                listener.failed(type, failure);
            }
            throw failure;
        } finally {
            active.removeLast();
        }
        if (!isSilent) {
            listener.finished(type, Duration.ofNanos(System.nanoTime() - begin));
        }
    }

    private String chain(Class<?> repeated) {
        StringBuilder out = new StringBuilder();
        for (Class<?> type : active) {
            out.append(label(type)).append(CHAIN_SEPARATOR);
        }
        return out.append(label(repeated)).toString();
    }

    private static String label(Class<?> type) {
        String simple = type.getSimpleName();
        return simple.isEmpty() ? type.getName() : simple;
    }
}
