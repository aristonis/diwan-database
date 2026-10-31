package org.aristonis.diwan.database.sqlite;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Schema;

/** Builds a {@link Schema} for SQLite. */
public final class SQLiteSchema {

    private SQLiteSchema() {
    }

    /**
     * Creates a schema builder with the SQLite grammar.
     *
     * @param connection a connection to SQLite, never {@code null}
     * @return the schema builder, never {@code null}
     * @throws NullPointerException if {@code connection} is {@code null}
     */
    public static Schema of(Connection connection) {
        return Schema.of(connection, new SQLiteSchemaGrammar());
    }
}
