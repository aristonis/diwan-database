package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionCallbacksTest {

    private final List<String> ran = new ArrayList<>();

    private Runnable mark(String name) {
        return () -> ran.add(name);
    }

    @Test
    void drainCommittedRunsCommitCallbacksInRegistrationOrder() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onCommit(1, mark("a"));
        callbacks.onCommit(2, mark("b"));
        callbacks.onCommit(1, mark("c"));

        callbacks.drainCommitted().run();

        assertEquals(Arrays.asList("a", "b", "c"), ran);
    }

    @Test
    void drainCommittedDiscardsRollbackCallbacksAndEmptiesItself() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onCommit(1, mark("commit"));
        callbacks.onRollback(1, mark("rollback"));

        callbacks.drainCommitted().run();
        callbacks.drainCommitted().run();
        callbacks.drainRolledBack().run();

        assertEquals(Arrays.asList("commit"), ran);
    }

    @Test
    void rollbackLevelDropsCommitCallbacksOfThatLevelAndDeeperOnly() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onCommit(1, mark("outer"));
        callbacks.onCommit(2, mark("savepoint"));
        callbacks.onCommit(3, mark("deeper"));

        callbacks.rollbackLevel(2);
        callbacks.drainCommitted().run();

        assertEquals(Arrays.asList("outer"), ran);
    }

    @Test
    void rollbackLevelQueuesRollbackCallbacksOfThatLevelAndDeeperForDrain() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onRollback(1, mark("outer"));
        callbacks.onRollback(2, mark("savepoint"));
        callbacks.onRollback(3, mark("deeper"));

        callbacks.rollbackLevel(2);
        assertEquals(0, ran.size());
        callbacks.drainRolledBack().run();

        assertEquals(Arrays.asList("deeper", "savepoint"), ran);
    }

    @Test
    void outerRollbackStillRunsCallbacksQueuedByAnEarlierSavepointRollback() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onRollback(2, mark("savepoint"));
        callbacks.onRollback(1, mark("outer"));

        callbacks.rollbackLevel(2);
        callbacks.rollbackLevel(1);
        callbacks.drainRolledBack().run();

        assertEquals(Arrays.asList("savepoint", "outer"), ran);
    }

    @Test
    void drainRolledBackRunsEachCallbackOnlyOnce() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onRollback(1, mark("a"));
        callbacks.rollbackLevel(1);

        callbacks.drainRolledBack().run();
        callbacks.drainRolledBack().run();

        assertEquals(Arrays.asList("a"), ran);
    }

    @Test
    void releaseLevelMovesCallbacksToTheParentSoTheyFollowItsFate() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onCommit(3, mark("commit"));
        callbacks.onRollback(3, mark("rollback"));

        callbacks.releaseLevel(3);
        callbacks.rollbackLevel(3);
        callbacks.drainCommitted().run();
        callbacks.drainRolledBack().run();

        assertEquals(Arrays.asList("commit"), ran);
    }

    @Test
    void releasedCallbacksAreDroppedWhenTheParentRollsBack() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onCommit(3, mark("commit"));
        callbacks.onRollback(3, mark("rollback"));

        callbacks.releaseLevel(3);
        callbacks.rollbackLevel(2);
        callbacks.drainCommitted().run();
        callbacks.drainRolledBack().run();

        assertEquals(Arrays.asList("rollback"), ran);
    }

    @Test
    void releaseOfTheOutermostLevelKeepsCallbacksAtLevelOne() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onCommit(1, mark("a"));

        callbacks.releaseLevel(1);
        callbacks.rollbackLevel(2);
        callbacks.drainCommitted().run();

        assertEquals(Arrays.asList("a"), ran);
    }

    @Test
    void failuresRunEveryCallbackThenThrowTheFirstWithTheRestSuppressed() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        callbacks.onCommit(1, () -> {
            throw first;
        });
        callbacks.onCommit(1, mark("middle"));
        callbacks.onCommit(1, () -> {
            throw second;
        });

        Runnable runner = callbacks.drainCommitted();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, runner::run);

        assertSame(first, thrown);
        assertEquals(Arrays.asList("middle"), ran);
        assertEquals(Arrays.asList(second), Arrays.asList(thrown.getSuppressed()));
    }

    @Test
    void rollbackCallbackFailuresFollowTheSameRule() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        callbacks.onRollback(1, () -> {
            throw new IllegalStateException("boom");
        });
        callbacks.onRollback(1, mark("after"));
        callbacks.rollbackLevel(1);

        assertThrows(IllegalStateException.class, callbacks.drainRolledBack()::run);

        assertEquals(Arrays.asList("after"), ran);
    }

    @Test
    void sameExceptionThrownTwiceIsNotSuppressedOnItself() {
        TransactionCallbacks callbacks = new TransactionCallbacks();
        IllegalStateException shared = new IllegalStateException("shared");
        callbacks.onCommit(1, () -> {
            throw shared;
        });
        callbacks.onCommit(1, () -> {
            throw shared;
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, callbacks.drainCommitted()::run);

        assertSame(shared, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void drainOfNothingReturnsARunnerThatDoesNothing() {
        TransactionCallbacks callbacks = new TransactionCallbacks();

        callbacks.drainCommitted().run();
        callbacks.drainRolledBack().run();

        assertEquals(0, ran.size());
    }

    @Test
    void refusesNullCallbacks() {
        TransactionCallbacks callbacks = new TransactionCallbacks();

        assertThrows(NullPointerException.class, () -> callbacks.onCommit(1, null));
        assertThrows(NullPointerException.class, () -> callbacks.onRollback(1, null));
    }
}
