package org.aristonis.photisma.database.migrations;

import java.util.Optional;
import org.aristonis.photisma.database.schema.Schema;

/**
 * One schema change, applied by {@link #up(Schema)} and undone by {@link #down(Schema)}.
 *
 * <p>The name identifies the migration in the migrations table, so it must never change once the
 * migration has run anywhere. Start it with the date, as Laravel's file names do, for example
 * {@code 2026_10_31_000000_create_users_table}, so names sort in the order they were written.
 *
 * <p>Implementations should be stateless: the same instance may be run, rolled back and run again.
 */
public abstract class Migration {

    /** Creates a migration. */
    protected Migration() {
    }

    /**
     * Returns the name recorded in the migrations table.
     *
     * @return the name, never {@code null}; non-blank, at most 191 characters, without control
     *     characters
     */
    public abstract String name();

    /**
     * Applies the change.
     *
     * @param schema the schema builder of the migration's connection, never {@code null}; bound to
     *     the migration's transaction when there is one. Run raw SQL through
     *     {@code schema.connection()}, never through a connection held elsewhere, or it runs outside
     *     that transaction.
     */
    public abstract void up(Schema schema);

    /**
     * Undoes the change.
     *
     * @implSpec The default does nothing, like a Laravel migration without a {@code down} method.
     *
     * @param schema the schema builder of the migration's connection, never {@code null}; bound to
     *     the migration's transaction when there is one
     */
    public void down(Schema schema) {
    }

    /**
     * Tells whether the change runs inside a transaction.
     *
     * @implSpec The default returns {@code true}. Return {@code false} for statements the database
     *     refuses inside a transaction.
     *
     * @return {@code true} to wrap {@link #up(Schema)} and {@link #down(Schema)} in a
     *     transaction
     */
    public boolean withinTransaction() {
        return true;
    }

    /**
     * Tells whether the change should run at all.
     *
     * @implSpec The default returns {@code true}.
     *
     * @return {@code false} to skip the migration
     */
    public boolean shouldRun() {
        return true;
    }

    /**
     * Names the connection the change targets.
     *
     * @implSpec The default returns an empty optional, which means the migrator's default
     *     connection.
     *
     * @return the connection name, or empty for the default connection
     */
    public Optional<String> connection() {
        return Optional.empty();
    }
}
