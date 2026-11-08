package org.aristonis.diwan.database.query;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.driver.DriverInfo;

/** A stub connection that records each statement and answers selects from a script. */
final class RecordingConnection {

    /** One call: the connection method, its SQL and its bindings. */
    record Call(String method, String sql, List<Object> bindings) {
    }

    final List<Call> calls = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> rows = new HashMap<>();
    private RuntimeException failure;
    private int affected = 1;
    private int level;
    private String version = "1";
    final Connection connection;

    RecordingConnection() {
        connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "getTablePrefix":
                            return "";
                        case "getName":
                            return "recording";
                        case "toString":
                            return "recording connection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "getDriverInfo":
                            return new DriverInfo("recording", version, EnumSet.noneOf(DriverCapability.class));
                        case "withStatementLockWait":
                            calls.add(new Call("statementLockWait", String.valueOf(args[0]), List.of()));
                            Object statementResult = ((java.util.function.Supplier<?>) args[1]).get();
                            calls.add(new Call("statementLockWaitRestore", String.valueOf(args[0]), List.of()));
                            return statementResult;
                        case "transactionLevel":
                            return level;
                        case "transaction":
                            return transaction((TransactionCallback<?>) args[0], (Connection) proxy);
                        default:
                            return run(name, (String) args[0], bindings(args[1]));
                    }
                });
    }

    private Object transaction(TransactionCallback<?> callback, Connection proxy) {
        calls.add(new Call("transaction", "begin", List.of()));
        level++;
        try {
            return callback.run(proxy);
        } finally {
            level--;
            calls.add(new Call("transaction", "end", List.of()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bindings(Object argument) {
        if (argument instanceof List) {
            return new ArrayList<>((List<Object>) argument);
        }
        throw new IllegalStateException("Builder must pass bindings as a List");
    }

    private Object run(String method, String sql, List<Object> bindings) {
        calls.add(new Call(method, sql, bindings));
        if (failure != null) {
            throw failure;
        }
        switch (method) {
            case "select":
                return rows.getOrDefault(sql, List.of());
            case "cursor":
                return new org.aristonis.diwan.database.query.internal.MappingCursor<Map<String, Object>>(
                        new ListCursor(rows.getOrDefault(sql, List.of())), java.util.function.Function.identity());
            case "insert":
            case "statement":
                return true;
            case "update":
            case "delete":
            case "affectingStatement":
                return affected;
            default:
                throw new UnsupportedOperationException(method);
        }
    }

    RecordingConnection returns(String sql, List<Map<String, Object>> result) {
        rows.put(sql, result);
        return this;
    }

    RecordingConnection affects(int count) {
        affected = count;
        return this;
    }

    RecordingConnection reportsVersion(String databaseVersion) {
        version = databaseVersion;
        return this;
    }

    RecordingConnection failsWith(RuntimeException error) {
        failure = error;
        return this;
    }

    QueryFactory db() {
        return QueryFactory.of(connection, new TestQueries.TestGrammar(""), new Processor());
    }

    QueryFactory db(QueryGrammar grammar) {
        return QueryFactory.of(connection, grammar, new Processor());
    }

    Call last() {
        return calls.get(calls.size() - 1);
    }

    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    /** A cursor over scripted rows. */
    static final class ListCursor implements org.aristonis.diwan.database.driver.RowCursor {

        private final java.util.Iterator<Map<String, Object>> rows;
        private boolean closed;

        ListCursor(List<Map<String, Object>> rows) {
            this.rows = rows.iterator();
        }

        @Override
        public java.util.Iterator<Map<String, Object>> iterator() {
            return this;
        }

        @Override
        public boolean hasNext() {
            boolean has = !closed && rows.hasNext();
            if (!has) {
                closed = true;
            }
            return has;
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            return rows.next();
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}
