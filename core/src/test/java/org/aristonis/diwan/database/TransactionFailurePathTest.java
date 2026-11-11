package org.aristonis.diwan.database;

import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.exception.QueryException;
import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.Iterator;

import org.junit.jupiter.api.Test;

/** Failures while a transaction starts or ends leave the connection clean or drop the session. */
class TransactionFailurePathTest {

    private final FakeDriver driver = new FakeDriver();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void aFailedBeginLeavesNoTransactionAndNoSession() {
        driver.failsToBegin(failure("08006"));
        Connection db = connection();

        assertThrows(QueryException.class, () -> db.transaction(tx -> tx.insert("insert into t values (1)")));

        assertEquals(0, db.transactionLevel());
        assertEquals(0, driver.openSessions());
        assertFalse(driver.calls().contains("execute insert into t values (1) []"));
    }

    @Test
    void aConnectionLostDuringRollbackIsDiscardedNotReturned() {
        driver.failsToRollback(failure("08006"));

        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            throw new IllegalStateException("undo");
        }));

        assertTrue(driver.calls().contains("discard"), driver.calls().toString());
        assertFalse(driver.calls().contains("close"), driver.calls().toString());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aConnectionLostDuringCommitIsDiscardedNotReturned() {
        driver.failsToCommit(failure("08003"));

        assertThrows(QueryException.class, () -> connection().transaction(tx -> tx.insert("insert into t values (1)")));

        assertTrue(driver.calls().contains("discard"), driver.calls().toString());
        assertEquals(0, driver.openSessions());
    }

    private static final String ABORTED = "aborted by a failed statement";
    private static final String BAD = "insert into bad values (1)";
    private static final String GOOD = "insert into good values (1)";

    private void catchingFailure(Runnable statement) {
        assertThrows(QueryException.class, statement::run);
    }

    @Test
    void aStatementAfterACaughtFailureIsRefusedWithTheFirstFailureAsCause() {
        driver.abortsTransactionsOnError().failsOn(BAD, failure("23505"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> {
                    catchingFailure(() -> tx.insert(BAD));
                    return tx.insert(GOOD);
                }));

        assertTrue(refused.getMessage().contains(ABORTED), refused.getMessage());
        assertTrue(refused.getCause() instanceof QueryException);
        assertFalse(driver.calls().contains("execute " + GOOD + " []"), driver.calls().toString());
        assertTrue(driver.calls().contains("rollback"), driver.calls().toString());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void theRollbackOnlyMessageWinsOverTheAbortedOne() {
        driver.abortsTransactionsOnError().failsWithConcurrency(BAD, 1);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> {
                    catchingFailure(() -> tx.insert(BAD));
                    return tx.insert(GOOD);
                }));

        assertTrue(refused.getMessage().contains("rollback-only"), refused.getMessage());
    }

    @Test
    void aCommitOnAnAbortedTransactionIsRefusedBeforeCommitIsSent() {
        driver.abortsTransactionsOnError().failsOn(BAD, failure("23505"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> {
                    catchingFailure(() -> tx.insert(BAD));
                    return null;
                }));

        assertTrue(refused.getMessage().contains(ABORTED), refused.getMessage());
        assertFalse(driver.calls().contains("commit"), driver.calls().toString());
        assertTrue(driver.calls().contains("rollback"), driver.calls().toString());
    }

    @Test
    void aLevelTwoClosureThatCatchesAFailureAndReturnsIsRefusedAtItsRelease() {
        driver.abortsTransactionsOnError().failsOn(BAD, failure("23505"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> tx.transaction(inner -> {
                    catchingFailure(() -> inner.insert(BAD));
                    return null;
                })));

        assertTrue(refused.getMessage().contains(ABORTED), refused.getMessage());
        assertFalse(driver.calls().contains("releaseSavepoint diwan_2"), driver.calls().toString());
        assertTrue(driver.calls().contains("rollbackToSavepoint diwan_2"), driver.calls().toString());
    }

    @Test
    void anAbortAtLevelOneRefusesASavepointAtLevelTwo() {
        driver.abortsTransactionsOnError().failsOn(BAD, failure("23505"));

        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            catchingFailure(() -> tx.insert(BAD));
            return tx.transaction(inner -> inner.insert(GOOD));
        }));

        assertFalse(driver.calls().contains("savepoint diwan_2"), driver.calls().toString());
    }

    @Test
    void aFailedSavepointCommandIsRecordedAtTheLevelAboveIt() {
        driver.abortsTransactionsOnError().failsToSavepoint("diwan_2", failure("42601"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> {
                    catchingFailure(() -> tx.transaction(inner -> inner.insert(GOOD)));
                    return null;
                }));

        assertTrue(refused.getMessage().contains(ABORTED), refused.getMessage());
        assertFalse(driver.calls().contains("commit"), driver.calls().toString());
    }

    @Test
    void anAbortInsideAnExternalTransactionRollsBackToItsSavepoint() {
        driver.abortsTransactionsOnError().insideExternalTransaction().failsOn(BAD, failure("23505"));

        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            catchingFailure(() -> tx.insert(BAD));
            return null;
        }));

        assertTrue(driver.calls().contains("rollbackToSavepoint diwan_1"), driver.calls().toString());
        assertFalse(driver.calls().contains("releaseSavepoint diwan_1"), driver.calls().toString());
    }

    @Test
    void aDeadlockInAClosureIsStillRetried() {
        driver.abortsTransactionsOnError().failsWithConcurrency(GOOD, 1);

        connection().transaction(tx -> tx.insert(GOOD), TransactionOptions.attempts(2));

        assertTrue(driver.calls().contains("commit"), driver.calls().toString());
        assertEquals(2, driver.sessionsOpened());
    }

    @Test
    void anExhaustedDeadlockRetryKeepsTheDriverFailureAsCause() {
        driver.abortsTransactionsOnError().failsWithConcurrency(GOOD, 2);

        QueryException error = assertThrows(QueryException.class,
                () -> connection().transaction(tx -> tx.insert(GOOD), TransactionOptions.attempts(2)));

        assertTrue(error.getCause() instanceof DriverException);
    }

    @Test
    void anAbortCausedByACursorFailureShowsTheAbortMessageBeforeTheCursorGuard() {
        driver.abortsTransactionsOnError().cursorFailsAtRow(0, failure("57014"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> {
                    Iterator<Map<String, Object>> rows = tx.cursor("select 1").iterator();
                    assertThrows(QueryException.class, rows::hasNext);
                    return tx.select("select 2");
                }));

        assertTrue(refused.getMessage().contains(ABORTED), refused.getMessage());
    }

    @Test
    void aClientSideFailureWithoutASqlStateDoesNotAbort() {
        DriverException clientSide = new DriverException("client-side failure",
                Collections.<DriverFailureKind>emptySet(), null);
        driver.abortsTransactionsOnError().failsOn(BAD, clientSide);

        connection().transaction(tx -> {
            catchingFailure(() -> tx.insert(BAD));
            return tx.insert(GOOD);
        });

        assertTrue(driver.calls().contains("commit"), driver.calls().toString());
    }

    @Test
    void aClientSideFailureWithASqlStateDoesNotAbort() {
        DriverException clientSide = new DriverException("client-side failure", "22P02", 0, null, null,
                Collections.<DriverFailureKind>emptySet(), null, null, Collections.<String>emptyList(), false);
        driver.abortsTransactionsOnError().failsOn(BAD, clientSide);

        connection().transaction(tx -> {
            catchingFailure(() -> tx.insert(BAD));
            return tx.insert(GOOD);
        });

        assertTrue(driver.calls().contains("commit"), driver.calls().toString());
    }

    @Test
    void aDriverWithoutTheCapabilityIsNeverMarkedAborted() {
        driver.failsOn(BAD, failure("23505"));

        connection().transaction(tx -> {
            catchingFailure(() -> tx.insert(BAD));
            return tx.insert(GOOD);
        });

        assertTrue(driver.calls().contains("commit"), driver.calls().toString());
    }

    @Test
    void rollingBackToTheSavepointClearsTheAbortSoTheNextStatementAndCommitWork() {
        driver.abortsTransactionsOnError().failsOn(BAD, failure("23505"));

        connection().transaction(tx -> {
            catchingFailure(() -> tx.transaction(inner -> inner.insert(BAD)));
            return tx.insert(GOOD);
        });

        assertTrue(driver.calls().contains("rollbackToSavepoint diwan_2"), driver.calls().toString());
        assertTrue(driver.calls().contains("execute " + GOOD + " []"), driver.calls().toString());
        assertTrue(driver.calls().contains("commit"), driver.calls().toString());
    }
}
