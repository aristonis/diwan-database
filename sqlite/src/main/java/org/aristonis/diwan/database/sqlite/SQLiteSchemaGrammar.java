package org.aristonis.diwan.database.sqlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionCallback;
import org.aristonis.diwan.database.schema.Blueprint;
import org.aristonis.diwan.database.schema.ColumnDefinition;
import org.aristonis.diwan.database.schema.ColumnType;
import org.aristonis.diwan.database.schema.Command;
import org.aristonis.diwan.database.schema.ForeignKeyAction;
import org.aristonis.diwan.database.schema.ForeignKeyCheckException;
import org.aristonis.diwan.database.schema.ForeignKeyDefinition;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.SchemaGrammar;
import org.aristonis.diwan.database.schema.SchemaQuery;

/**
 * Writes SQLite SQL, following Laravel's {@code SQLiteGrammar}.
 *
 * <p>SQLite has no unsigned types and no column comments, so {@code unsigned()} and
 * {@code comment()} are ignored, as in Laravel. JSON is stored as text. An auto-increment column
 * becomes {@code integer primary key autoincrement}, which SQLite allows only when the table is
 * created.
 *
 * <p>Adding or dropping a primary or foreign key on an existing table rebuilds it, keeping its
 * rows, indexes and auto-increment counter; tables with features the rebuild cannot carry over,
 * such as CHECK constraints, triggers or generated columns, are refused instead. Renaming and
 * dropping columns run in place on SQLite 3.25 and 3.35 and later, and rebuild the table on older
 * versions.
 *
 * <p>Instances hold no state and are safe to share between threads.
 */
public class SQLiteSchemaGrammar extends SchemaGrammar {

    // Native ALTER TABLE forms; older versions, such as Android 9's 3.22, rebuild the table instead.
    private static final int[] RENAME_COLUMN_SINCE = {3, 25, 0};
    private static final int[] DROP_COLUMN_SINCE = {3, 35, 0};

    private static final String[] KEY_CHANGES = {Command.CHANGE, Command.PRIMARY, Command.FOREIGN, Command.DROP_PRIMARY,
        Command.DROP_FOREIGN};
    private static final String[] STATEFUL = {Command.CHANGE, Command.PRIMARY, Command.FOREIGN, Command.DROP_PRIMARY,
        Command.DROP_FOREIGN, Command.RENAME_INDEX, Command.DROP_COLUMN, Command.RENAME_COLUMN};

    // Laravel's SQLite default for year()->useCurrent().
    private static final String CURRENT_YEAR = "(CAST(strftime('%Y', 'now') AS INTEGER))";

    private final String versionOverride;

    /** Creates the grammar. */
    public SQLiteSchemaGrammar() {
        this(null);
    }

