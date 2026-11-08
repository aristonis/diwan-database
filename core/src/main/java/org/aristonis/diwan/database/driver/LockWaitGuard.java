package org.aristonis.diwan.database.driver;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.exception.DriverException;

/**
 * Applies a lock wait to one transaction and takes it back.
 *
 * <p>The core calls {@link #beforeBegin()}, then {@link DriverSession#begin()}, then
 * {@link #afterBegin()}, and finally {@link #restore()} once the transaction ended by commit or
 * rollback, or when starting it failed. A database that applies the wait before the transaction
 * starts does its work in {@code beforeBegin}; one that applies it inside the transaction does it in
 * {@code afterBegin}. A guard serves one transaction and is not reused.
 *
 * <p>A guard from {@link DriverSession#statementLockWait(long)} serves one statement: the core calls
 * {@link #beforeBegin()} to apply the wait, runs the statement and calls {@link #restore()}, and
 * never calls {@link #afterBegin()}.
 *
 * @implSpec {@link #restore()} runs exactly once per guard from the core, also after a failed
 *     {@link #beforeBegin()}, {@link DriverSession#begin()} or {@link #afterBegin()}, so it must cope
 *     with a guard whose earlier steps did not complete.
 * @apiNote Internal: driver SPI for dialect modules.
 */
@Internal
public interface LockWaitGuard {

    /**
     * Applies the wait before the transaction starts.
     *
     * @throws DriverException if the wait cannot be applied
     */
    void beforeBegin();

    /**
     * Applies the wait inside the transaction, right after it started.
     *
     * @throws DriverException if the wait cannot be applied
     */
    void afterBegin();

    /**
     * Puts the session back to the lock wait it had before this guard, after the transaction ended.
     *
     * @throws DriverException if the previous wait cannot be put back
     */
    void restore();
}
