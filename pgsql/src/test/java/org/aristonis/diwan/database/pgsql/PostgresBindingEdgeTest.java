package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

class PostgresBindingEdgeTest {

    private static Connection stub() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTablePrefix":
                            return "";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "stub connection";
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @Test
    void binaryValuesAreWrittenAsByteaHexLiterals() {
        QueryFactory db = PostgresQuery.of(stub());

        assertEquals("select * from \"t\" where \"a\" = '\\x00ff10'::bytea",
                db.table("t").where("a", new byte[] {0, (byte) 0xff, 0x10}).toRawSql());
    }

    @Test
    void anEmptyBinaryValueIsAnEmptyByteaLiteral() {
        QueryFactory db = PostgresQuery.of(stub());

        assertEquals("select * from \"t\" where \"a\" = '\\x'::bytea", db.table("t").where("a", new byte[0]).toRawSql());
    }

    @Test
    void tooFewValuesForThePlaceholdersFailWithAQueryException() {
        Connection connection = PostgresServer.newDatabase();

        assertThrows(QueryException.class, () -> connection.select("select ? as a, ? as b", 1));
    }

    @Test
    void moreValuesThanPlaceholdersFailWithAQueryException() {
        Connection connection = PostgresServer.newDatabase();

        assertThrows(QueryException.class, () -> connection.select("select ? as a", 1, 2));
    }

    @Test
    void theRightNumberOfValuesRuns() {
        Connection connection = PostgresServer.newDatabase();

        assertEquals(1, connection.select("select ?::int as a, ?::int as b", 1, 2).size());
    }
}
