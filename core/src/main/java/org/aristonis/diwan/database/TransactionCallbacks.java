package org.aristonis.diwan.database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Holds the {@code afterCommit} and {@code afterRollback} callbacks of one transaction attempt.
 *
 * <p>Each callback is tied to the nesting level that registered it, where 1 is the outermost
 * transaction. The class only keeps and sorts callbacks: it never runs one itself. The connection
 * drains the callbacks once the transaction is fully finished and runs the returned
 * {@link Runnable}, so a callback that throws cannot disturb the transaction bookkeeping.
 *
 * <p>Instances are not thread-safe and not synchronized: one thread owns the transaction and
 * therefore its callbacks, from registration to the run of the drained runners.
 */
final class TransactionCallbacks {

    private static final Runnable NOTHING = new Runnable() {
        @Override
        public void run() {
        }
    };

    private final List<Entry> commit = new ArrayList<Entry>();
    private final List<Entry> rollback = new ArrayList<Entry>();
    /** Rollback callbacks whose level was rolled back and that wait for {@link #drainRolledBack()}. */
    private final List<Entry> rolledBack = new ArrayList<Entry>();

    /** Registers a callback that runs after the outermost commit, unless {@code level} rolls back first. */
    void onCommit(int level, Runnable callback) {
        commit.add(new Entry(level, Objects.requireNonNull(callback, "callback")));
    }

    /** Registers a callback that runs after {@code level} or an outer level rolled back. */
    void onRollback(int level, Runnable callback) {
        rollback.add(new Entry(level, Objects.requireNonNull(callback, "callback")));
    }

    /** Moves the callbacks of {@code level} and deeper to the level above, after a savepoint was released. */
    void releaseLevel(int level) {
        int parent = Math.max(1, level - 1);
        moveDown(commit, level, parent);
        moveDown(rollback, level, parent);
    }

    /**
     * Handles a rollback of {@code level} and every level inside it: drops their commit callbacks
     * and queues their rollback callbacks for {@link #drainRolledBack()}.
     */
    void rollbackLevel(int level) {
        for (Iterator<Entry> it = commit.iterator(); it.hasNext();) {
            if (it.next().level >= level) {
                it.remove();
            }
        }
        List<Entry> batch = new ArrayList<Entry>();
        for (Iterator<Entry> it = rollback.iterator(); it.hasNext();) {
            Entry entry = it.next();
            if (entry.level >= level) {
                batch.add(entry);
                it.remove();
            }
        }
        // Within one rollback the deepest callback runs first, as Laravel does; an earlier
        // savepoint rollback still runs before a later one.
        Collections.reverse(batch);
        rolledBack.addAll(batch);
    }

    /**
     * Takes every commit callback, in registration order, and discards the rollback callbacks that
     * were not queued: the transaction committed, so they can no longer run.
     *
     * @return a runner that runs all of them and throws the first failure with the rest suppressed
     */
    Runnable drainCommitted() {
        Runnable runner = runner(commit);
        commit.clear();
        rollback.clear();
        return runner;
    }

    /**
     * Takes the queued rollback callbacks: rollbacks in the order they happened, and within each
     * one the deepest callback first.
     *
     * @return a runner that runs all of them and throws the first failure with the rest suppressed
     */
    Runnable drainRolledBack() {
        Runnable runner = runner(rolledBack);
        rolledBack.clear();
        return runner;
    }

    private static void moveDown(List<Entry> entries, int fromLevel, int toLevel) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.level >= fromLevel) {
                entries.set(i, new Entry(toLevel, entry.callback));
            }
        }
    }

    private static Runnable runner(List<Entry> entries) {
        if (entries.isEmpty()) {
            return NOTHING;
        }
        final List<Runnable> snapshot = new ArrayList<Runnable>(entries.size());
        for (Entry entry : entries) {
            snapshot.add(entry.callback);
        }
        return new Runnable() {
            @Override
            public void run() {
                Throwable failure = TransactionState.runAll(snapshot);
                if (failure != null) {
                    Sessions.rethrow(failure);
                }
            }
        };
    }

    private static final class Entry {
        final int level;
        final Runnable callback;

        Entry(int level, Runnable callback) {
            this.level = level;
            this.callback = callback;
        }
    }
}
