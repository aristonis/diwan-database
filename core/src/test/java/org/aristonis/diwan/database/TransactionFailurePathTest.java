package org.aristonis.diwan.database;

import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
