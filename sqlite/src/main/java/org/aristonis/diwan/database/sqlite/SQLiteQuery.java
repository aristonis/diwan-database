package org.aristonis.diwan.database.sqlite;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;

/** Builds a {@link QueryFactory} for SQLite. */
public final class SQLiteQuery {

    private SQLiteQuery() {
    }

    /**
     * Creates a query factory with the SQLite grammar and processor.
     *
     * @param connection a connection to SQLite, never {@code null}; inside a transaction or pretend
     *     callback, the connection the callback was given
     * @return the query factory, never {@code null}
     * @throws NullPointerException if {@code connection} is {@code null}
     */
    public static QueryFactory of(Connection connection) {
        return QueryFactory.of(connection, new SQLiteQueryGrammar(connection.getTablePrefix()), new SQLiteProcessor());
    }
}
