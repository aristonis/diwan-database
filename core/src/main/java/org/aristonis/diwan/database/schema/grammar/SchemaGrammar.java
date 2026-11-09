package org.aristonis.diwan.database.schema.grammar;

import org.aristonis.diwan.database.exception.ForeignKeyCheckException;
import org.aristonis.diwan.database.schema.Blueprint;
import org.aristonis.diwan.database.schema.Collation;
import org.aristonis.diwan.database.schema.ColumnDefinition;
import org.aristonis.diwan.database.Expression;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.TableInfo;
import org.aristonis.diwan.database.schema.ViewInfo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.query.Identifiers;

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
public abstract class SchemaGrammar {

    /** Longest identifier PostgreSQL keeps whole; a safe default for most databases. */
    private static final int DEFAULT_MAX_IDENTIFIER_LENGTH = 63;

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
     * Compiles {@link Command#UNIQUE}.
     *
     * @implSpec The base returns {@code create unique index <index> on <table> (<columns>)}, with {@code where} for a
     *     partial index.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     */
    protected String compileUnique(Blueprint blueprint, Command command, Connection connection) {
        return "create unique index " + wrap(command.string(Command.INDEX)) + " on " + wrapTable(blueprint.table())
                + " (" + IndexSql.parts(this, command) + ")"
                + IndexSql.where(this, command);
    }

    /**
     * Compiles {@link Command#PLAIN_INDEX}.
     *
     * @implSpec The base returns {@code create index <index> on <table> (<columns>)}, with {@code where} for a
     *     partial index.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     */
    protected String compileIndex(Blueprint blueprint, Command command, Connection connection) {
        return "create index " + wrap(command.string(Command.INDEX)) + " on " + wrapTable(blueprint.table())
                + " (" + IndexSql.parts(this, command) + ")"
                + IndexSql.where(this, command);
    }

    /**
     * Compiles {@link Command#DROP_INDEX} and {@link Command#DROP_UNIQUE}.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected String compileDropIndex(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#PRIMARY}: the primary key.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compilePrimary(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#FOREIGN}: a foreign key.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileForeign(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#DROP_PRIMARY}: dropping the primary key.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileDropPrimary(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#DROP_FOREIGN}: dropping a foreign key.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileDropForeign(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#RENAME_INDEX}: renaming an index.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileRenameIndex(Blueprint blueprint, Command command, Connection connection) {
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
     * Compiles {@link Command#CHECK}: adding a named table check.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileCheck(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#DROP_CHECK}: dropping a named table check.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileDropCheck(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Tells whether {@link #modifiers(Blueprint, ColumnDefinition)} writes column checks and
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
     * Lists the indexes of a table, the primary key included.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection to read with, never {@code null}
     * @param table the full table name, prefix included, never {@code null}
     * @return the indexes, unmodifiable, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public List<IndexInfo> indexes(Connection connection, String table) {
        throw unsupported("Listing indexes");
    }

    /**
     * Lists the foreign keys of a table.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection to read with, never {@code null}
     * @param table the full table name, prefix included, never {@code null}
     * @return the foreign keys, unmodifiable, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String table) {
        throw unsupported("Listing foreign keys");
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
     * Returns a query that counts the tables with a name.
     *
     * @implSpec The base refuses the check.
     *
     * @param connection the connection, never {@code null}; lets a grammar bind the current schema
     *     or database as well
     * @param table the full table name, prefix included, never {@code null}
     * @return the query, never {@code null}; its single value is the count
     * @throws UnsupportedOperationException if the grammar does not support the check
     */
    public SchemaQuery compileTableExists(Connection connection, String table) {
        throw unsupported("Checking for a table");
    }

    /**
     * Returns a query whose first column lists the column names of a table.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection, never {@code null}; lets a grammar bind the current schema
     *     or database as well
     * @param table the full table name, prefix included, never {@code null}
     * @return the query, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public SchemaQuery compileColumnNames(Connection connection, String table) {
        throw unsupported("Listing columns");
    }

    /**
     * Returns a query that lists the user tables, one row each with the columns {@code name} and
     * {@code schema}.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection, never {@code null}; lets a grammar choose by database version
     * @return the query, never {@code null}; ordered by schema, then name
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public SchemaQuery compileTables(Connection connection) {
        throw unsupported("Listing tables");
    }

    /**
     * Returns a query that lists the views, one row each with the columns {@code name} and
     * {@code schema}.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection, never {@code null}; lets a grammar choose by database version
     * @return the query, never {@code null}; ordered by schema, then name
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public SchemaQuery compileViews(Connection connection) {
        throw unsupported("Listing views");
    }

    /**
     * Tells whether a listed table belongs to the database engine or an extension, so that dropping
     * or emptying every table must leave it alone.
     *
     * @implSpec The base returns {@code false}.
     *
     * @param table the table as {@link #compileTables} lists it, never {@code null}
     * @return {@code true} if the table is internal
     */
    public boolean isInternalTable(TableInfo table) {
        return false;
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
     * Returns Laravel's short name of the database this grammar writes for, such as
     * {@code "sqlite"} or {@code "pgsql"}, so a migration can branch on it.
     *
     * @implSpec The base refuses; every grammar that ships with the library overrides it.
     *
     * @return the name, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not name its database
     */
    public String driverName() {
        throw unsupported("Naming the database");
    }

    /**
     * Returns the longest identifier the database keeps whole.
     *
     * @implSpec The base returns 63, PostgreSQL's limit.
     *
     * @return the length in characters
     */
    public int maxIdentifierLength() {
        return DEFAULT_MAX_IDENTIFIER_LENGTH;
    }

    /**
     * Returns the SQL for a column: its quoted name, type and modifiers.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param column the column, never {@code null}
     * @return the column SQL, never {@code null}
     * @throws UnsupportedOperationException if the column type is not supported
     */
    protected String column(Blueprint blueprint, ColumnDefinition column) {
        return wrap(column.name()) + " " + type(column) + modifiers(blueprint, column);
    }

    /**
     * Returns the SQL type of a column.
     *
     * @implSpec The base refuses every type.
     *
     * @param column the column, never {@code null}
     * @return the type, never {@code null}
     * @throws UnsupportedOperationException if the type is not supported
     */
    protected String type(ColumnDefinition column) {
        throw unsupported("The column type " + column.type());
    }

    /**
     * Returns the SQL after a column's type: nullability, default and the like.
     *
     * @implSpec The base returns an empty string.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param column the column, never {@code null}
     * @return the modifiers with a leading space each, never {@code null}, may be empty
     */
    protected String modifiers(Blueprint blueprint, ColumnDefinition column) {
        return "";
    }

    /**
     * Returns the database's name for a collation.
     *
     * @implSpec The base returns the name of a named collation and refuses a portable one. A
     *     grammar overrides it to map each portable collation it supports.
     *
     * @param collation the collation, never {@code null}
     * @return the database's collation name, never {@code null}
     * @throws UnsupportedOperationException if the grammar has no name for a portable collation
     */
    protected String collationName(Collation collation) {
        if (collation.isPortable()) {
            throw new UnsupportedOperationException(getClass().getSimpleName()
                    + " has no name for the portable collation " + collation.name());
        }
        return collation.name();
    }

    /**
     * Quotes an identifier.
     *
     * @implSpec The base uses double quotes and doubles any double quote inside the name, so the
     *     name cannot end the identifier.
     *
     * @param name the identifier, never {@code null}
     * @return the quoted identifier, never {@code null}
     */
    protected String wrap(String name) {
        return Identifiers.quote(name, '"');
    }

    /**
     * Quotes a table name.
     *
     * @implSpec The base calls {@link #wrap(String)}.
     *
     * @param table the table name with its prefix, never {@code null}
     * @return the quoted name, never {@code null}
     */
    protected String wrapTable(String table) {
        return wrap(table);
    }

    /**
     * Writes a default value as SQL.
     *
     * @implSpec Text is put in single quotes with any single quote doubled; {@code true} and
     *     {@code false} become {@code '1'} and {@code '0'}, as in Laravel; numbers are quoted in
     *     their plain decimal form; an {@link Expression} is written as given. A backslash is
     *     not escaped, because SQLite and PostgreSQL (with standard strings) read it as an
     *     ordinary character.
     *
     * @param value the value, never {@code null}, of a type {@link ColumnDefinition#defaultValue(Object)}
     *     keeps
     * @return the SQL literal, never {@code null}
     * @throws IllegalArgumentException if text holds a NUL character
     */
    protected String literal(Object value) {
        if (value instanceof Expression) {
            return ((Expression) value).sql();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "'1'" : "'0'";
        }
        String text = value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : value.toString();
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Strings with NUL characters cannot be escaped");
        }
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * Writes a boolean in an index condition.
     *
     * @implSpec The base returns {@code 1} for {@code true} and {@code 0} for {@code false}.
     *
     * @param value the value
     * @return the SQL literal, never {@code null}
     */
    protected String booleanLiteral(boolean value) {
        return value ? "1" : "0";
    }

    /**
     * Quotes and joins column names.
     *
     * @param columns the names, never {@code null}
     * @return the names, quoted and separated by {@code ", "}
     */
    protected String columnize(List<String> columns) {
        StringBuilder sql = new StringBuilder();
        for (String name : columns) {
            if (sql.length() > 0) {
                sql.append(", ");
            }
            sql.append(wrap(name));
        }
        return sql.toString();
    }

    /**
     * Builds the error for something this grammar does not support.
     *
     * @param what what is not supported, starting with a capital letter
     * @return the exception, never {@code null}
     */
    protected UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException(what + " is not supported by " + getClass().getSimpleName());
    }
}
