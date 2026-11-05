package org.aristonis.diwan.database;

import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The transaction phases a listener hears, for the callback path, the scope path and retries. */
class TransactionEventsTest {

    private final FakeDriver driver = new FakeDriver();
    private final RecordingListener listener = new RecordingListener();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).listener(listener).build();
    }

    @Test
    void reportsBeginningThenCommittingThenCommittedForOneTransaction() {
        connection().transaction(tx -> tx.insert("insert into t values (1)"));

        assertEquals(Arrays.asList("beginning:1", "committing:1", "committed:1"), listener.phases());
    }

    @Test
    void carriesTheConnectionNameAndMarksTheOutermostLevelAsNotASavepoint() {
        connection().transaction(tx -> tx.insert("insert into t values (1)"));

        TransactionEvent event = listener.transactionEvents().get(0);
        assertEquals("main", event.connectionName());
        assertEquals(1, event.level());
        assertFalse(event.isSavepoint());
    }

    @Test
    void reportsNestedTransactionsWithTheirLevelsAndMarksTheInnerOneAsASavepoint() {
        connection().transaction(tx -> tx.transaction(inner -> inner.insert("insert into t values (1)")));

        assertEquals(Arrays.asList("beginning:1", "beginning:2", "committing:2", "committed:2", "committing:1",
                "committed:1"), listener.phases());
        assertTrue(listener.transactionEvents().get(1).isSavepoint());
    }

    @Test
    void reportsARolledBackSavepointWhileTheOuterTransactionCommits() {
        connection().transaction(tx -> {
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> {
                throw new IllegalStateException("inner rule");
            }));
            return null;
        });

        assertEquals(Arrays.asList("beginning:1", "beginning:2", "rolledBack:2", "committing:1", "committed:1"),
                listener.phases());
    }

    @Test
    void reportsRolledBackWhenTheCallbackThrows() {
        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            throw new IllegalStateException("rule");
        }));

        assertEquals(Arrays.asList("beginning:1", "rolledBack:1"), listener.phases());
    }

    @Test
    void rollsBackAndRethrowsTheListenerFailureWhenACommittingListenerThrows() {
        IllegalStateException veto = new IllegalStateException("not now");
        Connection db = Connection.builder().name("main").driver(driver).listener(new ConnectionListener() {
            @Override
            public void transactionCommitting(TransactionEvent event) {
                throw veto;
            }
        }).build();

        IllegalStateException caught = assertThrows(IllegalStateException.class,
                () -> db.transaction(tx -> tx.insert("insert into t values (1)")));

        assertSame(veto, caught);
        assertTrue(driver.calls().contains("rollback"), driver.calls().toString());
        assertFalse(driver.calls().contains("commit"), driver.calls().toString());
        assertEquals(0, driver.openSessions());
        assertEquals(0, db.transactionLevel());
    }

    @Test
    void doesNotReportCommittedWhenACommittingListenerThrows() {
        Connection db = Connection.builder().name("main").driver(driver).listener(listener)
                .listener(new ConnectionListener() {
                    @Override
                    public void transactionCommitting(TransactionEvent event) {
                        throw new IllegalStateException("not now");
                    }
                }).build();

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> tx.insert("insert into t values (1)")));

        assertFalse(listener.phases().contains("committed:1"), listener.phases().toString());
    }

    @Test
    void reportsTheScopePathCommit() {
        try (TransactionScope tx = connection().beginTransaction()) {
            tx.insert("insert into t values (1)");
            tx.commit();
        }

        assertEquals(Arrays.asList("beginning:1", "committing:1", "committed:1"), listener.phases());
    }

    @Test
    void reportsTheScopePathRollbackWhenClosedWithoutCommit() {
        try (TransactionScope tx = connection().beginTransaction()) {
            tx.insert("insert into t values (1)");
        }

        assertEquals(Arrays.asList("beginning:1", "rolledBack:1"), listener.phases());
    }

    @Test
    void reportsASavepointInsideAScope() {
        try (TransactionScope tx = connection().beginTransaction()) {
            tx.transaction(inner -> inner.insert("insert into t values (1)"));
            tx.commit();
        }

        assertEquals(Arrays.asList("beginning:1", "beginning:2", "committing:2", "committed:2", "committing:1",
                "committed:1"), listener.phases());
    }

    @Test
    void reportsEachRetryAttemptAsItsOwnTransaction() {
        driver.failsWithConcurrency("update t set a = 1", 1);
        AtomicInteger runs = new AtomicInteger();

        connection().transaction(tx -> {
            runs.incrementAndGet();
            return tx.update("update t set a = 1");
        }, 3);

        assertEquals(2, runs.get());
        assertEquals(Arrays.asList("beginning:1", "rolledBack:1", "beginning:1", "committing:1", "committed:1"),
                listener.phases());
    }

    @Test
    void reportsNoTransactionEventsForPlainStatements() {
        connection().insert("insert into t values (1)");

        assertTrue(listener.phases().isEmpty());
    }

    @Test
    void reportsNothingWhenBeginFails() {
        driver.failsToBegin(failure("08006"));

        assertThrows(QueryException.class, () -> connection().transaction(tx -> tx.insert("insert into t values (1)")));

        assertFalse(listener.phases().contains("committed:1"));
        assertFalse(listener.phases().contains("beginning:1"), listener.phases().toString());
    }
}
