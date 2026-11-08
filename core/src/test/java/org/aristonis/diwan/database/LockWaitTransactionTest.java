package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.LockWaitGuard;
import org.aristonis.diwan.database.event.ConnectionListener;
import org.aristonis.diwan.database.event.TransactionEvent;
import org.aristonis.diwan.database.exception.QueryException;
import org.junit.jupiter.api.Test;

/** The lock wait guard of a transaction: its order, its restore on every exit and its retries. */
class LockWaitTransactionTest {

    private static final Duration WAIT = Duration.ofMillis(250);

    private final RecordingDriver driver = new RecordingDriver();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void theGuardWrapsBeginAndRestoresAfterCommit() {
        String result = connection().transaction(tx -> {
            tx.update("update t set a = 1");
            return "ok";
        }, TransactionOptions.lockWait(WAIT));

        assertEquals("ok", result);
        assertEquals(Arrays.asList("open", "lockWait 250", "beforeBegin", "begin", "afterBegin",
                "update update t set a = 1", "commit", "restore", "close"), driver.log);
    }

    @Test
    void theGuardIsRestoredAfterRollbackAndTheOriginalFailureIsRethrown() {
        IllegalArgumentException boom = new IllegalArgumentException("boom");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> connection().transaction(tx -> {
                    throw boom;
                }, TransactionOptions.lockWait(WAIT)));

