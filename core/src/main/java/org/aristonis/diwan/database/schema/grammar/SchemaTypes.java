package org.aristonis.diwan.database.schema.grammar;

import java.math.BigDecimal;
import java.util.List;
import org.aristonis.diwan.database.Expression;
import org.aristonis.diwan.database.query.Identifiers;
import org.aristonis.diwan.database.schema.Blueprint;
import org.aristonis.diwan.database.schema.Collation;
import org.aristonis.diwan.database.schema.ColumnDefinition;

/**
 * Holds the quoting, literal and column-type hooks that every schema grammar layer builds on.
 *
 * <p>Split out of {@link SchemaGrammar} to keep that class small; it is not part of the public API.
 */
abstract class SchemaTypes {

    /** Longest identifier PostgreSQL keeps whole; a safe default for most databases. */
    private static final int DEFAULT_MAX_IDENTIFIER_LENGTH = 63;

    /** Creates the base of a grammar. */
    protected SchemaTypes() {
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
