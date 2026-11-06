package org.aristonis.diwan.database;

import org.aristonis.diwan.database.exception.QueryException;
import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;

/** When COMMIT or ROLLBACK itself fails, the events and callbacks still tell the truth. */
class TransactionFailureEventsTest {

    private final FakeDriver driver = new FakeDriver();
    private final RecordingListener listener = new RecordingListener();
    private final List<String> ran = new ArrayList<>();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).listener(listener).build();
    }

    @Test
    void aFailedCommitReportsNoCommittedEvent() {
        driver.failsToCommit(failure("08003"));

        assertThrows(QueryException.class, () -> connection().transaction(tx -> tx.insert("insert into t values (1)")));

        assertFalse(listener.phases().contains("committed:1"), listener.phases().toString());
        assertTrue(listener.phases().contains("beginning:1"));
    }

    @Test
    void aFailedCommitDropsCommitCallbacksAndRunsRollbackCallbacks() {
        driver.failsToCommit(failure("08003"));

        assertThrows(QueryException.class, () -> connection().transaction(tx -> {
            tx.afterCommit(() -> ran.add("commit"));
            tx.afterRollback(() -> ran.add("rollback"));
            return tx.insert("insert into t values (1)");
        }));

        assertEquals(Arrays.asList("rollback"), ran);
    }

    @Test
    void aFailedCommitThatKeepsTheConnectionStillRunsRollbackCallbacks() {
        driver.failsToCommit(failure("23000", DriverFailureKind.CONCURRENCY));

        assertThrows(QueryException.class, () -> connection().transaction(tx -> {
            tx.afterCommit(() -> ran.add("commit"));
            tx.afterRollback(() -> ran.add("rollback"));
            return null;
        }));

        assertEquals(Arrays.asList("rollback"), ran);
        assertFalse(listener.phases().contains("committed:1"));
    }

    @Test
    void aFailedCommitOnAScopeRunsRollbackCallbacksAndReportsNoCommitted() {
        driver.failsToCommit(failure("08003"));

        try (TransactionScope tx = connection().beginTransaction()) {
            tx.afterCommit(() -> ran.add("commit"));
            tx.afterRollback(() -> ran.add("rollback"));
            assertThrows(QueryException.class, tx::commit);
        }

        assertEquals(Arrays.asList("rollback"), ran);
        assertFalse(listener.phases().contains("committed:1"));
    }

    @Test
    void aFailedRollbackStillRunsRollbackCallbacks() {
        driver.failsToRollback(failure("08006"));

        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            tx.afterRollback(() -> ran.add("rollback"));
            tx.afterCommit(() -> ran.add("commit"));
            throw new IllegalStateException("rule");
        }));

        assertEquals(Arrays.asList("rollback"), ran);
    }

    @Test
    void aFailedRollbackReportsNoRolledBackEvent() {
        driver.failsToRollback(failure("08006"));

        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            throw new IllegalStateException("rule");
        }));

        assertFalse(listener.phases().contains("rolledBack:1"), listener.phases().toString());
    }

    @Test
    void aFailedRollbackKeepsTheCallersOriginalException() {
        driver.failsToRollback(failure("08006"));
        IllegalStateException original = new IllegalStateException("rule");

        IllegalStateException caught = assertThrows(IllegalStateException.class,
                () -> connection().transaction(tx -> {
                    throw original;
                }));

        assertEquals(original, caught);
    }
}
