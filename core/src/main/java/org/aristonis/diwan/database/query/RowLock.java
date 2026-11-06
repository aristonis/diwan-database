package org.aristonis.diwan.database.query;

import java.util.Objects;
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

    /**
     * Creates the lock.
     *
     * @param strength the strength, never {@code null}
     * @param lockWait the wait behavior, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    RowLock(LockStrength strength, LockWait lockWait) {
        this.strength = Objects.requireNonNull(strength, "strength");
        this.lockWait = Objects.requireNonNull(lockWait, "wait");
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
}
