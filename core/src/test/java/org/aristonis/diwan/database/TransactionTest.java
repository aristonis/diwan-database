package org.aristonis.diwan.database;

import org.aristonis.diwan.database.exception.QueryException;
import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private final FakeDriver driver = new FakeDriver();
    private final List<Throwable> cleanupFailures = new ArrayList<>();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).onCleanupFailure(cleanupFailures::add).build();
    }

    @Test
    void commitsWhenTheCallbackReturnsAndGivesBackItsResult() {
        String result = connection().transaction(tx -> {
            tx.insert("insert into t values (1)");
            return "done";
        });

        assertEquals("done", result);
        assertEquals(Arrays.asList("open", "begin", "execute insert into t values (1) []", "commit", "close"),
                driver.calls());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void rollsBackAndRethrowsTheSameExceptionWhenTheCallbackThrows() {
        IllegalStateException thrown = new IllegalStateException("business rule");

        IllegalStateException caught = assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            tx.insert("insert into t values (1)");
            throw thrown;
        }));

        assertSame(thrown, caught);
        assertTrue(driver.calls().contains("rollback"));
        assertFalse(driver.calls().contains("commit"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void reportsTheNestingLevel() {
        Connection connection = connection();
        List<Integer> levels = new ArrayList<>();

        connection.transaction(tx -> {
            levels.add(tx.transactionLevel());
            tx.transaction(inner -> levels.add(inner.transactionLevel()));
            levels.add(tx.transactionLevel());
            return null;
        });

        assertEquals(Arrays.asList(1, 2, 1), levels);
        assertEquals(0, connection.transactionLevel());
    }

    @Test
    void runsANestedTransactionInsideASavepointAndReleasesItOnSuccess() {
        connection().transaction(tx -> tx.transaction(inner -> inner.insert("insert into t values (2)")));

        assertEquals(Arrays.asList("open", "begin", "savepoint diwan_2", "execute insert into t values (2) []",
                "releaseSavepoint diwan_2", "commit", "close"), driver.calls());
    }

    @Test
    void keepsTheSavepointWhenTheDatabaseCannotReleaseIt() {
        driver.withoutSavepointRelease();

        connection().transaction(tx -> tx.transaction(inner -> inner.insert("insert into t values (2)")));

        assertFalse(driver.calls().contains("releaseSavepoint diwan_2"));
        assertTrue(driver.calls().contains("commit"));
    }

    @Test
    void undoesOnlyTheInnerWorkWhenTheOuterCallbackHandlesTheFailure() {
        connection().transaction(tx -> {
            tx.insert("insert into t values (1)");
            try {
                tx.transaction(inner -> {
                    throw new IllegalArgumentException("inner rule");
                });
            } catch (IllegalArgumentException expected) {
                // the outer unit decides to carry on
            }
            return null;
        });

        List<String> calls = driver.calls();
        assertTrue(calls.contains("rollbackToSavepoint diwan_2"));
        assertTrue(calls.contains("commit"));
        assertFalse(calls.contains("rollback"));
    }

    @Test
    void undoesEverythingWhenAnInnerFailureIsNotHandled() {
        assertThrows(IllegalArgumentException.class, () -> connection().transaction(tx ->
                tx.transaction(inner -> {
                    throw new IllegalArgumentException("inner rule");
                })));

        assertTrue(driver.calls().contains("rollbackToSavepoint diwan_2"));
        assertTrue(driver.calls().contains("rollback"));
        assertFalse(driver.calls().contains("commit"));
    }

    @Test
    void refusesToCommitAfterTheDatabaseLostTheWholeTransaction() {
        driver.losesSavepoints();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            try {
                tx.transaction(inner -> {
                    throw new IllegalArgumentException("inner rule");
                });
            } catch (RuntimeException ignored) {
                // a careless caller swallows the failure and carries on
            }
            return null;
        }));

        assertTrue(error.getMessage().contains("rollback-only"));
        assertFalse(driver.calls().contains("commit"));
        assertTrue(driver.calls().contains("rollback"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void refusesFurtherStatementsOnceTheTransactionIsRollbackOnly() {
        driver.failsOn("update t set a = 1", failure("40001", DriverFailureKind.CONCURRENCY));
        AtomicReference<RuntimeException> later = new AtomicReference<>();

        assertThrows(RuntimeException.class, () -> connection().transaction(tx -> {
            try {
                tx.transaction(inner -> inner.update("update t set a = 1"));
            } catch (QueryException ignored) {
                // swallowed on purpose
            }
            later.set(assertThrows(IllegalStateException.class, () -> tx.insert("insert into t values (3)")));
            return null;
        }));

        assertFalse(driver.calls().contains("execute insert into t values (3) []"));
    }

    @Test
    void retriesTheWholeCallbackOnAConcurrencyFailure() {
        driver.failsWithConcurrency("update t set a = 1", 2);
        AtomicInteger runs = new AtomicInteger();

        int changed = connection().transaction(tx -> {
            runs.incrementAndGet();
            return tx.update("update t set a = 1");
        }, 3);

        assertEquals(0, changed);
        assertEquals(3, runs.get());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        driver.failsWithConcurrency("update t set a = 1", 5);
        AtomicInteger runs = new AtomicInteger();

        QueryException error = assertThrows(QueryException.class, () -> connection().transaction(tx -> {
            runs.incrementAndGet();
            return tx.update("update t set a = 1");
        }, 3));

        assertTrue(error.isConcurrencyFailure());
        assertEquals(3, runs.get());
    }

    @Test
    void doesNotRetryOtherFailures() {
        driver.failsOn("update t set a = 1", failure("42P01"));
        AtomicInteger runs = new AtomicInteger();

        assertThrows(QueryException.class, () -> connection().transaction(tx -> {
            runs.incrementAndGet();
            return tx.update("update t set a = 1");
        }, 3));

        assertEquals(1, runs.get());
    }

    @Test
    void refusesFewerThanOneAttempt() {
        assertThrows(IllegalArgumentException.class, () -> connection().transaction(tx -> null, 0));
        assertEquals(0, driver.sessionsOpened());
    }

    @Test
    void worksInsideAnExternalTransactionThroughASavepointOnly() {
        driver.insideExternalTransaction();

        connection().transaction(tx -> tx.insert("insert into t values (1)"));

        assertEquals(Arrays.asList("open", "savepoint diwan_1", "execute insert into t values (1) []",
                "releaseSavepoint diwan_1", "close"), driver.calls());
    }

    @Test
    void undoesOnlyItsOwnPartInsideAnExternalTransaction() {
        driver.insideExternalTransaction();

        assertThrows(IllegalArgumentException.class, () -> connection().transaction(tx -> {
            throw new IllegalArgumentException("rule");
        }));

        assertTrue(driver.calls().contains("rollbackToSavepoint diwan_1"));
        assertFalse(driver.calls().contains("rollback"));
        assertFalse(driver.calls().contains("begin"));
    }

    @Test
    void neverRetriesInsideAnExternalTransaction() {
        driver.insideExternalTransaction().failsWithConcurrency("update t set a = 1", 1);
        AtomicInteger runs = new AtomicInteger();

        assertThrows(QueryException.class, () -> connection().transaction(tx -> {
            runs.incrementAndGet();
            return tx.update("update t set a = 1");
        }, 3));

        assertEquals(1, runs.get());
    }

    @Test
    void refusesARootCallFromInsideItsOwnTransaction() {
        Connection connection = connection();

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                connection.transaction(tx -> connection.insert("insert into audit values (1)")));

        assertTrue(error.getMessage().contains("transaction"));
        assertEquals(1, driver.sessionsOpened());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void allowsARootCallInsideATransactionWhenStrictModeIsOff() {
        Connection connection = Connection.builder().name("main").driver(driver).strictTransactions(false).build();

        connection.transaction(tx -> connection.insert("insert into audit values (1)"));

        assertEquals(2, driver.sessionsOpened());
    }

    @Test
    void allowsOtherThreadsToUseTheRootDuringATransaction() throws Exception {
        Connection connection = connection();
        AtomicBoolean ran = new AtomicBoolean();

        connection.transaction(tx -> {
            Thread other = new Thread(() -> ran.set(connection.insert("insert into t values (9)")));
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertTrue(ran.get());
    }

    @Test
    void attachesARollbackFailureToTheOriginalFailure() {
        IllegalArgumentException original = new IllegalArgumentException("rule");
        driver.failsToRollback(failure("08006"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> connection().transaction(tx -> {
                    throw original;
                }));

        assertSame(original, error);
        assertEquals(1, error.getSuppressed().length);
        assertEquals(0, driver.openSessions());
    }

    @Test
    void reportsAFailedCommitAndStillGivesTheSessionBack() {
        driver.failsToCommit(failure("40001", DriverFailureKind.CONCURRENCY));

        assertThrows(QueryException.class, () -> connection().transaction(tx -> tx.insert("insert into t values (1)")));

        assertEquals(0, driver.openSessions());
    }

    @Test
    void keepsTheResultWhenGivingTheSessionBackFailsAfterCommit() {
        RuntimeException closeProblem = new IllegalStateException("network gone");
        driver.failsToClose(closeProblem);

        assertEquals("ok", connection().transaction(tx -> "ok"));
        assertEquals(Arrays.<Throwable>asList(closeProblem), cleanupFailures);
    }

    @Test
    void refusesANullCallback() {
        assertThrows(NullPointerException.class, () -> connection().transaction(null));
    }

    @Test
    void givesEverySavepointInOneSessionItsOwnName() {
        connection().transaction(tx -> {
            try {
                tx.transaction(inner -> {
                    throw new IllegalArgumentException("first inner fails");
                });
            } catch (IllegalArgumentException expected) {
                // carry on
            }
            tx.transaction(inner -> inner.insert("insert into t values (2)"));
            return null;
        });

        List<String> calls = driver.calls();
        assertTrue(calls.contains("savepoint diwan_2"));
        assertTrue(calls.contains("savepoint diwan_2_1"));
        assertTrue(calls.contains("commit"));
    }
}
