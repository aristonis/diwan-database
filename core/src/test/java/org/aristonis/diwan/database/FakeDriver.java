package org.aristonis.diwan.database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.driver.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.RowCursor;

/** Scriptable driver for core tests: records every call and counts sessions still open. */
final class FakeDriver implements Driver {

    private final Map<String, List<Map<String, Object>>> rows = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final Map<String, DriverException> failures = new HashMap<>();
    private final Map<String, Long> delays = new HashMap<>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger opened = new AtomicInteger();
    private EnumSet<DriverCapability> capabilities = EnumSet.of(DriverCapability.SAVEPOINT_RELEASE);
    private boolean external;
    private boolean losesSavepoints;
    private DriverException openFailure;
    private DriverException commitFailure;
    private DriverException beginFailure;
    private RuntimeException closeFailure;
    private DriverException rollbackFailure;
    private Error rollbackError;
    private int concurrencyFailuresLeft;
    private String concurrencyFailureSql;
    private int maxOpen = Integer.MAX_VALUE;
    private int cursorFailureRow = -1;
    private DriverException cursorFailure;
    private DriverException cursorCloseFailure;
    private volatile boolean isCursorThreadOwned;
    private final AtomicInteger openCursors = new AtomicInteger();

    static Map<String, Object> row(Object... labelsAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            row.put((String) labelsAndValues[i], labelsAndValues[i + 1]);
        }
        return row;
    }

    static DriverException failure(String sqlState, DriverFailureKind... kinds) {
        EnumSet<DriverFailureKind> set = EnumSet.noneOf(DriverFailureKind.class);
        Collections.addAll(set, kinds);
        return new DriverException("driver failure " + sqlState, sqlState, 0, null, set);
    }

    FakeDriver returns(String sql, List<Map<String, Object>> result) {
        rows.put(sql, result);
        return this;
    }

    FakeDriver affects(String sql, int count) {
        counts.put(sql, count);
        return this;
    }

    FakeDriver failsOn(String sql, DriverException failure) {
        failures.put(sql, failure);
        return this;
    }

    /** Makes a statement take at least the given number of milliseconds, to exercise slow-query handlers. */
    FakeDriver takes(String sql, long millis) {
        delays.put(sql, millis);
        return this;
    }

    FakeDriver failsWithConcurrency(String sql, int times) {
        concurrencyFailureSql = sql;
        concurrencyFailuresLeft = times;
        return this;
    }

    FakeDriver failsToOpen(DriverException failure) {
        openFailure = failure;
        return this;
    }

    FakeDriver failsToBegin(DriverException failure) {
        beginFailure = failure;
        return this;
    }

    FakeDriver failsToCommit(DriverException failure) {
        commitFailure = failure;
        return this;
    }

    FakeDriver failsToRollback(DriverException failure) {
        rollbackFailure = failure;
        return this;
    }

    FakeDriver errorsOnRollback(Error error) {
        rollbackError = error;
        return this;
    }

    FakeDriver failsToClose(RuntimeException failure) {
        closeFailure = failure;
        return this;
    }

    FakeDriver insideExternalTransaction() {
        external = true;
        return this;
    }

    FakeDriver withoutSavepointRelease() {
        capabilities = EnumSet.noneOf(DriverCapability.class);
        return this;
    }

    /** Behaves like MySQL after a deadlock: the whole transaction and its savepoints are gone. */
    FakeDriver losesSavepoints() {
        losesSavepoints = true;
        return this;
    }

    /** Behaves like a pool of {@code size} connections: one more open fails at once. */
    FakeDriver poolOf(int size) {
        maxOpen = size;
        return this;
    }

    /** Makes every cursor fail when it reaches the given zero-based row. */
    FakeDriver cursorFailsAtRow(int row, DriverException failure) {
        cursorFailureRow = row;
        cursorFailure = failure;
        return this;
    }

    /** Makes every cursor refuse use from any thread but the one that opened it, as Android's does. */
    FakeDriver cursorsOwnedByTheirThread() {
        isCursorThreadOwned = true;
        return this;
    }

    FakeDriver cursorFailsToClose(DriverException failure) {
        cursorCloseFailure = failure;
        return this;
    }

    int openCursors() {
        return openCursors.get();
    }

    List<String> calls() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    int openSessions() {
        return open.get();
    }

    int sessionsOpened() {
        return opened.get();
    }

    @Override
    public DriverSession open() {
        calls.add("open");
        if (openFailure != null) {
            throw openFailure;
        }
        if (open.get() >= maxOpen) {
            throw failure("08004-pool-exhausted", DriverFailureKind.CONNECTION_LOST);
        }
        open.incrementAndGet();
        opened.incrementAndGet();
        return new Session();
    }

    @Override
    public DriverInfo info() {
        return new DriverInfo("Fake", "1", capabilities);
    }

    private final class Session implements DriverSession {

        private boolean closed;
        private final java.util.Set<String> savepoints = new java.util.HashSet<>();

        private void run(String call, String sql, List<Object> bindings) {
            if (closed) {
                throw new AssertionError("session used after close");
            }
            calls.add(bindings == null ? call + " " + sql : call + " " + sql + " " + bindings);
            Long delay = delays.get(sql);
            if (delay != null) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            DriverException failure = failures.get(sql);
            if (failure != null) {
                throw failure;
            }
            if (sql.equals(concurrencyFailureSql) && concurrencyFailuresLeft > 0) {
                concurrencyFailuresLeft--;
                throw failure("40001", DriverFailureKind.CONCURRENCY);
            }
        }

        @Override
        public List<Map<String, Object>> query(String sql, List<Object> bindings) {
            run("query", sql, bindings);
            List<Map<String, Object>> result = rows.get(sql);
            List<Map<String, Object>> copy = new ArrayList<>();
            if (result != null) {
                for (Map<String, Object> r : result) {
                    copy.add(new LinkedHashMap<>(r));
                }
            }
            return copy;
        }

        @Override
        public RowCursor cursor(String sql, List<Object> bindings) {
            run("cursor", sql, bindings);
            List<Map<String, Object>> result = rows.get(sql);
            openCursors.incrementAndGet();
            return new FakeCursor(result == null ? new ArrayList<Map<String, Object>>() : result);
        }

        @Override
        public int update(String sql, List<Object> bindings) {
            run("update", sql, bindings);
            Integer count = counts.get(sql);
            return count == null ? 0 : count;
        }

        @Override
        public boolean execute(String sql, List<Object> bindings) {
            run("execute", sql, bindings);
            // Like JDBC: true only when the statement produced a result set.
            return sql.trim().toLowerCase(java.util.Locale.ROOT).startsWith("select");
        }

        @Override
        public void executeUnprepared(String sql) {
            run("unprepared", sql, null);
        }

        @Override
        public boolean inExternalTransaction() {
            return external;
        }

        @Override
        public void begin() {
            calls.add("begin");
            if (beginFailure != null) {
                throw beginFailure;
            }
        }

        @Override
        public void commit() {
            calls.add("commit");
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        public void rollback() {
            calls.add("rollback");
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
            if (rollbackError != null) {
                throw rollbackError;
            }
        }

        @Override
        public void savepoint(String name) {
            if (!savepoints.add(name)) {
                throw new AssertionError("savepoint name reused in one session: " + name);
            }
            calls.add("savepoint " + name);
        }

        @Override
        public void rollbackToSavepoint(String name) {
            calls.add("rollbackToSavepoint " + name);
            if (losesSavepoints) {
                throw failure("40001", DriverFailureKind.CONCURRENCY);
            }
        }

        @Override
        public void releaseSavepoint(String name) {
            calls.add("releaseSavepoint " + name);
        }

        @Override
        public void close() {
            if (closed) {
                throw new AssertionError("session closed twice");
            }
            closed = true;
            open.decrementAndGet();
            calls.add("close");
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        @Override
        public void discard() {
            if (closed) {
                throw new AssertionError("session closed twice");
            }
            closed = true;
            open.decrementAndGet();
            calls.add("discard");
        }
    }

    /** A driver cursor over scripted rows, honoring the driver contract. */
    private final class FakeCursor implements RowCursor {

        private final List<Map<String, Object>> rows;
        private int index;
        private boolean isIterated;
        private boolean isClosed;
        private final Thread owner = Thread.currentThread();

        FakeCursor(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        private void checkOwner() {
            if (isCursorThreadOwned && Thread.currentThread() != owner) {
                throw new IllegalStateException("cursor used off its owner thread");
            }
        }

        @Override
        public java.util.Iterator<Map<String, Object>> iterator() {
            if (isIterated) {
                throw new IllegalStateException("iterated twice");
            }
            isIterated = true;
            return this;
        }

        @Override
        public boolean hasNext() {
            checkOwner();
            if (isClosed) {
                return false;
            }
            if (index == cursorFailureRow) {
                throw cursorFailure;
            }
            if (index < rows.size()) {
                return true;
            }
            close();
            return false;
        }

        @Override
        public Map<String, Object> next() {
            checkOwner();
            if (isClosed) {
                throw new IllegalStateException("closed");
            }
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            return new LinkedHashMap<>(rows.get(index++));
        }

        @Override
        public void close() {
            checkOwner();
            if (isClosed) {
                return;
            }
            isClosed = true;
            openCursors.decrementAndGet();
            calls.add("cursorClose");
            if (cursorCloseFailure != null) {
                throw cursorCloseFailure;
            }
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }
    }
}
