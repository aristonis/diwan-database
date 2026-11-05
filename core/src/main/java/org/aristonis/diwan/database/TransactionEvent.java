package org.aristonis.diwan.database;

import java.util.Objects;

/**
 * Describes one transaction level that began, is committing, committed or rolled back.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class TransactionEvent {

    private final String connectionName;
    private final int level;
    private final boolean isSavepoint;

    /** Creates an event; {@code level} is 1 for the outermost transaction. */
    TransactionEvent(String connectionName, int level, boolean isSavepoint) {
        this.connectionName = Objects.requireNonNull(connectionName, "connectionName");
        if (level < 1) {
            throw new IllegalArgumentException("level must be at least 1");
        }
        this.level = level;
        this.isSavepoint = isSavepoint;
    }

    /**
     * Returns the name of the connection.
     *
     * @return the name, never {@code null}
     */
    public String connectionName() {
        return connectionName;
    }

    /**
     * Returns the nesting level the event applies to.
     *
     * @return the level, where 1 is the outermost transaction
     */
    public int level() {
        return level;
    }

    /**
     * Tells whether this level is a savepoint rather than a real transaction.
     *
     * @apiNote Inside a transaction the caller owns, the outermost level is only a savepoint, so
     *     this is {@code true} at level 1 there.
     * @return {@code true} if the level is a savepoint
     */
    public boolean isSavepoint() {
        return isSavepoint;
    }

    /**
     * Describes the event.
     *
     * @return a short text with the connection name, level and kind, never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionEvent[" + connectionName + ", level " + level + (isSavepoint ? ", savepoint]" : "]");
    }
}
