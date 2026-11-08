package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Validation, rounding and chaining of {@link TransactionOptions}. */
class TransactionOptionsTest {

    @Test
    void noneHasNoLockWaitAndOneAttempt() {
        TransactionOptions options = TransactionOptions.none();

        assertFalse(options.lockWaitMillis().isPresent());
        assertEquals(1, options.attempts());
    }

    @Test
    void zeroNegativeAndTooLargeDurationsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> TransactionOptions.lockWait(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> TransactionOptions.lockWait(Duration.ofMillis(-5)));
        assertThrows(IllegalArgumentException.class,
                () -> TransactionOptions.lockWait(Duration.ofMillis(Integer.MAX_VALUE + 1L)));
        assertThrows(IllegalArgumentException.class, () -> TransactionOptions.lockWait(Duration.ofDays(400_000_000L)));
    }

    @Test
    void aNullDurationIsRefused() {
        assertThrows(NullPointerException.class, () -> TransactionOptions.lockWait(null));
    }

    @Test
    void aSubMillisecondDurationRoundsUpToOneMillisecond() {
        assertEquals(1L, TransactionOptions.lockWait(Duration.ofNanos(500_000)).lockWaitMillis().getAsLong());
        assertEquals(1L, TransactionOptions.lockWait(Duration.ofNanos(1)).lockWaitMillis().getAsLong());
    }

    @Test
    void aFractionalMillisecondRoundsUp() {
        assertEquals(3L, TransactionOptions.lockWait(Duration.ofNanos(2_000_001)).lockWaitMillis().getAsLong());
        assertEquals(1500L, TransactionOptions.lockWait(Duration.ofMillis(1500)).lockWaitMillis().getAsLong());
    }

    @Test
    void theLargestAllowedDurationIsAccepted() {
        long millis = TransactionOptions.lockWait(Duration.ofMillis(Integer.MAX_VALUE)).lockWaitMillis().getAsLong();

        assertEquals(Integer.MAX_VALUE, millis);
    }

    @Test
    void attemptsBelowOneAreRefusedWithTheUsualMessage() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TransactionOptions.attempts(0));

        assertEquals("attempts must be at least 1, was 0", failure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TransactionOptions.none().withAttempts(-3));
    }

    @Test
    void chainingKeepsBothSettingsAndLeavesTheOriginalUntouched() {
        TransactionOptions base = TransactionOptions.lockWait(Duration.ofSeconds(2));

        TransactionOptions chained = base.withAttempts(3);
        TransactionOptions reversed = TransactionOptions.attempts(4).withLockWait(Duration.ofMillis(10));

        assertEquals(2000L, chained.lockWaitMillis().getAsLong());
        assertEquals(3, chained.attempts());
        assertEquals(1, base.attempts());
        assertEquals(10L, reversed.lockWaitMillis().getAsLong());
        assertEquals(4, reversed.attempts());
    }

    @Test
    void equalSettingsMakeEqualOptions() {
        assertEquals(TransactionOptions.attempts(2), TransactionOptions.none().withAttempts(2));
        assertSame(TransactionOptions.none(), TransactionOptions.none());
    }
}
