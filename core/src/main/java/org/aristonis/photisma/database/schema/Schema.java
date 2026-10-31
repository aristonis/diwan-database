package org.aristonis.photisma.database.schema;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.photisma.database.Connection;
import org.aristonis.photisma.database.driver.DriverCapability;

/**
 * Creates, changes and inspects tables, as Laravel's {@code Schema} facade does.
 *
 * <p>A schema pairs a {@link Connection} with the {@link SchemaGrammar} of its database:
 *
 * <pre>{@code
 * Schema schema = Schema.of(db, new SQLiteSchemaGrammar());
 * schema.create("users", table -> {
 *     table.id();
 *     table.string("email").unique();
 *     table.timestamps();
 * });
 * }</pre>
 *
 * <p>The connection's table prefix is added to every table name. Each call checks its blueprint
 * before any SQL runs. When a call produces several statements, they run in one transaction if the
 * connection is not in one yet and the database can undo schema changes; otherwise they run one by
 * one, and those before a failure stay applied.
 *
 * <p>Instances are immutable and as safe to share between threads as their connection.
 */
public final class Schema {

    private final Connection connection;
    private final SchemaGrammar grammar;

    private Schema(Connection connection, SchemaGrammar grammar) {
        this.connection = connection;
        this.grammar = grammar;
    }

    /**
     * Creates a schema builder.
     *
     * @param connection the connection to run on, never {@code null}; inside a transaction or
     *     pretend callback, pass the connection the callback was given
     * @param grammar the grammar of the connection's database, never {@code null}
     * @return the schema builder, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Schema of(Connection connection, SchemaGrammar grammar) {
        return new Schema(Objects.requireNonNull(connection, "connection"), Objects.requireNonNull(grammar, "grammar"));
    }

    /**
     * Returns the connection.
     *
     * @return the connection, never {@code null}
     */
    public Connection connection() {
        return connection;
    }

    /**
     * Returns the grammar.
     *
     * @return the grammar, never {@code null}
     */
    public SchemaGrammar grammar() {
        return grammar;
    }

    /**
     * Creates a table.
     *
     * @param table the table name without prefix, never {@code null}
     * @param callback adds the columns, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a name is invalid, the table has no column, a column is
     *     added twice or a generated index name is too long
     * @throws UnsupportedOperationException if the grammar does not support a command or type
     * @throws org.aristonis.photisma.database.QueryException if a statement fails
     */
    public void create(String table, Consumer<Blueprint> callback) {
        Blueprint blueprint = blueprint(table, true);
        blueprint.addCommand(Command.CREATE, Collections.<String, Object>emptyMap());
        Objects.requireNonNull(callback, "callback").accept(blueprint);
        build(blueprint);
    }

    /**
     * Adds columns to an existing table.
     *
     * @param table the table name without prefix, never {@code null}
     * @param callback adds the columns, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a name is invalid, a column is added twice or a
     *     generated index name is too long
     * @throws UnsupportedOperationException if the grammar does not support a command or type
     * @throws org.aristonis.photisma.database.QueryException if a statement fails
     */
    public void table(String table, Consumer<Blueprint> callback) {
        Blueprint blueprint = blueprint(table, false);
        Objects.requireNonNull(callback, "callback").accept(blueprint);
        build(blueprint);
    }

    /**
     * Drops a table.
     *
     * @param table the table name without prefix, never {@code null}
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the name is invalid
     * @throws org.aristonis.photisma.database.QueryException if the table does not exist or the
     *     statement fails
     */
    public void drop(String table) {
        single(table, Command.DROP, Collections.<String, Object>emptyMap());
    }

    /**
     * Drops a table if it exists.
     *
     * @param table the table name without prefix, never {@code null}
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the name is invalid
     * @throws org.aristonis.photisma.database.QueryException if the statement fails
     */
    public void dropIfExists(String table) {
        single(table, Command.DROP_IF_EXISTS, Collections.<String, Object>emptyMap());
    }

    /**
     * Renames a table.
     *
     * @param from the current name without prefix, never {@code null}
     * @param to the new name without prefix, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a name is invalid
     * @throws UnsupportedOperationException if the grammar does not support renaming
     * @throws org.aristonis.photisma.database.QueryException if the statement fails
     */
    public void rename(String from, String to) {
        String target = prefixed(to);
        single(from, Command.RENAME, Collections.singletonMap(Command.TO, target));
    }

    /**
     * Tells whether a table exists.
     *
     * @param table the table name without prefix, never {@code null}
     * @return {@code true} if the table exists; {@code false} in pretend mode
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the name is invalid
     * @throws UnsupportedOperationException if the grammar does not support the check
     * @throws IllegalStateException if the query does not return a count
     * @throws org.aristonis.photisma.database.QueryException if the query fails
     */
    public boolean hasTable(String table) {
        SchemaQuery query = grammar.compileTableExists(connection, prefixed(table));
        Object count = connection.scalar(query.sql(), query.bindings()).orElse(0);
        if (count instanceof Number) {
            return ((Number) count).longValue() > 0;
        }
        try {
            return Long.parseLong(count.toString().trim()) > 0;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("The table check returned a value that is not a count", e);
        }
    }

    /**
     * Tells whether a table has a column. Letter case is ignored, as in Laravel.
     *
     * @param table the table name without prefix, never {@code null}
     * @param column the column name, never {@code null}
     * @return {@code true} if the column exists; {@code false} in pretend mode
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if a name is invalid
     * @throws UnsupportedOperationException if the grammar does not support the listing
     * @throws org.aristonis.photisma.database.QueryException if the query fails
     */
    public boolean hasColumn(String table, String column) {
        String wanted = SchemaNames.check("column", column).toLowerCase(Locale.ROOT);
        SchemaQuery query = grammar.compileColumnNames(connection, prefixed(table));
        List<Map<String, Object>> rows = connection.select(query.sql(), query.bindings());
        for (Map<String, Object> row : rows) {
            Iterator<Object> values = row.values().iterator();
            Object name = values.hasNext() ? values.next() : null;
            if (name != null && name.toString().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private Blueprint blueprint(String table, boolean isCreating) {
        return new Blueprint(prefixed(table), isCreating);
    }

    private String prefixed(String table) {
        SchemaNames.check("table", table);
        return SchemaNames.check("table", connection.getTablePrefix() + table);
    }

    private void single(String table, String command, Map<String, ?> attributes) {
        Blueprint blueprint = blueprint(table, false);
        blueprint.addCommand(command, attributes);
        build(blueprint);
    }

    private void build(Blueprint blueprint) {
        blueprint.prepare(grammar.maxIdentifierLength());
        List<String> statements = grammar.compile(blueprint, connection);
        if (statements.size() > 1 && connection.transactionLevel() == 0
                && connection.getDriverInfo().supports(DriverCapability.TRANSACTIONAL_DDL)) {
            connection.transaction(tx -> {
                run(tx, statements);
                return null;
            });
        } else {
            run(connection, statements);
        }
    }

    private static void run(Connection target, List<String> statements) {
        for (String sql : statements) {
            target.statement(sql);
        }
    }
}
