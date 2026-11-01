package org.aristonis.diwan.database.query;

import java.lang.reflect.Proxy;
import org.aristonis.diwan.database.Connection;

/** Builds query factories over a stub connection that only knows its name and table prefix. */
final class TestQueries {

    private TestQueries() {
    }

    static Connection connection(String prefix) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTablePrefix":
                            return prefix;
                        case "getName":
                            return "test";
                        case "toString":
                            return "stub connection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    static QueryFactory db() {
        return db("");
    }

    static QueryFactory db(String prefix) {
        return QueryFactory.of(connection(prefix), new TestGrammar(prefix), new Processor());
    }

    /** The base grammar with nothing overridden. */
    static final class TestGrammar extends QueryGrammar {
        TestGrammar(String tablePrefix) {
            super(tablePrefix);
        }
    }
}
