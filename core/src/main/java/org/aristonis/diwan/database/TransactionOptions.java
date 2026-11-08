package org.aristonis.diwan.database;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Settings of one outermost transaction: how long it waits for a lock and how many times it runs.
 *
 * <p>An instance is immutable. {@link #none()} and the static factories start a chain, and each
 * instance method returns a new copy with one setting changed, so
 * {@code TransactionOptions.lockWait(Duration.ofSeconds(2)).withAttempts(3)} combines both.
 *
 * <p>A lock wait longer than zero is rounded up to whole milliseconds, so a positive duration never
 * becomes "no wait".
 */
public final class TransactionOptions {

    private static final TransactionOptions NONE = new TransactionOptions(-1L, 1);
    private static final long NANOS_PER_MILLI = 1_000_000L;

    // Negative means no lock wait was asked for.
    private final long lockWaitMillis;
    private final int attempts;

    private TransactionOptions(long lockWaitMillis, int attempts) {
        this.lockWaitMillis = lockWaitMillis;
        this.attempts = attempts;
    }

    /**
     * Returns the options that change nothing: the database default lock wait and a single attempt.
     *
     * @return the shared default options, never {@code null}
     */
    public static TransactionOptions none() {
        return NONE;
    }

    /**
     * Returns options with a lock wait and a single attempt.
     *
     * @param wait the longest the transaction waits for a lock, more than zero and at most
     *     {@link Integer#MAX_VALUE} milliseconds; a sub-millisecond value rounds up to 1 ms
     * @return the new options, never {@code null}
     * @throws NullPointerException if {@code wait} is {@code null}
     * @throws IllegalArgumentException if {@code wait} is zero, negative or longer than
     *     {@link Integer#MAX_VALUE} milliseconds
     */
    public static TransactionOptions lockWait(Duration wait) {
        return NONE.withLockWait(wait);
    }

    /**
     * Returns options that run the transaction up to {@code attempts} times and keep the database
     * default lock wait.
     *
     * @param attempts the most times to run the callback, at least 1
     * @return the new options, never {@code null}
     * @throws IllegalArgumentException if {@code attempts} is below 1
     */
    public static TransactionOptions attempts(int attempts) {
        return NONE.withAttempts(attempts);
    }

    /**
     * Returns a copy with a lock wait, keeping the attempts of this instance.
     *
     * @param wait the longest the transaction waits for a lock, more than zero and at most
     *     {@link Integer#MAX_VALUE} milliseconds; a sub-millisecond value rounds up to 1 ms
     * @return the new options, never {@code null}
     * @throws NullPointerException if {@code wait} is {@code null}
     * @throws IllegalArgumentException if {@code wait} is zero, negative or longer than
     *     {@link Integer#MAX_VALUE} milliseconds
     */
    public TransactionOptions withLockWait(Duration wait) {
        Objects.requireNonNull(wait, "wait");
        if (wait.isNegative() || wait.isZero()) {
            throw new IllegalArgumentException("lockWait must be more than zero, was " + wait);
        }
        long millis = ceilMillis(wait);
        if (millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("lockWait must be at most " + Integer.MAX_VALUE
                    + " milliseconds, was " + wait);
        }
        return new TransactionOptions(millis, attempts);
    }

    /**
     * Returns a copy that runs the transaction up to {@code attempts} times, keeping the lock wait of
     * this instance.
     *
     * @param attempts the most times to run the callback, at least 1
     * @return the new options, never {@code null}
     * @throws IllegalArgumentException if {@code attempts} is below 1
     */
    public TransactionOptions withAttempts(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
        return new TransactionOptions(lockWaitMillis, attempts);
    }

    /**
     * Returns the lock wait in milliseconds.
     *
     * @return the wait, or empty when none was set
     */
    public OptionalLong lockWaitMillis() {
        return lockWaitMillis < 0 ? OptionalLong.empty() : OptionalLong.of(lockWaitMillis);
    }

    /**
     * Returns how many times the callback may run.
     *
     * @return the attempts, at least 1
     */
    public int attempts() {
        return attempts;
    }

    // Duration.toMillis would overflow a long for huge values, so large durations are measured in
    // seconds first; anything past the int range is refused by the caller either way.
    private static long ceilMillis(Duration wait) {
        if (wait.getSeconds() > Integer.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long millis = wait.getSeconds() * 1000L;
        long nanos = wait.getNano();
        return millis + (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionOptions)) {
            return false;
        }
        TransactionOptions that = (TransactionOptions) other;
        return lockWaitMillis == that.lockWaitMillis && attempts == that.attempts;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lockWaitMillis, attempts);
    }

    @Override
    public String toString() {
        return "TransactionOptions[lockWaitMillis=" + (lockWaitMillis < 0 ? "none" : lockWaitMillis)
                + ", attempts=" + attempts + "]";
    }
}
