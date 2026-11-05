package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Callbacks that wait for the outcome of a transaction: when they run, when they are dropped, and how they fail. */
class AfterCommitTest {

    private final FakeDriver driver = new FakeDriver();
    private final List<String> ran = new ArrayList<>();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void runsAtOnceOutsideATransaction() {
        connection().afterCommit(() -> ran.add("now"));

        assertEquals(Arrays.asList("now"), ran);
    }

    @Test
    void afterRollbackOutsideATransactionNeverRuns() {
        connection().afterRollback(() -> ran.add("never"));

        assertTrue(ran.isEmpty());
    }

    @Test
    void waitsForTheCommitInsideATransaction() {
        Connection db = connection();

        db.transaction(tx -> {
            tx.afterCommit(() -> ran.add("done"));
            assertTrue(ran.isEmpty());
            return null;
        });

        assertEquals(Arrays.asList("done"), ran);
    }

    @Test
    void runsNestedCallbacksOnceAfterTheOuterCommitInRegistrationOrder() {
        connection().transaction(tx -> {
            tx.afterCommit(() -> ran.add("outer-1"));
            tx.transaction(inner -> {
                inner.afterCommit(() -> ran.add("inner"));
                return null;
            });
            assertTrue(ran.isEmpty(), "inner release must not run callbacks");
            tx.afterCommit(() -> ran.add("outer-2"));
            return null;
        });

        assertEquals(Arrays.asList("outer-1", "inner", "outer-2"), ran);
    }

    @Test
    void runsCallbacksOnlyAfterTheSessionIsGivenBackAndTheTransactionIsOver() {
        Connection db = connection();
        List<Object> seen = new ArrayList<>();

        db.transaction(tx -> {
            tx.afterCommit(() -> {
                seen.add(driver.openSessions());
                seen.add(db.transactionLevel());
                seen.add(driver.calls().contains("commit"));
            });
            return null;
        });

        assertEquals(Arrays.<Object>asList(0, 0, true), seen);
    }

