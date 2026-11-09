package org.aristonis.diwan.database.sqlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.ColumnGeneration;
import org.aristonis.diwan.database.schema.ColumnInfo;
import org.aristonis.diwan.database.schema.DatabaseVersion;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.TableInfo;
import org.aristonis.diwan.database.schema.grammar.SchemaQuery;

/**
 * Reads SQLite's catalog for {@link SQLiteSchemaGrammar}: the indexes, foreign keys, tables and views
 * of a database, and the queries that list them. It is kept apart so the grammar holds only the
 * statements that change a schema.
 *
 * <p>Instances hold no state beyond the version function and are safe to share between threads.
 */
final class SQLiteSchemaInspection {

    private static final int[] TABLE_LIST_SINCE = {3, 37, 0};
    private static final String ANDROID_METADATA = "android_metadata";

    // Names a virtual table v owns, from the suffixes of FTS3 to FTS5 and R-Tree; v is the alias of the
    // virtual table in the sqlite_master form of compileTables.
    private static final String SHADOW_NAMES = "(v.name || '_data', v.name || '_idx', v.name || '_content',"
            + " v.name || '_docsize', v.name || '_config', v.name || '_segments', v.name || '_segdir',"
            + " v.name || '_stat', v.name || '_node', v.name || '_rowid', v.name || '_parent')";

    private static final int[] TABLE_XINFO_SINCE = {3, 26, 0};
    private static final String COLUMNS_SELECT = "select name, type, \"notnull\", dflt_value, pk, ";
    private static final String XINFO_COLUMNS = COLUMNS_SELECT + "hidden from pragma_table_xinfo(?) order by cid";
    private static final String INFO_COLUMNS = COLUMNS_SELECT + "0 as hidden from pragma_table_info(?) order by cid";
    private static final String KEY_COLUMNS = "select name, pk from pragma_table_info(?)";
    private static final long HIDDEN_VIRTUAL_TABLE = 1;
    private static final long HIDDEN_VIRTUAL = 2;
    private static final long HIDDEN_STORED = 3;
    private static final String AUTO_INCREMENT_TYPE = "integer";

    private final Function<Connection, String> version;

    /**
     * Creates the inspection.
     *
     * @param version reads the SQLite version of a connection, as {@code major.minor.patch}
     */
    SQLiteSchemaInspection(Function<Connection, String> version) {
        this.version = version;
    }

    /**
     * Lists the columns of a table or view in column order. Below SQLite 3.26 the hidden-column
     * information is missing, which is harmless: generated columns need 3.31.
     */
    List<ColumnInfo> columns(Connection connection, String table) {
        String sql = DatabaseVersion.atLeast(version.apply(connection), TABLE_XINFO_SINCE)
                ? XINFO_COLUMNS : INFO_COLUMNS;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : connection.select(sql, table)) {
            if (SQLiteTableState.number(row.get("hidden")) == HIDDEN_VIRTUAL_TABLE) {
                continue;
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        boolean isOnlyKey = keyColumns(rows).size() == 1;
        SQLiteColumnDefinitions definitions = SQLiteColumnDefinitions.read(connection, table);
        List<ColumnInfo> found = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            found.add(columnInfo(row, isOnlyKey, definitions));
        }
        return Collections.unmodifiableList(found);
    }

    // The primary key columns in key order, from rows that carry name and pk.
    private static List<String> keyColumns(List<Map<String, Object>> rows) {
        List<Map<String, Object>> keyed = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (SQLiteTableState.number(row.get("pk")) > 0) {
                keyed.add(row);
            }
        }
        keyed.sort(Comparator.comparingLong(row -> SQLiteTableState.number(row.get("pk"))));
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : keyed) {
            names.add(row.get("name").toString());
        }
        return names;
    }

    private static ColumnInfo columnInfo(Map<String, Object> row, boolean isOnlyKey,
            SQLiteColumnDefinitions definitions) {
        String name = row.get("name").toString();
        Object declared = row.get("type");
        String type = declared == null ? "" : declared.toString().toLowerCase(Locale.ROOT);
        int paren = type.indexOf('(');
        String typeName = (paren < 0 ? type : type.substring(0, paren)).trim();
        long hidden = SQLiteTableState.number(row.get("hidden"));
        boolean isGenerated = hidden == HIDDEN_VIRTUAL || hidden == HIDDEN_STORED;
        ColumnGeneration generation = null;
        if (isGenerated) {
            String expression = definitions.generation(name);
            generation = new ColumnGeneration(hidden == HIDDEN_STORED, expression == null ? "" : expression);
        }
        Object defaultValue = row.get("dflt_value");
        boolean isAutoIncrement = isOnlyKey && SQLiteTableState.number(row.get("pk")) > 0
                && AUTO_INCREMENT_TYPE.equals(type);
        return new ColumnInfo(name, typeName, type, definitions.collation(name),
                SQLiteTableState.number(row.get("notnull")) == 0,
                isGenerated || defaultValue == null ? null : defaultValue.toString(), isAutoIncrement, null,
                generation);
    }

    List<IndexInfo> indexes(Connection connection, String table) {
        List<IndexInfo> found = new ArrayList<>();
        List<String> key = keyColumns(connection.select(KEY_COLUMNS, table));
        if (!key.isEmpty()) {
            found.add(new IndexInfo("primary", key, true, true));
        }
        for (SQLiteIndexReader.Entry entry : SQLiteIndexReader.read(connection, table)) {
            if ("pk".equals(entry.origin)) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String column : entry.columns) {
                columns.add(String.valueOf(column));
            }
            found.add(new IndexInfo(entry.name, columns, entry.isUnique, false, entry.orders, entry.collations,
                    entry.where));
        }
        return Collections.unmodifiableList(found);
    }

    List<ForeignKeyInfo> foreignKeys(Connection connection, String table) {
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

    SchemaQuery compileTableExists(Connection connection, String table) {
        return new SchemaQuery("select count(*) from sqlite_master where type = 'table' and name = ?",
                Collections.singletonList(table));
    }

    SchemaQuery compileColumnNames(Connection connection, String table) {
        return new SchemaQuery("select name from pragma_table_info(?)", Collections.singletonList(table));
    }

    SchemaQuery compileTables(Connection connection) {
        if (DatabaseVersion.atLeast(version.apply(connection), TABLE_LIST_SINCE)) {
            return new SchemaQuery("select name, schema from pragma_table_list where schema = 'main'"
                    + " and type in ('table', 'virtual') and name not like 'sqlite\\_%' escape '\\' and name <> '" + ANDROID_METADATA + "'"
                    + " order by schema, name", Collections.emptyList());
        }
        return new SchemaQuery("select name, 'main' as schema from sqlite_master as t where type = 'table'"
                + " and name not like 'sqlite\\_%' escape '\\' and name <> '" + ANDROID_METADATA + "'"
                + " and not exists (select 1 from sqlite_master as v where v.sql like 'create virtual table%'"
                + " and t.name in " + SHADOW_NAMES + ") order by name", Collections.emptyList());
    }

    SchemaQuery compileViews(Connection connection) {
        return new SchemaQuery("select name, 'main' as schema from sqlite_master where type = 'view'"
                + " order by name", Collections.emptyList());
    }

    boolean isInternalTable(TableInfo table) {
        return ANDROID_METADATA.equals(table.name()) || table.name().startsWith("sqlite_");
    }
}
