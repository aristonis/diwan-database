package org.aristonis.diwan.database.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The timeout overload of {@code lock}: its validation, the flag on the lock and where the limit is applied. */
class LockTimeoutTest {

    /** A grammar that writes nothing for a lock and does not need a transaction for it. */
    static final class QuietGrammar extends QueryGrammar {
        QuietGrammar() {
            super("");
        }

        @Override
        protected String compileLock(Builder query, RowLock lock) {
            return "";
        }
    }

    private final RecordingConnection recording = new RecordingConnection();
    private final QueryFactory db = recording.db(new QuietGrammar());

    @Test
    void theLockKeepsTheTimeoutInMilliseconds() {
        RowLock lock = db.table("users").lock(LockStrength.UPDATE, Duration.ofSeconds(3)).getLock().orElseThrow();

        assertEquals(3000L, lock.timeoutMillis().getAsLong());
        assertEquals(LockWait.WAIT, lock.lockWait());
        assertEquals(LockStrength.UPDATE, lock.strength());
    }

    @Test
    void aPlainLockHasNoTimeout() {
        assertTrue(db.table("users").lockForUpdate().getLock().orElseThrow().timeoutMillis().isEmpty());
    }

    @Test
    void aSubMillisecondTimeoutRoundsUpToOneMillisecond() {
        assertEquals(1L, db.table("users").lock(LockStrength.SHARE, Duration.ofNanos(1)).getLock().orElseThrow()
                .timeoutMillis().getAsLong());
        assertEquals(2L, db.table("users").lock(LockStrength.SHARE, Duration.ofNanos(1_000_001)).getLock()
                .orElseThrow().timeoutMillis().getAsLong());
    }

    @Test
    void theLargestTimeoutIsAcceptedAndOneMoreMillisecondIsNot() {
        assertEquals(Integer.MAX_VALUE, db.table("users")
                .lock(LockStrength.UPDATE, Duration.ofMillis(Integer.MAX_VALUE)).getLock().orElseThrow()
                .timeoutMillis().getAsLong());
        assertThrows(IllegalArgumentException.class,
                () -> db.table("users").lock(LockStrength.UPDATE, Duration.ofMillis(Integer.MAX_VALUE + 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> db.table("users").lock(LockStrength.UPDATE, Duration.ofDays(Long.MAX_VALUE / 100_000)));
    }

    @Test
    void zeroAndNegativeTimeoutsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> db.table("users").lock(LockStrength.UPDATE, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> db.table("users").lock(LockStrength.UPDATE, Duration.ofMillis(-5)));
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThrows(NullPointerException.class, () -> db.table("users").lock(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> db.table("users").lock(LockStrength.UPDATE, (Duration) null));
    }

    @Test
    void aTimedSelectInsideATransactionSetsTheLimitBeforeTheSelect() {
        recording.connection.transaction(connection -> db.table("users").lock(LockStrength.UPDATE,
                Duration.ofMillis(250)).get());

        List<String> methods = recording.calls.stream().map(call -> call.method()).toList();
        assertEquals(List.of("transaction", "statementLockWait", "select", "statementLockWaitRestore",
                "transaction"), methods);
        assertEquals("250", recording.calls.get(1).sql());
    }

    @Test
    void aTimedSelectOutsideATransactionIsRefusedBeforeAnyStatement() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> db.table("users").lock(LockStrength.UPDATE, Duration.ofSeconds(1)).get());

        assertEquals("A lock wait timeout on a statement needs an open transaction", refused.getMessage());
        assertTrue(recording.calls.isEmpty());
    }

    @Test
    void aTimedCursorOutsideATransactionIsRefusedToo() {
        assertThrows(IllegalStateException.class,
                () -> db.table("users").lock(LockStrength.UPDATE, Duration.ofSeconds(1)).cursor());
    }

    @Test
    void theBaseGrammarRefusesATimedLock() {
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                () -> recording.db().table("users").lock(LockStrength.UPDATE, Duration.ofSeconds(1)).toSql());

        assertTrue(refused.getMessage().contains("timed row lock wait"));
    }
}
