package org.aristonis.diwan.database.eloquent.relations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;

/** Wraps a connection so every select it runs is recorded as SQL and bindings. */
final class QueryLog {

    private static final List<String> SELECTS = Arrays.asList("select", "selectOne", "scalar", "cursor");

    private final List<String> sql = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> bindingCounts = Collections.synchronizedList(new ArrayList<>());

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

    /** Forgets the recorded selects. */
    void clear() {
        sql.clear();
        bindingCounts.clear();
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