    /** Creates a grammar that acts as if the database had a given version, for tests. */
    SQLiteSchemaGrammar(String versionOverride) {
        this.versionOverride = versionOverride;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote A blueprint that changes keys, drops or renames columns or renames an index on an
     *     existing table is compiled against the table's current shape, read through
     *     {@code connection}; when SQLite cannot make a change in place, the table is rebuilt: a new
     *     table, the rows copied, the old one dropped, the new one renamed, the indexes and the
     *     auto-increment counter restored.
     */
    @Override
    public List<String> compile(Blueprint blueprint, Connection connection) {
        if (blueprint.isCreating() || !hasAny(blueprint, STATEFUL)) {
            return super.compile(blueprint, connection);
        }
        return new SQLiteTableRebuild(this, blueprint, connection).compile();
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
        boolean hasAutoIncrement = false;
        for (ColumnDefinition column : blueprint.columns()) {
            hasAutoIncrement |= column.isAutoIncrement();
        }
        for (Command key : blueprint.commands()) {
            if (Command.PRIMARY.equals(key.name())) {
                if (hasAutoIncrement) {
                    throw new IllegalArgumentException("Table " + blueprint.table()
                            + " has an auto-increment column, which is its primary key already");
                }
                columns.append(", primary key (").append(columnize(key.strings(Command.COLUMNS))).append(')');
            }
        }
        for (Command key : blueprint.commands()) {
            if (Command.FOREIGN.equals(key.name())) {
                ForeignKeyDefinition definition = key.foreignKey();
                columns.append(", ").append(foreignKeySql(definition.columns(), definition.referencedTable(),
                        definition.referencedColumns(),
                        definition.deleteAction().map(ForeignKeyAction::sql).orElse(null),
                        definition.updateAction().map(ForeignKeyAction::sql).orElse(null)));
            }
        }
        return "create table " + wrapTable(blueprint.table()) + " (" + columns + ")";
    }

    /**
     * {@inheritDoc}
     *
     * @return no statement on a new table, whose create statement holds the key; an existing table
     *     is rebuilt by {@link #compile(Blueprint, Connection)} instead
     */
    @Override
    protected List<String> compilePrimary(Blueprint blueprint, Command command, Connection connection) {
        return Collections.emptyList();
    }

    /**
     * {@inheritDoc}
     *
     * @return no statement on a new table, whose create statement holds the key; an existing table
     *     is rebuilt by {@link #compile(Blueprint, Connection)} instead
     */
    @Override
    protected List<String> compileForeign(Blueprint blueprint, Command command, Connection connection) {
        return Collections.emptyList();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code drop index <index>}
     */
    @Override
    protected String compileDropIndex(Blueprint blueprint, Command command, Connection connection) {
        return "drop index " + wrap(command.string(Command.INDEX));
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
     * @return the primary key first, named {@code primary}, then every other index in SQLite's
     *     order; a unique constraint written into the table appears under SQLite's own name
     */
    @Override
    public List<IndexInfo> indexes(Connection connection, String table) {
        List<IndexInfo> found = new ArrayList<>();
        List<String> key = new ArrayList<>();
        for (Map<String, Object> row : connection.select(
                "select name from pragma_table_info(?) where pk > 0 order by pk", table)) {
            key.add(row.get("name").toString());
        }
        if (!key.isEmpty()) {
            found.add(new IndexInfo("primary", key, true, true));
        }
        for (Map<String, Object> row : connection.select(
                "select name, \"unique\", origin from pragma_index_list(?) order by seq", table)) {
            if ("pk".equals(String.valueOf(row.get("origin")))) {
                continue;
            }
            String name = row.get("name").toString();
            List<String> columns = new ArrayList<>();
            for (Map<String, Object> part : connection.select(
                    "select name from pragma_index_info(?) order by seqno", name)) {
                columns.add(String.valueOf(part.get("name")));
            }
            found.add(new IndexInfo(name, columns, SQLiteTableState.number(row.get("unique")) == 1, false));
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * {@inheritDoc}
     *
     * @return the keys in SQLite's order, each with its columns in key order
     */
    @Override
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String table) {
        List<ForeignKeyInfo> found = new ArrayList<>();
        long current = -1;
        List<String> from = new ArrayList<>();
        List<String> to = new ArrayList<>();
        Map<String, Object> head = null;
        for (Map<String, Object> row : connection.select("select id, \"table\", \"from\", \"to\", on_update,"
                + " on_delete from pragma_foreign_key_list(?) order by id, seq", table)) {
            long id = SQLiteTableState.number(row.get("id"));
            if (id != current && head != null) {
                found.add(keyInfo(head, from, to));
                from = new ArrayList<>();
                to = new ArrayList<>();
            }
            current = id;
            head = row;
            from.add(String.valueOf(row.get("from")));
            to.add(String.valueOf(row.get("to")));
        }
        if (head != null) {
            found.add(keyInfo(head, from, to));
        }
        return Collections.unmodifiableList(found);
    }

    private static ForeignKeyInfo keyInfo(Map<String, Object> row, List<String> from, List<String> to) {
        return new ForeignKeyInfo(from, String.valueOf(row.get("table")), to,
                String.valueOf(row.get("on_update")).toLowerCase(Locale.ROOT),
                String.valueOf(row.get("on_delete")).toLowerCase(Locale.ROOT));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Holds one physical connection, switches {@code PRAGMA foreign_keys} off before the
     *     transaction starts, since SQLite ignores the switch inside one, and switches it back on
     *     after the transaction ends. If switching it back fails, the work stays committed and the
     *     session is discarded: a driver that can abort the connection makes the pool drop it.
     */
    @Override
    public <T> T withoutForeignKeyConstraints(Connection connection, TransactionCallback<T> work) {
        Objects.requireNonNull(work, "work");
        if (connection.transactionLevel() > 0) {
            throw new IllegalStateException("Foreign key enforcement can only be switched off outside a transaction");
        }
        return connection.session(session -> {
            boolean wasOn = isForeignKeysOn(session);
            if (wasOn) {
                session.statement("pragma foreign_keys = off");
            }
            T result;
            try {
                result = session.transaction(work);
            } catch (RuntimeException | Error failure) {
                if (wasOn) {
                    try {
                        session.statement("pragma foreign_keys = on");
                    } catch (RuntimeException restoreFailure) {
                        failure.addSuppressed(restoreFailure);
                    }
                }
                throw failure;
            }
            if (wasOn) {
                try {
                    session.statement("pragma foreign_keys = on");
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("The work was committed, but foreign key enforcement could not"
                            + " be switched back on; the database connection was discarded", failure);
                }
            }
            return result;
        });
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Runs {@code pragma_foreign_key_check} over the whole database.
     */
    @Override
    public void checkForeignKeys(Connection connection) {
        List<Map<String, Object>> rows = connection.select("select \"table\", parent from pragma_foreign_key_check");
        if (!rows.isEmpty()) {
            throw new ForeignKeyCheckException(String.valueOf(rows.get(0).get("table")),
                    String.valueOf(rows.get(0).get("parent")), rows.size());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when the blueprint changes columns or keys of an existing table, or drops or renames
     *     columns, which may need a rebuild on older SQLite versions
     */
    @Override
    protected boolean requiresForeignKeysOff(Blueprint blueprint) {
        return !blueprint.isCreating() && (hasAny(blueprint, KEY_CHANGES)
                || hasAny(blueprint, new String[] {Command.DROP_COLUMN, Command.RENAME_COLUMN}));
    }

    /**
     * {@inheritDoc}
     *
     * @return a {@code pragma_foreign_key_check} of the changed table and of every table that points
     *     at it, when the blueprint may have rebuilt the table
     */
    @Override
    protected List<SchemaQuery> compileForeignKeyChecks(Blueprint blueprint, Connection connection) {
        if (!requiresForeignKeysOff(blueprint)) {
            return Collections.emptyList();
        }
        List<String> tables = new ArrayList<>();
        tables.add(blueprint.table());
        for (String child : referencingTables(connection, blueprint.table())) {
            if (!child.equalsIgnoreCase(blueprint.table())) {
                tables.add(child);
            }
        }
        List<SchemaQuery> checks = new ArrayList<>(tables.size());
        for (String table : tables) {
            checks.add(new SchemaQuery("select \"table\", parent from pragma_foreign_key_check(?)",
                    Collections.singletonList(table)));
        }
        return checks;
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
     * @return Laravel's SQLite type for the column; an enum is {@code varchar} with a check on its
     *     values, and {@code set} is refused, since SQLite has nothing like it
     */
    @Override
    protected String type(ColumnDefinition column) {
        switch (column.type()) {
            case STRING:
            case CHAR:
            case UUID:
            case IP_ADDRESS:
            case MAC_ADDRESS:
                return "varchar";
            case TEXT:
            case TINY_TEXT:
            case MEDIUM_TEXT:
            case LONG_TEXT:
            case JSON:
            case JSONB:
                return "text";
            case INTEGER:
            case BIG_INTEGER:
            case TINY_INTEGER:
            case SMALL_INTEGER:
            case MEDIUM_INTEGER:
            case YEAR:
                return "integer";
            case BOOLEAN:
                return "tinyint(1)";
            case DECIMAL:
                return "numeric";
            case FLOAT:
                return "float";
            case DOUBLE:
                return "double";
            case DATE:
                return "date";
            case TIMESTAMP:
            case TIMESTAMP_TZ:
            case DATE_TIME:
            case DATE_TIME_TZ:
                return "datetime";
            case TIME:
            case TIME_TZ:
                return "time";
            case BINARY:
                return "blob";
            case ENUM:
                return "varchar " + enumCheck(column.name(), column.allowed());
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
        } else if (column.type() == ColumnType.YEAR && column.usesCurrent()) {
            sql.append(" default ").append(CURRENT_YEAR);
        }
        return sql.toString();
    }

    /** Writes an enum's check exactly as Laravel does; the table rebuild reads it back in this form. */
    String enumCheck(String column, List<String> allowed) {
        StringBuilder values = new StringBuilder();
        for (String value : allowed) {
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append(literal(value));
        }
        return "check (" + wrap(column) + " in (" + values + "))";
    }

    String typeOf(ColumnDefinition column) {
        return type(column);
    }

    String modifiersOf(Blueprint blueprint, ColumnDefinition column) {
        return modifiers(blueprint, column);
    }

    String quote(String name) {
        return wrap(name);
    }

    List<String> compileOne(Blueprint blueprint, Command command, Connection connection) {
        return compileCommand(blueprint, command, connection);
    }

    String foreignKeySql(List<String> columns, String table, List<String> references, String onDelete,
            String onUpdate) {
        StringBuilder sql = new StringBuilder("foreign key (").append(columnize(columns)).append(") references ")
                .append(wrapTable(table)).append(" (").append(columnize(references)).append(')');
        if (onDelete != null && !"no action".equalsIgnoreCase(onDelete)) {
            sql.append(" on delete ").append(onDelete.toLowerCase(Locale.ROOT));
        }
        if (onUpdate != null && !"no action".equalsIgnoreCase(onUpdate)) {
            sql.append(" on update ").append(onUpdate.toLowerCase(Locale.ROOT));
        }
        return sql.toString();
    }

    boolean isForeignKeysOn(Connection connection) {
        return connection.scalar("pragma foreign_keys").map(SQLiteTableState::number).orElse(0L) == 1;
    }

    List<String> referencingTables(Connection connection, String table) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> row : connection.select("select distinct m.name as name from sqlite_master m,"
                + " pragma_foreign_key_list(m.name) f where m.type = 'table' and lower(f.\"table\") = lower(?)",
                table)) {
            found.add(row.get("name").toString());
        }
        return found;
    }

    Set<String> referencedColumns(Connection connection, String table) {
        Set<String> found = new HashSet<>();
        for (Map<String, Object> row : connection.select("select f.\"to\" as name from sqlite_master m,"
                + " pragma_foreign_key_list(m.name) f where m.type = 'table' and lower(f.\"table\") = lower(?)"
                + " and lower(m.name) <> lower(?)", table, table)) {
            Object name = row.get("name");
            if (name != null) {
                found.add(name.toString().toLowerCase(Locale.ROOT));
            }
        }
        return found;
    }

    boolean supportsRenameColumn(Connection connection) {
        return atLeast(version(connection), RENAME_COLUMN_SINCE);
    }

    boolean supportsDropColumn(Connection connection) {
        return atLeast(version(connection), DROP_COLUMN_SINCE);
    }

    private String version(Connection connection) {
        if (versionOverride != null) {
            return versionOverride;
        }
        return connection.scalar("select sqlite_version()").map(Object::toString).orElse("0");
    }

    static boolean atLeast(String version, int[] wanted) {
        String[] parts = version.trim().split("\\.");
        for (int i = 0; i < wanted.length; i++) {
            int part = i < parts.length ? parseOrZero(parts[i]) : 0;
            if (part != wanted[i]) {
                return part > wanted[i];
            }
        }
        return true;
    }

    private static int parseOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasAny(Blueprint blueprint, String[] names) {
        for (Command command : blueprint.commands()) {
            for (String name : names) {
                if (name.equals(command.name())) {
                    return true;
                }
            }
        }
        return false;
    }
}
