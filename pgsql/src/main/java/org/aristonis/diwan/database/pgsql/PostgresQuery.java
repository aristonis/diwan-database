package org.aristonis.diwan.database.pgsql;

import java.util.Objects;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;

/** Builds a {@link QueryFactory} for PostgreSQL. */
public final class PostgresQuery {

    private PostgresQuery() {
    }

    /**
     * Creates a query factory with the PostgreSQL grammar and processor.
     *
     * <pre>{@code
     * QueryFactory db = PostgresQuery.of(connection);
     * long id = (Long) db.table("users").insertGetId(Map.of("name", "Ann"));
     * }</pre>
     *
     * @param connection a connection to PostgreSQL, never {@code null}
     * @return the query factory, never {@code null}
     * @throws NullPointerException if {@code connection} is {@code null}
     */
    public static QueryFactory of(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        return QueryFactory.of(connection, new PostgresQueryGrammar(connection.getTablePrefix()),
                new PostgresProcessor());
    }
}
