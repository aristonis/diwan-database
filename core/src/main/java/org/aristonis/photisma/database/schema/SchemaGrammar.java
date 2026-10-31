package org.aristonis.photisma.database.schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.aristonis.photisma.database.Connection;

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
     * @implSpec The base returns {@code create unique index <index> on <table> (<columns>)}.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     */
    protected String compileUnique(Blueprint blueprint, Command command, Connection connection) {
        return "create unique index " + wrap(command.string(Command.INDEX)) + " on " + wrapTable(blueprint.table())
                + " (" + columnize(command.strings(Command.COLUMNS)) + ")";
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
     * Quotes an identifier.
     *
     * @implSpec The base uses double quotes and doubles any double quote inside the name, so the
     *     name cannot end the identifier.
     *
     * @param name the identifier, never {@code null}
     * @return the quoted identifier, never {@code null}
     */
    protected String wrap(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
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
     *     their plain decimal form; an {@link Expression} is written as given.
     *
     * @param value the value, never {@code null}, of a type {@link ColumnDefinition#defaultValue(Object)}
     *     keeps
     * @return the SQL literal, never {@code null}
     */
    protected String literal(Object value) {
        if (value instanceof Expression) {
            return ((Expression) value).sql();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "'1'" : "'0'";
        }
        String text = value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : value.toString();
        return "'" + text.replace("'", "''") + "'";
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
