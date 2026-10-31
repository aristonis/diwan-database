package org.aristonis.photisma.database.sqlite;

import java.util.Collections;
import org.aristonis.photisma.database.Connection;
import org.aristonis.photisma.database.schema.Blueprint;
import org.aristonis.photisma.database.schema.ColumnDefinition;
import org.aristonis.photisma.database.schema.Command;
import org.aristonis.photisma.database.schema.SchemaGrammar;
import org.aristonis.photisma.database.schema.SchemaQuery;

/**
 * Writes SQLite SQL, following Laravel's {@code SQLiteGrammar}.
 *
 * <p>SQLite has no unsigned types and no column comments, so {@code unsigned()} and
 * {@code comment()} are ignored, as in Laravel. JSON is stored as text. An auto-increment column
 * becomes {@code integer primary key autoincrement}, which SQLite allows only when the table is
 * created.
 *
 * <p>Instances hold no state and are safe to share between threads.
 */
public class SQLiteSchemaGrammar extends SchemaGrammar {

    /** Creates the grammar. */
    public SQLiteSchemaGrammar() {
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code create table <table> (<columns>)}
     */
    @Override
    protected String compileCreate(Blueprint blueprint, Command command, Connection connection) {
        StringBuilder columns = new StringBuilder();
        for (ColumnDefinition column : blueprint.columns()) {
            if (columns.length() > 0) {
                columns.append(", ");
            }
            columns.append(column(blueprint, column));
        }
        return "create table " + wrapTable(blueprint.table()) + " (" + columns + ")";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> add column <column>}
     */
    @Override
    protected String compileAdd(Blueprint blueprint, Command command, Connection connection) {
        return "alter table " + wrapTable(blueprint.table()) + " add column " + column(blueprint, command.column());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> rename to <new name>}
     */
    @Override
    protected String compileRename(Blueprint blueprint, Command command, Connection connection) {
        return "alter table " + wrapTable(blueprint.table()) + " rename to " + wrapTable(command.string(Command.TO));
    }

    /**
     * {@inheritDoc}
     *
     * @return a count over {@code sqlite_master}
     */
    @Override
    public SchemaQuery compileTableExists(Connection connection, String table) {
        return new SchemaQuery("select count(*) from sqlite_master where type = 'table' and name = ?",
                Collections.singletonList(table));
    }

    /**
     * {@inheritDoc}
     *
     * @return a query over {@code pragma_table_info}
     */
    @Override
    public SchemaQuery compileColumnNames(Connection connection, String table) {
        return new SchemaQuery("select name from pragma_table_info(?)", Collections.singletonList(table));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Integer#MAX_VALUE}: SQLite does not limit identifier length
     */
    @Override
    public int maxIdentifierLength() {
        return Integer.MAX_VALUE;
    }

    /**
     * {@inheritDoc}
     *
     * @return Laravel's SQLite type for the column
     */
    @Override
    protected String type(ColumnDefinition column) {
        switch (column.type()) {
            case STRING:
                return "varchar";
            case TEXT:
            case JSON:
                return "text";
            case INTEGER:
            case BIG_INTEGER:
                return "integer";
            case BOOLEAN:
                return "tinyint(1)";
            case DECIMAL:
                return "numeric";
            case DATE:
                return "date";
            case TIMESTAMP:
                return "datetime";
            default:
                return super.type(column);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return the auto-increment key, {@code not null} unless nullable, and the default, in
     *     Laravel's order; collation and generated columns are not supported yet
     */
    @Override
    protected String modifiers(Blueprint blueprint, ColumnDefinition column) {
        StringBuilder sql = new StringBuilder();
        if (column.isAutoIncrement()) {
            sql.append(" primary key autoincrement");
        }
        if (!column.isNullable()) {
            sql.append(" not null");
        }
        if (column.defaultValue().isPresent()) {
            sql.append(" default ").append(literal(column.defaultValue().get()));
        }
        return sql.toString();
    }
}
