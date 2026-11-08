package org.aristonis.diwan.database.query;

import java.util.Objects;
import java.util.OptionalLong;
import org.aristonis.diwan.database.Internal;

/**
 * The row lock of a select: a strength and what to do when the rows are already locked.
 *
 * <p>Instances are immutable.
 *
 * @apiNote Internal: read by dialect grammars; may change without notice.
 */
@Internal
public final class RowLock {

    private final LockStrength strength;
    private final LockWait lockWait;
    private final long timeoutMillis;

    /**
     * Creates the lock.
     *
     * @param strength the strength, never {@code null}
     * @param lockWait the wait behavior, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    RowLock(LockStrength strength, LockWait lockWait) {
        this(strength, lockWait, -1L);
    }

    /**
     * Creates a lock that waits for a conflicting lock at most {@code timeoutMillis}.
     *
     * @param strength the strength, never {@code null}
     * @param timeoutMillis the longest wait in milliseconds, more than zero
     * @throws NullPointerException if {@code strength} is {@code null}
     */
    RowLock(LockStrength strength, long timeoutMillis) {
        this(strength, LockWait.WAIT, timeoutMillis);
    }

    private RowLock(LockStrength strength, LockWait lockWait, long timeoutMillis) {
        this.strength = Objects.requireNonNull(strength, "strength");
        this.lockWait = Objects.requireNonNull(lockWait, "wait");
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Returns the strength.
     *
     * @return the strength, never {@code null}
     */
    public LockStrength strength() {
        return strength;
    }

    /**
     * Returns the wait behavior.
     *
     * @return the wait behavior, never {@code null}
     */
    public LockWait lockWait() {
        return lockWait;
    }

    /**
     * Returns the longest wait for a conflicting lock.
     *
     * @return the wait in milliseconds, empty if the lock has no timeout of its own
     */
    public OptionalLong timeoutMillis() {
        return timeoutMillis < 0 ? OptionalLong.empty() : OptionalLong.of(timeoutMillis);
    }
}
