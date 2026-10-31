package org.aristonis.photisma.database.migrations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.aristonis.photisma.database.LoggedQuery;

/**
 * What the {@link Migrator} did with one migration.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class MigrationResult {

    /** What happened to a migration. */
    public enum Outcome {
        /** {@code up} ran and the migration was recorded. */
        RAN,
        /** {@code shouldRun()} returned {@code false}; nothing ran and nothing was recorded. */
        SKIPPED,
        /** {@code down} ran and the record was removed. */
        ROLLED_BACK,
        /** The migrations table lists a name the migrator was not given; the record was kept. */
        NOT_FOUND,
        /** Pretend mode: the SQL was collected and nothing changed. */
        PRETENDED
    }

    private final String name;
    private final Outcome outcome;
    private final int batch;
    private final List<LoggedQuery> queries;

    MigrationResult(String name, Outcome outcome, int batch, List<LoggedQuery> queries) {
        this.name = Objects.requireNonNull(name, "name");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.batch = batch;
        this.queries = Collections.unmodifiableList(new ArrayList<>(queries));
    }

    /**
     * Returns the migration name.
     *
     * @return the name, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * Returns what happened.
     *
     * @return the outcome, never {@code null}
     */
    public Outcome outcome() {
        return outcome;
    }

    /**
     * Returns the batch the migration was recorded in or removed from.
     *
     * @return the batch number, or 0 for a skipped or pretended migration
     */
    public int batch() {
        return batch;
    }

    /**
     * Returns the SQL collected in pretend mode.
     *
     * @return an unmodifiable list in the order the statements would run, never {@code null}; empty
     *     unless the outcome is {@link Outcome#PRETENDED}
     */
    public List<LoggedQuery> queries() {
        return queries;
    }

    /**
     * Describes the result for logs and test output.
     *
     * @return the name, outcome and batch, never {@code null}
     */
    @Override
    public String toString() {
        return "MigrationResult[" + name + ", " + outcome + ", batch " + batch + "]";
    }
}
