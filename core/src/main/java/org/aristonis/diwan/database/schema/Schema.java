package org.aristonis.diwan.database.schema;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.driver.DriverCapability;

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
 * <p>On SQLite, adding or dropping a primary or foreign key on an existing table rebuilds the
 * table. Outside a transaction the schema builder runs the rebuild through
 * {@link #withoutForeignKeyConstraints(Consumer)} on its own; inside one, a rebuild of a table other
 * tables point at is refused with {@link ForeignKeysOffRequiredException}, because SQLite would apply
 * their delete actions. The migrator catches it and runs that migration again with enforcement off.
 *
 * <p>Instances are immutable and as safe to share between threads as their connection.
 */
public final class Schema {

    private final Connection connection;
    private final SchemaGrammar grammar;
    // Reads the live schema; differs from connection only in pretend mode, where writes are recorded.
    private final Connection reader;

    private Schema(Connection connection, SchemaGrammar grammar, Connection reader) {
        this.connection = connection;
        this.grammar = grammar;
        this.reader = reader;
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
        Objects.requireNonNull(connection, "connection");
        return new Schema(connection, Objects.requireNonNull(grammar, "grammar"), connection);
    }

    /**
     * Returns a schema builder with the same grammar that runs on another connection of the same
     * database, such as the connection a transaction callback receives.
     *
     * @param other the connection to run on, never {@code null}
     * @return a schema builder bound to {@code other}, never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     * @throws IllegalArgumentException if {@code other} talks to a different kind of database
     */
    public Schema on(Connection other) {
        Objects.requireNonNull(other, "other");
        String expected = connection.getDriverInfo().databaseName();
        String actual = other.getDriverInfo().databaseName();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("The connection is to " + actual
                    + ", but this schema builder writes SQL for " + expected);
        }
        return new Schema(other, grammar, other);
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
     * @throws org.aristonis.diwan.database.QueryException if a statement fails
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
     * @throws org.aristonis.diwan.database.QueryException if a statement fails
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
     * @throws org.aristonis.diwan.database.QueryException if the table does not exist or the
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
     * @throws org.aristonis.diwan.database.QueryException if the statement fails
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
     * @throws org.aristonis.diwan.database.QueryException if the statement fails
     */
    public void rename(String from, String to) {
        String target = prefixed(to);
        single(from, Command.RENAME, Collections.singletonMap(Command.TO, target));
    }

    /**
     * Lists a table's indexes, its primary key included.
     *
     * @param table the table name without prefix, never {@code null}
     * @return the indexes, unmodifiable, never {@code null}; empty if the table does not exist
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws UnsupportedOperationException if the grammar cannot list indexes
     */
    public List<IndexInfo> indexes(String table) {
        return grammar.indexes(reader, prefixed(table));
    }

    /**
     * Lists a table's foreign keys.
     *
     * @param table the table name without prefix, never {@code null}
     * @return the foreign keys, unmodifiable, never {@code null}; empty if the table does not exist
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws UnsupportedOperationException if the grammar cannot list foreign keys
     */
    public List<ForeignKeyInfo> foreignKeys(String table) {
        return grammar.foreignKeys(reader, prefixed(table));
    }

    /**
     * Runs schema work in one transaction with foreign key enforcement switched off, as Laravel's
     * {@code Schema::withoutForeignKeyConstraints} does.
     *
     * <p>On SQLite the work runs on one physical connection with {@code PRAGMA foreign_keys} off,
     * and the setting is put back afterwards; if it cannot be put back, that session is discarded
     * instead of closed, so a driver that can abort the connection keeps it out of the pool. Rows the work writes are not checked against foreign keys,
     * except those a table rebuild copies. In pretend mode the work only runs.
     *
     * @param work the work, given a schema builder for the switched-off connection, never
     *     {@code null}
     * @throws NullPointerException if {@code work} is {@code null}
     * @throws IllegalStateException if the connection is inside a transaction
     * @throws RuntimeException any failure of the work, after the transaction is undone
     */
    public void withoutForeignKeyConstraints(Consumer<Schema> work) {
        Objects.requireNonNull(work, "work");
        if (isPretending()) {
            work.accept(this);
            return;
        }
        grammar.withoutForeignKeyConstraints(connection, tx -> {
            work.accept(new Schema(tx, grammar, tx));
            return null;
        });
    }

    /**
     * Records the statements schema work would run, without changing the database.
     *
     * <p>Unlike a schema builder made on a pretend connection, this one reads the live schema, so
     * a change that depends on the table's current shape, such as a SQLite rebuild, lists its real
     * statements. A table made earlier in the same pretend run does not exist yet for such a read.
     *
     * @param work the work, given a recording schema builder, never {@code null}
     * @return the recorded statements in order, unmodifiable, never {@code null}
     * @throws NullPointerException if {@code work} is {@code null}
     * @throws RuntimeException any failure of the work
     */
    public List<LoggedQuery> pretend(Consumer<Schema> work) {
        Objects.requireNonNull(work, "work");
        return connection.pretend(recorder -> work.accept(new Schema(recorder, grammar, reader)));
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
     * @throws org.aristonis.diwan.database.QueryException if the query fails
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
     * @throws org.aristonis.diwan.database.QueryException if the query fails
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
        return new Blueprint(prefixed(table), connection.getTablePrefix(), isCreating);
    }

    /**
     * Runs one statement as given, for what the builder cannot say.
     *
     * <p>Like the other calls, it runs on this schema's connection: inside a migration it joins the
     * migration's transaction, and under {@link #pretend(Consumer)} it is recorded, not run. The SQL
     * is trusted, like an {@code Expression}: build it from constants only, never from user input. A
     * migration that the migrator runs again with foreign keys off, after a SQLite table rebuild
     * was refused, runs its statements again too, so keep them to the database.
     *
     * @param sql the statement, never {@code null} or blank
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     * @throws org.aristonis.diwan.database.QueryException if the statement fails
     */
    public void statement(String sql) {
        connection.statement(ColumnDefinition.trustedSql("statement", sql));
    }

    /**
     * Returns Laravel's short name of this schema's database, such as {@code "sqlite"} or
     * {@code "pgsql"}.
     *
     * @return the name, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not name its database
     */
    public String driverName() {
        return grammar.driverName();
    }

    private boolean isPretending() {
        return reader != connection;
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
        List<String> statements = grammar.compile(blueprint, reader);
        if (!blueprint.isCreating()) {
            DropColumnGuard.check(blueprint, grammar, reader);
        }
        if (isPretending()) {
            run(connection, statements);
        } else if (grammar.requiresForeignKeysOff(blueprint) && connection.transactionLevel() == 0) {
            grammar.withoutForeignKeyConstraints(connection, tx -> {
                runChecked(tx, blueprint, statements);
                return null;
            });
        } else if (statements.size() > 1 && connection.transactionLevel() == 0
                && connection.getDriverInfo().supports(DriverCapability.TRANSACTIONAL_DDL)) {
            connection.transaction(tx -> {
                runChecked(tx, blueprint, statements);
                return null;
            });
        } else {
            runChecked(connection, blueprint, statements);
        }
    }

    private void runChecked(Connection target, Blueprint blueprint, List<String> statements) {
        run(target, statements);
        for (SchemaQuery check : grammar.compileForeignKeyChecks(blueprint, target)) {
            List<Map<String, Object>> rows = target.select(check.sql(), check.bindings());
            if (!rows.isEmpty()) {
                Map<String, Object> first = rows.get(0);
                throw new ForeignKeyCheckException(String.valueOf(first.get("table")),
                        String.valueOf(first.get("parent")), rows.size());
            }
        }
    }

    private static void run(Connection target, List<String> statements) {
        for (String sql : statements) {
            target.statement(sql);
        }
    }
}
