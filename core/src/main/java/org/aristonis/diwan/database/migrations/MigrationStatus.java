package org.aristonis.diwan.database.migrations;

import java.util.Objects;

/**
 * Where one migration stands, as reported by {@link Migrator#status()}.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class MigrationStatus {

    /** Whether a migration ran. */
    public enum State {
        /** Recorded in the migrations table. */
        RAN,
        /** Given to the migrator and not recorded yet. */
        PENDING,
        /** Recorded in the migrations table but not given to the migrator. */
        MISSING
    }

    private final String name;
    private final State state;
    private final int batch;

    MigrationStatus(String name, State state, int batch) {
        this.name = Objects.requireNonNull(name, "name");
        this.state = Objects.requireNonNull(state, "state");
        this.batch = batch;
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
     * Returns the state.
     *
     * @return the state, never {@code null}
     */
    public State state() {
        return state;
    }

    /**
     * Returns the batch the migration ran in.
     *
     * @return the batch number, or 0 when pending
     */
    public int batch() {
        return batch;
    }

    /**
     * Describes the status for logs and test output.
     *
     * @return the name, state and batch, never {@code null}
     */
    @Override
    public String toString() {
        return "MigrationStatus[" + name + ", " + state + ", batch " + batch + "]";
    }
}