    @Test
    void dropsCallbacksOfASavepointThatRollsBackButKeepsTheOuterOnes() {
        connection().transaction(tx -> {
            tx.afterCommit(() -> ran.add("outer-1"));
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> {
                inner.afterCommit(() -> ran.add("inner"));
                throw new IllegalStateException("inner rule");
            }));
            tx.afterCommit(() -> ran.add("outer-2"));
            return null;
        });

        assertEquals(Arrays.asList("outer-1", "outer-2"), ran);
    }

    @Test
    void dropsEveryCommitCallbackWhenTheOuterTransactionRollsBack() {
        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            tx.afterCommit(() -> ran.add("commit"));
            tx.transaction(inner -> {
                inner.afterCommit(() -> ran.add("inner-commit"));
                return null;
            });
            throw new IllegalStateException("rule");
        }));

        assertTrue(ran.isEmpty(), ran.toString());
    }

    @Test
    void keepsRunningTheOtherCallbacksWhenOneThrowsAndRethrowsTheFirstFailure() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");

        RuntimeException caught = assertThrows(RuntimeException.class, () -> connection().transaction(tx -> {
            tx.afterCommit(() -> {
                ran.add("a");
                throw first;
            });
            tx.afterCommit(() -> ran.add("b"));
            tx.afterCommit(() -> {
                ran.add("c");
                throw second;
            });
            return null;
        }));

        assertSame(first, caught);
        assertTrue(Arrays.asList(caught.getSuppressed()).contains(second));
        assertEquals(Arrays.asList("a", "b", "c"), ran);
    }

    @Test
    void aThrowingCallbackLeavesTheTransactionCommittedAndNeverRollsItBack() {
        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            tx.insert("insert into t values (1)");
            tx.afterCommit(() -> {
                throw new IllegalStateException("callback");
            });
            return null;
        }));

        assertTrue(driver.calls().contains("commit"), driver.calls().toString());
        assertFalse(driver.calls().contains("rollback"), driver.calls().toString());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void runsAfterRollbackCallbacksWhenTheTransactionRollsBack() {
        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            tx.afterRollback(() -> ran.add("undone"));
            throw new IllegalStateException("rule");
        }));

        assertEquals(Arrays.asList("undone"), ran);
    }

    @Test
    void doesNotRunAfterRollbackCallbacksWhenTheTransactionCommits() {
        connection().transaction(tx -> {
            tx.afterRollback(() -> ran.add("undone"));
            return null;
        });

        assertTrue(ran.isEmpty());
    }

    @Test
    void runsASavepointsAfterRollbackCallbackWhenOnlyThatSavepointRollsBack() {
        connection().transaction(tx -> {
            tx.afterRollback(() -> ran.add("outer"));
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> {
                inner.afterRollback(() -> ran.add("inner"));
                throw new IllegalStateException("inner rule");
            }));
            return null;
        });

        assertEquals(Arrays.asList("inner"), ran);
    }

    @Test
    void runsAReleasedSavepointsAfterRollbackCallbackWhenTheOuterTransactionRollsBack() {
        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            tx.afterRollback(() -> ran.add("outer"));
            tx.transaction(inner -> {
                inner.afterRollback(() -> ran.add("inner"));
                return null;
            });
            throw new IllegalStateException("rule");
        }));

        assertEquals(2, ran.size());
        assertTrue(ran.containsAll(Arrays.asList("outer", "inner")), ran.toString());
    }

    @Test
    void aCallbackMayRunSqlOnTheRootEvenWithStrictTransactionsOn() {
        Connection db = connection();

        db.transaction(tx -> {
            tx.afterCommit(() -> db.insert("insert into audit values (1)"));
            return null;
        });

        assertTrue(driver.calls().contains("execute insert into audit values (1) []"), driver.calls().toString());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aScopeRunsItsCommitCallbacksOnceItIsClosed() {
        try (TransactionScope tx = connection().beginTransaction()) {
            tx.afterCommit(() -> ran.add("committed"));
            tx.commit();
        }

        assertEquals(Arrays.asList("committed"), ran);
    }

    @Test
    void aScopeClosedWithoutCommitRunsRollbackCallbacksAndDropsCommitOnes() {
        try (TransactionScope tx = connection().beginTransaction()) {
            tx.afterCommit(() -> ran.add("commit"));
            tx.afterRollback(() -> ran.add("rollback"));
        }

        assertEquals(Arrays.asList("rollback"), ran);
    }

    @Test
    void keepsCallbacksPerRetryAttempt() {
        driver.failsWithConcurrency("update t set a = 1", 1);
        AtomicInteger attempt = new AtomicInteger();

        connection().transaction(tx -> {
            int number = attempt.incrementAndGet();
            tx.afterCommit(() -> ran.add("commit-" + number));
            tx.afterRollback(() -> ran.add("rollback-" + number));
            return tx.update("update t set a = 1");
        }, 3);

        assertEquals(2, attempt.get());
        assertEquals(Arrays.asList("rollback-1", "commit-2"), ran);
    }

    @Test
    void sessionRetryRunsEachAttemptsCallbacksOnceAfterTheCallEnds() {
        driver.failsWithConcurrency("update t set a = 1", 1);
        AtomicInteger attempt = new AtomicInteger();

        connection().session(session -> {
            session.transaction(tx -> {
                int number = attempt.incrementAndGet();
                ran.add("attempt-" + number);
                tx.afterCommit(() -> ran.add("commit-" + number));
                tx.afterRollback(() -> ran.add("rollback-" + number));
                return tx.update("update t set a = 1");
            }, 3);
            ran.add("call-ended");
            return null;
        });

        assertEquals(2, attempt.get());
        assertEquals(Arrays.asList("attempt-1", "attempt-2", "rollback-1", "commit-2", "call-ended"), ran);
    }
}
