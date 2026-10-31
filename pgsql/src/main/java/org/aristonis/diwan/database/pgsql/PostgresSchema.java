package org.aristonis.diwan.database.pgsql;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Schema;

/** Builds a {@link Schema} for PostgreSQL. */
public final class PostgresSchema {

    private PostgresSchema() {
    }

    /**
     * Creates a schema builder with the PostgreSQL grammar.
     *
     * @param connection a connection to PostgreSQL, never {@code null}
     * @return the schema builder, never {@code null}
     * @throws NullPointerException if {@code connection} is {@code null}
     */
    public static Schema of(Connection connection) {
        return Schema.of(connection, new PostgresSchemaGrammar());
    }
}
