package org.aristonis.diwan.database.migrations.event;

import org.aristonis.diwan.database.Internal;
import java.util.Objects;
import org.aristonis.diwan.database.exception.MigrationFailedException.Direction;

/**
 * Describes one run, rollback or reset as a whole, for {@link MigrationListener}.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class MigrationsEvent {

    private final Direction direction;
    private final boolean isPretend;
    private final String connectionName;

    /**
     * Creates an event for a whole run.
     *
     * @param direction whether the run goes up or down, never {@code null}
     * @param isPretend whether the run is a pretend run
     * @param connectionName the connection's name, never {@code null}
     */
    @Internal
    public MigrationsEvent(Direction direction, boolean isPretend, String connectionName) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.isPretend = isPretend;
        this.connectionName = Objects.requireNonNull(connectionName, "connectionName");
    }

    /**
     * Returns the direction of the call.
     *
     * @return {@link Direction#UP} for a run, {@link Direction#DOWN} for a rollback or reset, never
     *     {@code null}
     */
    public Direction direction() {
        return direction;
    }

    /**
     * Tells whether the call only collects SQL.
     *
     * @return {@code true} for a pretend call, where nothing changes
     */
    public boolean isPretend() {
        return isPretend;
    }

    /**
     * Returns the name of the connection that holds the migrations table.
     *
     * @return the connection name, never {@code null}
     */
    public String connectionName() {
        return connectionName;
    }

    /**
     * Describes the event for logs and test output.
     *
     * @return the direction, the pretend flag and the connection, never {@code null}
     */
    @Override
    public String toString() {
        return "MigrationsEvent[" + direction + (isPretend ? ", pretend" : "") + ", " + connectionName + "]";
    }
}
