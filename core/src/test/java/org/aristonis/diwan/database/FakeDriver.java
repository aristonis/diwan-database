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

/** Scriptable driver for core tests: records every call and counts sessions still open. */
final class FakeDriver implements Driver {

    private final Map<String, List<Map<String, Object>>> rows = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final Map<String, DriverException> failures = new HashMap<>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger opened = new AtomicInteger();
    private EnumSet<DriverCapability> capabilities = EnumSet.of(DriverCapability.SAVEPOINT_RELEASE);
    private boolean external;
    private boolean losesSavepoints;
    private DriverException openFailure;
    private DriverException commitFailure;
    private RuntimeException closeFailure;
    private DriverException rollbackFailure;
    private Error rollbackError;
    private int concurrencyFailuresLeft;
    private String concurrencyFailureSql;

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

    FakeDriver failsWithConcurrency(String sql, int times) {
        concurrencyFailureSql = sql;
        concurrencyFailuresLeft = times;
        return this;
    }

    FakeDriver failsToOpen(DriverException failure) {
        openFailure = failure;
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
}
