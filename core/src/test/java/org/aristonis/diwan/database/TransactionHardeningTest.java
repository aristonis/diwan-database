package org.aristonis.diwan.database;

import org.aristonis.diwan.database.exception.QueryException;
import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;

class TransactionHardeningTest {

    private final FakeDriver driver = new FakeDriver();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void endsAScopeLeftOpenInsideANestedTransaction() {
        List<Integer> levels = new java.util.ArrayList<>();

        connection().transaction(tx -> {
            tx.transaction(inner -> {
                inner.beginTransaction().insert("insert into t values (1)");
                return null;
            });
            levels.add(tx.transactionLevel());
            try (TransactionScope again = tx.beginTransaction()) {
                levels.add(again.transactionLevel());
                again.commit();
            }
            return null;
        });

        assertEquals(Arrays.asList(1, 2), levels);
        assertTrue(driver.calls().contains("rollbackToSavepoint diwan_3"));
        assertTrue(driver.calls().contains("commit"));
        assertFalse(driver.calls().contains("rollback"));
    }

    @Test
    void refusesACallbackConnectionUsedAfterItsTransactionEnded() {
        AtomicReference<Connection> leaked = new AtomicReference<>();

        connection().transaction(tx -> {
            leaked.set(tx);
            return null;
        });

        assertThrows(IllegalStateException.class, () -> leaked.get().insert("insert into t values (1)"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void refusesThePretendRecorderAfterItsCallbackAndKeepsTheListFixed() {
        AtomicReference<Connection> leaked = new AtomicReference<>();

        List<LoggedQuery> queries = connection().pretend(p -> {
            leaked.set(p);
            p.statement("select 1");
        });

        assertThrows(IllegalStateException.class, () -> leaked.get().statement("select 2"));
        assertEquals(1, queries.size());
    }

    @Test
    void neverRetriesAFailedCommitBecauseItMayHaveLanded() {
        driver.failsToCommit(failure("40001", DriverFailureKind.CONCURRENCY));
        AtomicInteger runs = new AtomicInteger();

        assertThrows(QueryException.class, () -> connection().transaction(tx -> {
            runs.incrementAndGet();
            return tx.insert("insert into t values (1)");
        }, 3));

        assertEquals(1, runs.get());
    }

    @Test
    void retriesWhenACallbackSwallowedADeadlockOnItsOwnConnection() {
        driver.failsWithConcurrency("update t set a = 1", 1);
        AtomicInteger runs = new AtomicInteger();

        String result = connection().transaction(tx -> {
            runs.incrementAndGet();
            try {
                tx.transaction(inner -> inner.update("update t set a = 1"));
            } catch (QueryException swallowed) {
                // a careless caller hides the deadlock
            }
            return "ok";
        }, 2);

        assertEquals("ok", result);
        assertEquals(2, runs.get());
    }

    @Test
    void doesNotRetryADeadlockThatCameFromSomewhereElse() {
        QueryException foreign = new QueryException("other", "update x set a = 1", Arrays.asList(),
                failure("40001", DriverFailureKind.CONCURRENCY), false);
        AtomicInteger runs = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            runs.incrementAndGet();
            throw new IllegalStateException("wrapped", foreign);
        }, 3));

        assertEquals(1, runs.get());
    }

    @Test
    void givesTheSessionBackAndFreesTheThreadWhenRollbackThrowsAnError() {
        driver.errorsOnRollback(new AssertionError("driver bug"));
        Connection connection = connection();

        assertThrows(Throwable.class, () -> connection.transaction(tx -> {
            throw new IllegalStateException("rule");
        }));

        assertEquals(0, driver.openSessions());
        assertTrue(connection.statement("select 1"));
    }

    @Test
    void freesTheOpeningThreadWhenAScopeIsClosedOnAnotherThread() throws Exception {
        Connection connection = connection();
        TransactionScope scope = connection.beginTransaction();

        Thread closer = new Thread(scope::close);
        closer.start();
        closer.join();

        assertTrue(connection.statement("select 1"));
    }

    @Test
    void escapesEveryLineBreakingAndControlCharacterInMessages() {
        QueryException error = new QueryException("main", "select ' \u001b[31m\\n'", Arrays.asList(),
                failure("42000"), false);

        String message = error.getMessage();

        assertFalse(message.contains(" ") || message.contains("\u001b"));
        assertTrue(message.contains("\\\\n"));
    }
}
