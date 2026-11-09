package org.aristonis.diwan.database.sqlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.aristonis.diwan.database.Connection;
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

    private final Function<Connection, String> version;

    /**
     * Creates the inspection.
     *
     * @param version reads the SQLite version of a connection, as {@code major.minor.patch}
     */
    SQLiteSchemaInspection(Function<Connection, String> version) {
        this.version = version;
    }

    List<IndexInfo> indexes(Connection connection, String table) {
        List<IndexInfo> found = new ArrayList<>();
        List<String> key = new ArrayList<>();
        for (Map<String, Object> row : connection.select(
                "select name from pragma_table_info(?) where pk > 0 order by pk", table)) {
            key.add(row.get("name").toString());
        }
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
                    + " and type in ('table', 'virtual') and name not like 'sqlite\\_%' escape '\\' and name <> 'android_metadata'"
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
