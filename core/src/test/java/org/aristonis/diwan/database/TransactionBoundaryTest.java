package org.aristonis.diwan.database;

import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;

/** Edges of transactions on the fake driver: ending twice, serialization failures, callback order, leaks. */
class TransactionBoundaryTest {

    private final FakeDriver driver = new FakeDriver();
    private final RecordingListener listener = new RecordingListener();

    private Connection connection(FakeDriver fake, RecordingListener recorder) {
        return Connection.builder().name("main").driver(fake).listener(recorder).build();
    }

    private Connection connection() {
        return connection(driver, listener);
    }

    @Test
    void endingARolledBackScopeAgainReportsNoSecondRolledBackEvent() {
        TransactionScope tx = connection().beginTransaction();
        tx.close();

        tx.close();

        assertEquals(Arrays.asList("beginning:1", "rolledBack:1"), listener.phases());
    }

    @Test
    void closingACommittedScopeReportsNoRolledBackEvent() {
        TransactionScope tx = connection().beginTransaction();
        tx.commit();

        tx.close();

        assertEquals(Arrays.asList("beginning:1", "committing:1", "committed:1"), listener.phases());
    }

    @Test
    void aSerializationFailureIsRetriedAndTheSecondAttemptCommits() {
        driver.failsWithConcurrency("update t set a = 1", 1);
        AtomicInteger runs = new AtomicInteger();

        int changed = connection().transaction(tx -> {
            runs.incrementAndGet();
            return tx.update("update t set a = 1");
        }, 2);

        assertEquals(0, changed);
        assertEquals(2, runs.get());
        assertEquals("40001", listener.failed().get(0).exception().sqlState().orElse(""));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void rollbackCallbacksRunDeepestFirstAcrossCommittedAndOpenBranches() {
        List<String> ran = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            tx.afterRollback(() -> ran.add("outer-1"));
            tx.transaction(committed -> {
                committed.afterRollback(() -> ran.add("committed-2"));
                return null;
            });
            tx.transaction(open -> open.transaction(deeper -> {
                deeper.afterRollback(() -> ran.add("open-3"));
                return null;
            }));
            throw new IllegalStateException("undo everything");
        }));

        assertEquals(Arrays.asList("open-3", "committed-2", "outer-1"), ran);
    }

    @Test
    void callbacksOfOneConnectionDoNotRunWhenAnotherConnectionCommits() {
        FakeDriver otherDriver = new FakeDriver();
        Connection first = connection();
        Connection second = connection(otherDriver, new RecordingListener());
        List<String> ran = new ArrayList<>();

        first.transaction(a -> {
            a.afterCommit(() -> ran.add("first"));
            second.transaction(b -> {
                b.afterCommit(() -> ran.add("second"));
                return null;
            });
            assertEquals(Arrays.asList("second"), new ArrayList<>(ran));
            return null;
        });

        assertEquals(Arrays.asList("second", "first"), ran);
    }

    @Test
    void rollbackCallbacksOfOneConnectionDoNotRunWhenAnotherConnectionRollsBack() {
        Connection first = connection();
        Connection second = connection(new FakeDriver(), new RecordingListener());
        List<String> ran = new ArrayList<>();

        first.transaction(a -> {
            a.afterRollback(() -> ran.add("first"));
            assertThrows(IllegalStateException.class, () -> second.transaction(b -> {
                b.afterRollback(() -> ran.add("second"));
                throw new IllegalStateException("undo second");
            }));
            return null;
        });

        assertEquals(Arrays.asList("second"), ran);
    }

    @Test
    void noSessionStaysOpenAfterManyFailedCommitsRollbacksAndListenerErrors() {
        int rounds = 25;
        for (int i = 0; i < rounds; i++) {
            FakeDriver failing = new FakeDriver().failsToCommit(failure("40001", DriverFailureKind.CONCURRENCY));
            assertThrows(QueryException.class,
                    () -> connection(failing, new RecordingListener()).transaction(tx -> tx.insert("insert into t values (1)")));
            assertEquals(0, failing.openSessions());

            FakeDriver rollbackFails = new FakeDriver().failsToRollback(failure("08006"));
            assertThrows(IllegalStateException.class, () -> connection(rollbackFails, new RecordingListener())
                    .transaction(tx -> {
                        throw new IllegalStateException("rule");
                    }));
            assertEquals(0, rollbackFails.openSessions());

            FakeDriver listenerFails = new FakeDriver();
            Connection throwing = Connection.builder().name("main").driver(listenerFails)
                    .listener(new ConnectionListener() {
                        @Override
                        public void transactionCommitting(TransactionEvent event) {
                            throw new IllegalStateException("listener");
                        }
                    }).build();
            assertThrows(RuntimeException.class, () -> throwing.transaction(tx -> tx.insert("insert into t values (1)")));
            assertEquals(0, listenerFails.openSessions());
        }
    }
}
