package org.aristonis.diwan.database.exception;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.MigrationResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Reports that a migration failed, with the work that finished before it.
 *
 * <p>The failure itself is the cause. When the migration ran in a transaction, its changes and its
 * record were undone; migrations before it stay applied and recorded, and later ones did not run.
 * {@link #phase()} tells whether the migration itself failed or only its record: a record that
 * fails after a migration without a transaction, or after one on another connection, leaves the
 * change applied and unrecorded.
 *
 * <p>The message names the migration, direction and phase only. Details are in the cause, subject
 * to the connection's rules on showing values; SQL that a migration writes itself may still contain
 * literal values, so do not send the cause chain to an untrusted place.
 */
public final class MigrationFailedException extends DatabaseException {

    private static final long serialVersionUID = 1L;

    /** Which method of the migration failed. */
    public enum Direction {
        /** {@link Migration#up}. */
        UP,
        /** {@link Migration#down}. */
        DOWN
    }

    /** Which step of a migration failed. */
    public enum Phase {
        /** {@code up} or {@code down} failed. */
        MIGRATION,
        /** Writing or removing the record in the migrations table failed. */
        RECORD
    }

    /** The name of the migration that failed. */
    private final String migration;

    /** The method that failed. */
    private final Direction direction;

    /** The step that failed. */
    private final Phase phase;

    /** The results of the migrations that finished first; not serialized. */
    private final transient List<MigrationResult> completed;

    /**
     * Creates the exception for a migration that failed.
     *
     * @param migration the failed migration's name, never {@code null}
     * @param direction whether it ran up or down, never {@code null}
     * @param phase the step that failed, never {@code null}
     * @param completed the results of the migrations that finished first, never {@code null}
     * @param cause the failure, never {@code null}
     */
    @Internal
    public MigrationFailedException(String migration, Direction direction, Phase phase, List<MigrationResult> completed,
            RuntimeException cause) {
        super("Migration " + migration + " failed while "
                + (phase == Phase.RECORD ? "recording " : "running ") + direction.name().toLowerCase(Locale.ROOT),
                cause);
        this.migration = Objects.requireNonNull(migration, "migration");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.completed = Collections.unmodifiableList(new ArrayList<>(completed));
    }

    /**
     * Returns the name of the migration that failed.
     *
     * @return the name, never {@code null}
     */
    public String migration() {
        return migration;
    }

    /**
     * Returns the method that failed.
     *
     * @return the direction, never {@code null}
     */
    public Direction direction() {
        return direction;
    }

    /**
     * Returns the step that failed.
     *
     * @return the phase, never {@code null}
     */
    public Phase phase() {
        return phase;
    }

    /**
     * Returns the results of the migrations that finished before the failure, in order.
     *
     * @return an unmodifiable list, never {@code null}; empty after deserialization
     */
    public List<MigrationResult> completed() {
        return completed == null ? Collections.<MigrationResult>emptyList() : completed;
    }
}
