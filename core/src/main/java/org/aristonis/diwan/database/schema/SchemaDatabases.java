package org.aristonis.diwan.database.schema;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.grammar.SchemaGrammar;

/** Checks and dispatches the database commands of {@link Schema}, so that class stays small. */
final class SchemaDatabases {

    private SchemaDatabases() {
    }

    /**
     * Creates a database, or records the statement in pretend mode.
     *
     * @param connection the connection to run on, never {@code null}
     * @param reader the live connection; differs from {@code connection} only in pretend mode
     * @param grammar the grammar, never {@code null}
     * @param name the database name
     * @param template the database to copy, or {@code null}
     * @param strategy the copy strategy, or {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or blank, or {@code template}
     *     is blank
     */
    static void create(Connection connection, Connection reader, SchemaGrammar grammar, String name,
            String template, CopyStrategy strategy) {
        check("name", name);
        if (template != null) {
            check("template", template);
        }
        if (connection != reader) {
            connection.statement(grammar.compileCreateDatabase(reader, name, template, strategy));
        } else {
            grammar.createDatabase(connection, name, template, strategy);
        }
    }

    /**
     * Drops a database if it exists, or records the statement in pretend mode.
     *
     * @param connection the connection to run on, never {@code null}
     * @param reader the live connection; differs from {@code connection} only in pretend mode
     * @param grammar the grammar, never {@code null}
     * @param name the database name
     * @param force whether to close the sessions that use the database first
     * @return {@code true} if the database existed; {@code false} in pretend mode
     * @throws IllegalArgumentException if {@code name} is {@code null} or blank
     */
    static boolean dropIfExists(Connection connection, Connection reader, SchemaGrammar grammar,
            String name, boolean force) {
        check("name", name);
        if (connection != reader) {
            connection.statement(grammar.compileDropDatabaseIfExists(reader, name, force));
            return false;
        }
        return grammar.dropDatabaseIfExists(connection, name, force);
    }

    private static void check(String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("The database " + label + " must not be null or blank");
        }
    }
}
