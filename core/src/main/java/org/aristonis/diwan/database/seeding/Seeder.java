package org.aristonis.diwan.database.seeding;

/**
 * Fills a database with data; the unit {@link SeedContext#call} runs.
 *
 * <p>A seeder is created by a supplier for each call, so it may keep per-run state in its fields.
 * The run is identified by the seeder's class: a class that is already running cannot be called
 * again until it finishes.
 *
 * @implSpec {@link #run(SeedContext)} must use the context's connection, query factory and schema
 *     rather than the manager's, so that a run in a transaction stays in it.
 */
public abstract class Seeder {

    /**
     * Creates a seeder.
     */
    protected Seeder() {
    }

    /**
     * Seeds the database.
     *
     * @param context what the seeder reads and writes through, and how it calls other seeders,
     *     never {@code null}
     * @throws RuntimeException on any failure; the run stops and the exception propagates to the
     *     caller of {@code DatabaseManager.seed}
     */
    protected abstract void run(SeedContext context);
}
