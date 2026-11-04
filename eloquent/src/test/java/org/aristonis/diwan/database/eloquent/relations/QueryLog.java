package org.aristonis.diwan.database.eloquent.relations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;

/**
 * Wraps a connection so every select it runs is recorded as SQL and bindings, and every write
 * (insert, update, delete, statement) apart, so a select count is not changed by the writes.
 * Work inside {@code transaction(...)} runs on a wrapped transaction connection, so it is logged too.
 */
final class QueryLog {

    private static final List<String> SELECTS = Arrays.asList("select", "selectOne", "scalar", "cursor");

    private final List<String> sql = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> WRITES = Arrays.asList("insert", "update", "delete", "statement",
            "affectingStatement");

    private final List<Integer> bindingCounts = Collections.synchronizedList(new ArrayList<>());
    private final List<String> writes = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> writeBindingCounts = Collections.synchronizedList(new ArrayList<>());

    /**
     * Returns a connection that records its selects in this log.
     *
     * @param real the connection to forward to
     * @return the logging connection
     */
    Connection wrap(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (SELECTS.contains(method.getName()) && args != null && args[0] instanceof String) {
                        sql.add((String) args[0]);
                        bindingCounts.add(countOf(args.length > 1 ? args[1] : null));
                    } else if (WRITES.contains(method.getName()) && args != null && args[0] instanceof String) {
                        writes.add((String) args[0]);
                        writeBindingCounts.add(countOf(args.length > 1 ? args[1] : null));
                    }
                    if ("transaction".equals(method.getName()) && args != null
                            && args[0] instanceof TransactionCallback) {
                        TransactionCallback<?> work = (TransactionCallback<?>) args[0];
                        args = args.clone();
                        args[0] = (TransactionCallback<Object>) inner -> work.run(wrap(inner));
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static int countOf(Object argument) {
        if (argument instanceof List) {
            return ((List<?>) argument).size();
        }
        return argument == null ? 0 : ((Object[]) argument).length;
    }

    /** Forgets the recorded selects and writes. */
    void clear() {
        sql.clear();
        bindingCounts.clear();
        writes.clear();
        writeBindingCounts.clear();
    }

    /**
     * Returns the SQL of the recorded writes.
     *
     * @return a copy, in order
     */
    List<String> writes() {
        synchronized (writes) {
            return new ArrayList<>(writes);
        }
    }

    /**
     * Returns the binding count of each recorded write.
     *
     * @return a copy, in order
     */
    List<Integer> writeBindingCounts() {
        synchronized (writeBindingCounts) {
            return new ArrayList<>(writeBindingCounts);
        }
    }

    /**
     * Returns the SQL of the recorded selects.
     *
     * @return a copy, in order
     */
    List<String> sql() {
        synchronized (sql) {
            return new ArrayList<>(sql);
        }
    }

    /**
     * Returns the binding count of each recorded select.
     *
     * @return a copy, in order
     */
    List<Integer> bindingCounts() {
        synchronized (bindingCounts) {
            return new ArrayList<>(bindingCounts);
        }
    }

    /**
     * Returns how many recorded selects read the given table.
     *
     * @param table the table name
     * @return the number of selects starting {@code select * from "table"}
     */
    int countOn(String table) {
        int count = 0;
        for (String text : sql()) {
            if (text.startsWith("select * from \"" + table + "\"")) {
                count++;
            }
        }
        return count;
    }
}
