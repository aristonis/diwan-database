package org.aristonis.diwan.database.eloquent;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.driver.RowCursor;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Processor;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.query.QueryGrammar;

/** A stub connection that records each statement and answers selects from a script. */
final class FakeDb {

    /** One call: the connection method, its SQL and its bindings. */
    record Call(String method, String sql, List<Object> bindings) {
    }

    final List<Call> calls = new ArrayList<>();
    private final Map<String, Deque<List<Map<String, Object>>>> scripted = new HashMap<>();
    private final Deque<Object> ids = new ArrayDeque<>();
    private int affected = 1;
    private int level;
    final Connection connection;
    final QueryFactory db;

    FakeDb() {
        connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTablePrefix":
                            return "";
                        case "getName":
                            return "fake";
                        case "toString":
                            return "fake connection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "transactionLevel":
                            return level;
                        case "transaction":
                            return transaction((TransactionCallback<?>) args[0], (Connection) proxy);
                        default:
                            return run(method.getName(), (String) args[0], bindings(args[1]));
                    }
                });
        db = QueryFactory.of(connection, new Grammar(), new IdProcessor());
    }

    private Object transaction(TransactionCallback<?> callback, Connection proxy) {
        level++;
        try {
            return callback.run(proxy);
        } finally {
            level--;
        }
    }

    void inTransaction(Runnable work) {
        level++;
        try {
            work.run();
        } finally {
            level--;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bindings(Object argument) {
        return new ArrayList<>((List<Object>) argument);
    }

    private Object run(String method, String sql, List<Object> bindings) {
        calls.add(new Call(method, sql, bindings));
        switch (method) {
            case "select":
                return nextRows(sql);
            case "cursor":
                return new ListCursor(nextRows(sql));
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

    private List<Map<String, Object>> nextRows(String sql) {
        Deque<List<Map<String, Object>>> queue = scripted.get(sql);
        if (queue == null || queue.isEmpty()) {
            return new ArrayList<>();
        }
        return queue.size() == 1 ? queue.peek() : queue.poll();
    }

    /** Answers the select {@code sql} with {@code rows}; repeated calls queue answers in order. */
    @SafeVarargs
    final FakeDb returns(String sql, Map<String, Object>... rows) {
        List<Map<String, Object>> answer = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            answer.add(row);
        }
        scripted.computeIfAbsent(sql, k -> new ArrayDeque<>()).add(answer);
        return this;
    }

    FakeDb nextId(Object id) {
        ids.add(id);
        return this;
    }

    FakeDb affects(int count) {
        affected = count;
        return this;
    }

    Call last() {
        return calls.get(calls.size() - 1);
    }

    List<String> sql() {
        List<String> sql = new ArrayList<>();
        for (Call call : calls) {
            sql.add(call.sql());
        }
        return sql;
    }

    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    /** The base grammar with nothing overridden. */
    static final class Grammar extends QueryGrammar {
        Grammar() {
            super("");
        }
    }

    /** Runs the insert through the connection, then answers the next scripted id. */
    private final class IdProcessor extends Processor {
        @Override
        public Object processInsertGetId(Builder query, String sql, List<Object> values, String sequence) {
            query.getConnection().insert(sql, values);
            return toId(ids.isEmpty() ? 1L : ids.poll());
        }
    }

    /** A cursor over scripted rows. */
    static final class ListCursor implements RowCursor {

        private final Iterator<Map<String, Object>> rows;
        boolean closed;

        ListCursor(List<Map<String, Object>> rows) {
            this.rows = rows.iterator();
        }

        @Override
        public Iterator<Map<String, Object>> iterator() {
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
                throw new NoSuchElementException();
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
