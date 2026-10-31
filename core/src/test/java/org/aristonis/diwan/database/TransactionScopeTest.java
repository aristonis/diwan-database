package org.aristonis.diwan.database;

import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionScopeTest {

    private final FakeDriver driver = new FakeDriver();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void commitsWhenAskedAndGivesTheSessionBackOnClose() {
        try (TransactionScope tx = connection().beginTransaction()) {
            tx.insert("insert into t values (1)");
            tx.commit();
        }

        assertEquals(Arrays.asList("open", "begin", "execute insert into t values (1) []", "commit", "close"),
                driver.calls());
    }

    @Test
    void rollsBackWhenClosedWithoutCommit() {
        try (TransactionScope tx = connection().beginTransaction()) {
            tx.insert("insert into t values (1)");
        }

        assertTrue(driver.calls().contains("rollback"));
        assertFalse(driver.calls().contains("commit"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void closingTwiceDoesNothing() {
        TransactionScope tx = connection().beginTransaction();

        tx.close();
        tx.close();

        assertEquals(Arrays.asList("open", "begin", "rollback", "close"), driver.calls());
    }

    @Test
    void refusesUseAfterItEnded() {
        TransactionScope tx = connection().beginTransaction();
        tx.commit();

        assertThrows(IllegalStateException.class, () -> tx.insert("insert into t values (1)"));
        assertThrows(IllegalStateException.class, tx::commit);
        tx.close();
        assertEquals(0, driver.openSessions());
    }

    @Test
    void rollsBackAndGivesTheSessionBackWhenCommitFailed() {
        driver.failsToCommit(failure("40001"));
        TransactionScope tx = connection().beginTransaction();

        assertThrows(QueryException.class, tx::commit);
        tx.close();

        assertTrue(driver.calls().contains("rollback"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void reportsItsLevelAndNestsThroughSavepoints() {
        try (TransactionScope outer = connection().beginTransaction()) {
            assertEquals(1, outer.transactionLevel());
            try (TransactionScope inner = outer.beginTransaction()) {
                assertEquals(2, inner.transactionLevel());
                inner.insert("insert into t values (2)");
                inner.commit();
            }
            outer.commit();
        }

        List<String> calls = driver.calls();
        assertTrue(calls.contains("savepoint diwan_2"));
        assertTrue(calls.contains("releaseSavepoint diwan_2"));
        assertTrue(calls.contains("commit"));
    }

    @Test
    void endsAnOpenInnerScopeWhenTheOuterOneCloses() {
        TransactionScope outer = connection().beginTransaction();
        TransactionScope inner = outer.beginTransaction();
        inner.insert("insert into t values (2)");

        outer.close();

        List<String> calls = driver.calls();
        assertTrue(calls.indexOf("rollbackToSavepoint diwan_2") < calls.indexOf("rollback"));
        assertThrows(IllegalStateException.class, () -> inner.insert("insert into t values (3)"));
        assertEquals(0, driver.openSessions());
    }
}