        assertSame(boom, thrown);
        assertEquals(Arrays.asList("open", "lockWait 250", "beforeBegin", "begin", "afterBegin", "rollback",
                "restore", "close"), driver.log);
    }

    @Test
    void aRestoreFailureAfterAFailureIsSuppressedOntoIt() {
        driver.restoreFailure = new IllegalStateException("restore broke");
        IllegalArgumentException boom = new IllegalArgumentException("boom");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> connection().transaction(tx -> {
                    throw boom;
                }, TransactionOptions.lockWait(WAIT)));

        assertSame(boom, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(driver.restoreFailure, thrown.getSuppressed()[0]);
        assertTrue(driver.log.contains("discard"));
    }

    @Test
    void aBeginningListenerFailureDropsTheSessionWhenTheRestoreFailsToo() {
        driver.restoreFailure = new IllegalStateException("restore broke");
        IllegalArgumentException refused = new IllegalArgumentException("refused");
        Connection db = refusingConnection(refused);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> db.transaction(tx -> tx.update("update t set a = 1"), TransactionOptions.lockWait(WAIT)));

        assertSame(refused, thrown);
        assertSame(driver.restoreFailure, thrown.getSuppressed()[0]);
        assertTrue(driver.log.contains("discard"));
        assertFalse(driver.log.contains("close"));
    }

    @Test
    void aBeginningListenerFailureClosesTheSessionWhenTheRestoreWorks() {
        IllegalArgumentException refused = new IllegalArgumentException("refused");
        Connection db = refusingConnection(refused);

        assertThrows(IllegalArgumentException.class,
                () -> db.transaction(tx -> tx.update("update t set a = 1"), TransactionOptions.lockWait(WAIT)));

        assertEquals(Arrays.asList("open", "lockWait 250", "beforeBegin", "begin", "afterBegin", "rollback",
                "restore", "close"), driver.log);
    }

    private Connection refusingConnection(RuntimeException refusal) {
        return Connection.builder().name("main").driver(driver).listener(new ConnectionListener() {
            @Override
            public void transactionBeginning(TransactionEvent event) {
                throw refusal;
            }
        }).build();
    }

    @Test
    void aRestoreFailureAfterSuccessIsThrown() {
        driver.restoreFailure = new IllegalStateException("restore broke");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> tx.update("update t set a = 1"),
                        TransactionOptions.lockWait(WAIT)));

        assertSame(driver.restoreFailure, thrown);
        assertTrue(driver.log.contains("commit"));
        assertFalse(driver.log.contains("rollback"));
    }

    @Test
    void aFailedAfterBeginRestoresTheWaitOnce() {
        driver.afterBeginFailure = new IllegalStateException("set failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> 1, TransactionOptions.lockWait(WAIT)));

        assertSame(driver.afterBeginFailure, thrown);
        assertEquals(1, count("restore"));
        assertFalse(driver.log.contains("commit"));
    }

    @Test
    void everyRetryAppliesANewGuard() {
        driver.concurrencyFailures = 1;

        int changed = connection().transaction(tx -> tx.update("update t set a = 1"),
                TransactionOptions.lockWait(WAIT).withAttempts(3));

        assertEquals(1, changed);
        assertEquals(2, count("lockWait 250"));
        assertEquals(2, count("beforeBegin"));
        assertEquals(2, count("restore"));
        assertEquals(Arrays.asList("open", "lockWait 250", "beforeBegin", "begin", "afterBegin",
                "update update t set a = 1", "rollback", "restore", "close"), driver.log.subList(0, 9));
    }

    @Test
    void aLockTimeoutThatKeepsFailingSurfacesAsAConcurrencyFailure() {
        driver.concurrencyFailures = 5;

        QueryException thrown = assertThrows(QueryException.class,
                () -> connection().transaction(tx -> tx.update("update t set a = 1"),
                        TransactionOptions.lockWait(WAIT).withAttempts(2)));

        assertTrue(thrown.isConcurrencyFailure());
        assertEquals(2, count("restore"));
    }

    @Test
    void aNestedTransactionWithALockWaitIsRefusedBeforeAnySql() {
        List<String> beforeNested = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            beforeNested.addAll(driver.log);
            return tx.transaction(inner -> inner.update("update t set a = 1"),
                    TransactionOptions.lockWait(WAIT));
        }));

        assertEquals(0, count("lockWait"));
        assertFalse(driver.log.contains("savepoint diwan_2"));
        assertEquals(beforeNested, driver.log.subList(0, beforeNested.size()));
        assertFalse(driver.log.stream().anyMatch(call -> call.startsWith("update")));
    }

    @Test
    void aNestedTransactionWithOnlyAttemptsStillWorks() {
        int changed = connection().transaction(tx -> tx.transaction(inner -> inner.update("update t set a = 1"),
                TransactionOptions.attempts(2)));

        assertEquals(1, changed);
        assertEquals(0, count("lockWait"));
    }

    @Test
    void attemptsAsAnIntBehavesAsBefore() {
        driver.concurrencyFailures = 1;

        int changed = connection().transaction(tx -> tx.update("update t set a = 1"), 3);

        assertEquals(1, changed);
        assertEquals(0, count("lockWait"));
        assertEquals(0, count("beforeBegin"));
        assertEquals(2, count("begin"));
    }

    @Test
    void attemptsBelowOneAreStillRefusedAsAnInt() {
        assertThrows(IllegalArgumentException.class, () -> connection().transaction(tx -> 1, 0));
    }

    @Test
    void aHeldSessionAppliesTheGuardToo() {
        connection().session(held -> held.transaction(tx -> tx.update("update t set a = 1"),
                TransactionOptions.lockWait(WAIT)));

        assertEquals(Arrays.asList("open", "lockWait 250", "beforeBegin", "begin", "afterBegin",
                "update update t set a = 1", "commit", "restore", "close"), driver.log);
    }

    @Test
    void aDriverWithoutLockWaitRefusesTheOption() {
        driver.isLockWaitSupported = false;

        assertThrows(UnsupportedOperationException.class,
                () -> connection().transaction(tx -> 1, TransactionOptions.lockWait(WAIT)));
        assertFalse(driver.log.contains("begin"));
    }

    @Test
    void pretendIgnoresTheLockWait() {
        List<LoggedQuery> queries = connection().pretend(c -> c.transaction(tx -> tx.update("update t set a = 1"),
                TransactionOptions.lockWait(WAIT)));

        assertEquals(1, queries.size());
        assertEquals(Collections.emptyList(), driver.log);
    }

    @Test
    void exactDecimalDigitsForwardsTheSessionAnswerInsideATransaction() {
        driver.digits = 15;

        int digits = connection().transaction(tx -> tx.exactDecimalDigits().getAsInt());

        assertEquals(15, digits);
    }

    @Test
    void exactDecimalDigitsOfTheRootConnectionComesFromTheDriver() {
        driver.digits = 15;

        assertEquals(15, connection().exactDecimalDigits().getAsInt());
        assertEquals(Collections.emptyList(), driver.log);
    }

    private int count(String call) {
        int n = 0;
        for (String entry : driver.log) {
            if (entry.equals(call)) {
                n++;
            }
        }
        return n;
    }

    /** Driver whose session writes every call into one log and fails where a test asks it to. */
    private static final class RecordingDriver implements Driver {

        final List<String> log = new ArrayList<>();
        RuntimeException restoreFailure;
        RuntimeException afterBeginFailure;
        int concurrencyFailures;
        boolean isLockWaitSupported = true;
        int digits = -1;

        @Override
        public DriverSession open() {
            log.add("open");
            return new Session();
        }

        @Override
        public java.util.OptionalInt exactDecimalDigits() {
            return digits < 0 ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(digits);
        }

        @Override
        public DriverInfo info() {
            return new DriverInfo("Recording", "1", EnumSet.of(DriverCapability.SAVEPOINT_RELEASE));
        }

        private final class Session implements DriverSession {

            @Override
            public List<Map<String, Object>> query(String sql, List<Object> bindings) {
                log.add("query " + sql);
                return Collections.emptyList();
            }

            @Override
            public int update(String sql, List<Object> bindings) {
                log.add("update " + sql);
                if (concurrencyFailures > 0) {
                    concurrencyFailures--;
                    throw FakeDriver.failure("40001", DriverFailureKind.CONCURRENCY);
                }
                return 1;
            }

            @Override
            public boolean execute(String sql, List<Object> bindings) {
                log.add("execute " + sql);
                return false;
            }

            @Override
            public void executeUnprepared(String sql) {
                log.add("unprepared " + sql);
            }

            @Override
            public boolean inExternalTransaction() {
                return false;
            }

            @Override
            public void begin() {
                log.add("begin");
            }

            @Override
            public void commit() {
                log.add("commit");
            }

            @Override
            public void rollback() {
                log.add("rollback");
            }

            @Override
            public void savepoint(String name) {
                log.add("savepoint " + name);
            }

            @Override
            public void rollbackToSavepoint(String name) {
                log.add("rollbackTo " + name);
            }

            @Override
            public void releaseSavepoint(String name) {
                log.add("release " + name);
            }

            @Override
            public void close() {
                log.add("close");
            }

            @Override
            public void discard() {
                log.add("discard");
            }

            @Override
            public LockWaitGuard lockWait(long millis) {
                if (!isLockWaitSupported) {
                    return DriverSession.super.lockWait(millis);
                }
                log.add("lockWait " + millis);
                return new LockWaitGuard() {
                    @Override
                    public void beforeBegin() {
                        log.add("beforeBegin");
                    }

                    @Override
                    public void afterBegin() {
                        log.add("afterBegin");
                        if (afterBeginFailure != null) {
                            throw afterBeginFailure;
                        }
                    }

                    @Override
                    public void restore() {
                        log.add("restore");
                        if (restoreFailure != null) {
                            throw restoreFailure;
                        }
                    }
                };
            }

            @Override
            public java.util.OptionalInt exactDecimalDigits() {
                return digits < 0 ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(digits);
            }
        }
    }
}
