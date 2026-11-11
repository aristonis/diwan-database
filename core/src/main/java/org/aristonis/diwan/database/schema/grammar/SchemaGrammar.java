package org.aristonis.diwan.database.schema.grammar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.exception.ForeignKeyCheckException;
import org.aristonis.diwan.database.schema.Blueprint;
import org.aristonis.diwan.database.schema.ColumnDefinition;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.schema.TableInfo;
import org.aristonis.diwan.database.schema.ViewInfo;

/**
 * Turns a {@link Blueprint} into SQL for one database.
 *
 * <p>Each database module extends this class. The base class sends each {@link Command} to its
 * {@code compile} method and quotes names and values; a subclass supplies the SQL for the
 * commands and column types its database supports. Any command or type a subclass does not handle
 * fails with an error that names it and the grammar, instead of producing wrong SQL.
 *
 * @implSpec Methods added in later versions come with a body that refuses the command, so existing
 *     grammars keep compiling. Quoting goes through {@link #wrap(String)},
 *     {@link #wrapTable(String)} and {@link #literal(Object)}; override them, not the callers, to
 *     change quoting.
 */
public abstract class SchemaGrammar extends InspectionGrammar {

    /** Creates a grammar. */
    protected SchemaGrammar() {
    }

    /**
     * Compiles every command of a blueprint.
     *
     * @param blueprint the checked blueprint, never {@code null}
     * @param connection the connection the SQL will run on, never {@code null}; a grammar may read
     *     the current schema through it
     * @return the statements in the order to run them, unmodifiable, never {@code null}
     * @throws UnsupportedOperationException if a command or column type is not supported
     */
    public List<String> compile(Blueprint blueprint, Connection connection) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(connection, "connection");
        refuseUnwrittenClauses(blueprint);
        List<String> statements = new ArrayList<>();
        for (Command command : blueprint.commands()) {
            statements.addAll(compileCommand(blueprint, command, connection));
        }
        return Collections.unmodifiableList(statements);
    }

    /**
     * Compiles one command.
     *
     * @implSpec Sends the command to the {@code compile} method named after it and refuses any
     *     other name. Override it to support a command of your own, and call this method for the
     *     rest.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}
     * @throws UnsupportedOperationException if the command is not supported
     */
    protected List<String> compileCommand(Blueprint blueprint, Command command, Connection connection) {
        switch (command.name()) {
            case Command.CREATE:
                return Collections.singletonList(compileCreate(blueprint, command, connection));
            case Command.ADD:
                return Collections.singletonList(compileAdd(blueprint, command, connection));
            case Command.DROP:
                return Collections.singletonList(compileDrop(blueprint, command, connection));
            case Command.DROP_IF_EXISTS:
                return Collections.singletonList(compileDropIfExists(blueprint, command, connection));
            case Command.RENAME:
                return Collections.singletonList(compileRename(blueprint, command, connection));
            case Command.UNIQUE:
                return Collections.singletonList(compileUnique(blueprint, command, connection));
            case Command.PLAIN_INDEX:
                return Collections.singletonList(compileIndex(blueprint, command, connection));
            case Command.PRIMARY:
                return compilePrimary(blueprint, command, connection);
            case Command.FOREIGN:
                return compileForeign(blueprint, command, connection);
            case Command.DROP_INDEX:
            case Command.DROP_UNIQUE:
                return Collections.singletonList(compileDropIndex(blueprint, command, connection));
            case Command.DROP_PRIMARY:
                return compileDropPrimary(blueprint, command, connection);
            case Command.DROP_FOREIGN:
                return compileDropForeign(blueprint, command, connection);
            case Command.RENAME_INDEX:
                return compileRenameIndex(blueprint, command, connection);
            case Command.DROP_COLUMN:
                return compileDropColumn(blueprint, command, connection);
            case Command.RENAME_COLUMN:
                return compileRenameColumn(blueprint, command, connection);
            case Command.CHANGE:
                return compileChange(blueprint, command, connection);
            case Command.CHECK:
                return compileCheck(blueprint, command, connection);
            case Command.DROP_CHECK:
                return compileDropCheck(blueprint, command, connection);
            case Command.TABLE_COMMENT:
                return compileTableComment(blueprint, command, connection);
            default:
                throw unsupported("The command " + command.name());
        }
    }

    /**
     * Compiles {@link Command#CREATE}.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected String compileCreate(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Writes the start of a create statement, {@code create table "t"} or, for a temporary table,
     * {@code create temporary table "t"}.
     *
     * @implSpec Writes {@code create temporary table} when {@link Blueprint#isTemporary()} is
     *     {@code true}, else {@code create table}, followed by the wrapped table name.
     *
     * @param blueprint the blueprint, never {@code null}
     * @return the statement start without a trailing space, never {@code null}
     */
    protected String createTable(Blueprint blueprint) {
        return (blueprint.isTemporary() ? "create temporary table " : "create table ")
                + wrapTable(blueprint.table());
    }

    /**
     * Compiles {@link Command#TABLE_COMMENT}.
     *
     * @implSpec The base writes no statement, as a grammar without table comments skips the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}; empty in the base
     */
    protected List<String> compileTableComment(Blueprint blueprint, Command command, Connection connection) {
        return Collections.emptyList();
    }

    /**
     * Compiles {@link Command#ADD}.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected String compileAdd(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#DROP}.
     *
     * @implSpec The base returns {@code drop table <table>}.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     */
    protected String compileDrop(Blueprint blueprint, Command command, Connection connection) {
        return "drop table " + wrapTable(blueprint.table());
    }

    /**
     * Compiles {@link Command#DROP_IF_EXISTS}.
     *
     * @implSpec The base returns {@code drop table if exists <table>}.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     */
    protected String compileDropIfExists(Blueprint blueprint, Command command, Connection connection) {
        return "drop table if exists " + wrapTable(blueprint.table());
    }

    /**
     * Compiles {@link Command#RENAME}.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected String compileRename(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#DROP_COLUMN}: dropping columns.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileDropColumn(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#CHANGE}: redefining an existing column.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileChange(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Tells whether {@code modifiers} writes column checks and
     * generated expressions.
     *
     * @implSpec The base returns {@code false}, so a grammar that has not learned them refuses such
     *     a column instead of creating it without them.
     *
     * @return {@code true} if the grammar writes them
     */
    protected boolean supportsColumnClauses() {
        return false;
    }

    /**
     * Refuses columns whose checks or generated expressions this grammar would leave out.
     *
     * @param blueprint the blueprint, never {@code null}
     * @throws UnsupportedOperationException if a column has a check or generated expression and
     *     {@link #supportsColumnClauses()} is {@code false}
     */
    protected final void refuseUnwrittenClauses(Blueprint blueprint) {
        if (supportsColumnClauses()) {
            return;
        }
        for (ColumnDefinition column : blueprint.columns()) {
            if (!column.checks().isEmpty() || column.isGenerated()) {
                throw unsupported("Checks and generated columns");
            }
        }
    }

    /**
     * Compiles {@link Command#RENAME_COLUMN}: renaming a column.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileRenameColumn(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Runs work in one transaction with foreign key enforcement switched off, as Laravel's
     * {@code withoutForeignKeyConstraints} does.
     *
     * @implSpec The base runs the work in a transaction on the connection as is. A grammar whose
     *     database can switch enforcement off for one connection overrides it; the promise that a
     *     failure undoes the whole work holds only where schema changes are transactional.
     *
     * @param <T> the type of the result
     * @param connection the connection, never {@code null}, outside any transaction
     * @param work the work, never {@code null}
     * @return the result of the work, may be {@code null}
     * @throws IllegalStateException if the connection is inside a transaction
     * @throws RuntimeException any failure of the work, after the transaction is undone
     */
    public <T> T withoutForeignKeyConstraints(Connection connection, TransactionCallback<T> work) {
        if (connection.transactionLevel() > 0) {
            throw new IllegalStateException("Foreign key enforcement can only be switched off outside a transaction");
        }
        return connection.transaction(work);
    }

    /**
     * Checks every table for rows that break a foreign key, as the last step of work run with
     * enforcement off.
     *
     * @implSpec The base does nothing.
     *
     * @param connection the connection the work ran on, never {@code null}
     * @throws ForeignKeyCheckException if a row breaks a foreign key
     */
    public void checkForeignKeys(Connection connection) {
    }

    /**
     * Tells whether a blueprint must run with foreign key enforcement off to keep data safe.
     *
     * @implSpec The base returns {@code false}.
     *
     * @param blueprint the checked blueprint, never {@code null}
     * @return {@code true} if the schema builder must run it through
     *     {@link #withoutForeignKeyConstraints}
     */
    public boolean requiresForeignKeysOff(Blueprint blueprint) {
        return false;
    }

    /**
     * Returns queries that must find no rows once a blueprint's statements ran, each finding a row
     * that breaks a foreign key.
     *
     * @implSpec The base returns none. Each row must have the columns {@code table} and
     *     {@code parent}.
     *
     * @param blueprint the checked blueprint, never {@code null}
     * @param connection the connection the statements ran on, never {@code null}
     * @return the queries, never {@code null}, may be empty
     */
    public List<SchemaQuery> compileForeignKeyChecks(Blueprint blueprint, Connection connection) {
        return Collections.emptyList();
    }

    /**
     * Orders the tables so that dropping them one after the other respects foreign keys.
     *
     * @implSpec The base returns the tables unchanged: a grammar whose drop is one statement, or
     *     whose foreign keys do not block a drop, needs no order.
     *
     * @param connection the connection the tables were listed on, never {@code null}
     * @param tables the tables to drop, never {@code null}
     * @return the tables in drop order, never {@code null}; the input order when no order exists
     */
    public List<TableInfo> sortForDrop(Connection connection, List<TableInfo> tables) {
        return tables;
    }

    /**
     * Returns the statements that drop the given tables.
     *
     * <p>More than one statement means they must run together in one transaction.
     *
     * @implSpec The base refuses the command.
     *
     * @param tables the tables to drop, never {@code null}, as listed by {@link #compileTables}
     * @return the statements in order, never {@code null}; empty if {@code tables} is empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    public List<String> compileDropAllTables(List<TableInfo> tables) {
        throw unsupported("Dropping all tables");
    }

    /**
     * Returns the statements that drop the given views.
     *
     * <p>More than one statement means they must run together in one transaction.
     *
     * @implSpec The base refuses the command.
     *
     * @param views the views to drop, never {@code null}, as listed by {@link #compileViews}
     * @return the statements in order, never {@code null}; empty if {@code views} is empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    public List<String> compileDropAllViews(List<ViewInfo> views) {
        throw unsupported("Dropping all views");
    }

    /**
     * Returns the statement that creates a database.
     *
     * @implSpec The base refuses the command.
     *
     * @param connection the connection the SQL will run on, never {@code null}; a grammar may read
     *     the server version through it
     * @param name the database name, never {@code null}
     * @param template the database to copy, or {@code null} for an empty one
     * @param strategy how to copy the template, or {@code null} for the server's default
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the command, or the
     *     server is too old for the strategy
     */
    public String compileCreateDatabase(Connection connection, String name, String template,
            CopyStrategy strategy) {
        throw unsupported("Creating a database");
    }

    /**
     * Returns the statement that drops a database if it exists.
     *
     * @implSpec The base refuses the command.
     *
     * @param connection the connection the SQL will run on, never {@code null}; a grammar may read
     *     the server version through it
     * @param name the database name, never {@code null}
     * @param force {@code true} to close the sessions that use the database first
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the command, or the
     *     server is too old for {@code force}
     */
    public String compileDropDatabaseIfExists(Connection connection, String name, boolean force) {
        throw unsupported("Dropping a database");
    }

    /**
     * Returns the query that tells whether a database exists.
     *
     * <p>The query returns at least one row when the database exists and none when it does not.
     *
     * @implSpec The base refuses the query.
     *
     * @param name the database name, never {@code null}
     * @return the query, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the query
     */
    public SchemaQuery compileDatabaseExists(String name) {
        throw unsupported("Checking that a database exists");
    }

    /**
     * Tells whether the server accepts a {@link CopyStrategy} when it copies a template.
     *
     * @implSpec The base returns {@code false}.
     *
     * @param connection the connection to the server, never {@code null}
     * @return {@code true} if {@link #compileCreateDatabase} accepts a strategy
     */
    public boolean supportsCopyStrategy(Connection connection) {
        return false;
    }

    /**
     * Creates a database.
     *
     * @implSpec Runs the statement of {@link #compileCreateDatabase} on {@code connection}. A
     *     grammar whose database has no such statement overrides this method instead.
     *
     * @param connection the connection to run on, never {@code null}
     * @param name the database name, never {@code null}
     * @param template the database to copy, or {@code null} for an empty one
     * @param strategy how to copy the template, or {@code null} for the server's default
     * @throws UnsupportedOperationException if the grammar does not support the command
     * @throws org.aristonis.diwan.database.exception.QueryException if the statement fails
     */
    public void createDatabase(Connection connection, String name, String template,
            CopyStrategy strategy) {
        connection.statement(compileCreateDatabase(connection, name, template, strategy));
    }

    /**
     * Drops a database if it exists and tells whether it did.
     *
     * @implSpec Runs the query of {@link #compileDatabaseExists}, then the statement of
     *     {@link #compileDropDatabaseIfExists}, and returns the answer of the query. Another
     *     process may drop the database between the two, so a {@code true} answer is not proof
     *     that this call dropped it.
     *
     * @param connection the connection to run on, never {@code null}
     * @param name the database name, never {@code null}
     * @param force {@code true} to close the sessions that use the database first
     * @return {@code true} if the database existed
     * @throws UnsupportedOperationException if the grammar does not support the command
     * @throws org.aristonis.diwan.database.exception.QueryException if a statement fails
     */
    public boolean dropDatabaseIfExists(Connection connection, String name, boolean force) {
        SchemaQuery query = compileDatabaseExists(name);
        boolean isPresent = !connection.select(query.sql(), query.bindings()).isEmpty();
        connection.statement(compileDropDatabaseIfExists(connection, name, force));
        return isPresent;
    }
}
