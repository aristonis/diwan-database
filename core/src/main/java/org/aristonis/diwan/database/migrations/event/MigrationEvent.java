package org.aristonis.diwan.database.migrations.event;

import org.aristonis.diwan.database.Internal;
import java.util.Objects;
import org.aristonis.diwan.database.exception.MigrationFailedException.Direction;

/**
 * Describes one migration being run or undone, for {@link MigrationListener}.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class MigrationEvent {

    private final Direction direction;
    private final String name;
    private final int batch;
    private final boolean isPretend;
    private final String connectionName;

    /**
     * Creates an event for one migration.
     *
     * @param direction whether it runs up or down, never {@code null}
     * @param name the migration's name, never {@code null}
     * @param batch the batch number
     * @param isPretend whether the run is a pretend run
     * @param connectionName the connection's name, never {@code null}
     */
    @Internal
    public MigrationEvent(Direction direction, String name, int batch, boolean isPretend, String connectionName) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.name = Objects.requireNonNull(name, "name");
        this.batch = batch;
        this.isPretend = isPretend;
        this.connectionName = Objects.requireNonNull(connectionName, "connectionName");
    }

    /**
     * Returns the method of the migration that runs.
     *
     * @return {@link Direction#UP} or {@link Direction#DOWN}, never {@code null}
     */
    public Direction direction() {
        return direction;
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
     * Returns the batch the migration is recorded in or removed from.
     *
     * @return the batch number, or 0 for a skipped migration and for one that is only pretended
     *     going up
     */
    public int batch() {
        return batch;
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
     * Returns the name of the connection the migration runs on.
     *
     * @return the connection name, never {@code null}
     */
    public String connectionName() {
        return connectionName;
    }

    /**
     * Describes the event for logs and test output.
     *
     * @return the migration name, direction and batch, never {@code null}
     */
    @Override
    public String toString() {
        return "MigrationEvent[" + name + ", " + direction + (isPretend ? ", pretend" : "") + ", batch " + batch + "]";
    }
}
