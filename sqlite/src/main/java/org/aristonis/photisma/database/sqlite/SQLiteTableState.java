package org.aristonis.photisma.database.sqlite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.aristonis.photisma.database.Connection;

/**
 * The shape of one SQLite table while a blueprint changes it: read once from the live table, then
 * updated by each command in order, so a rebuild copies what the table holds at that point, as
 * Laravel's {@code BlueprintState} does.
 *
 * <p>Each column remembers its name in the live table ({@code source}), which differs from its
 * name here only while a rename waits for the rebuild.
 */
final class SQLiteTableState {

    // Features a rebuild would silently lose; the rebuild refuses the table instead.
    private static final Pattern CHECK = Pattern.compile("\\bcheck\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLLATE = Pattern.compile("\\bcollate\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITHOUT_ROWID = Pattern.compile("\\bwithout\\s+rowid\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERATED = Pattern.compile("\\bgenerated\\s+always\\b|\\bas\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOINCREMENT = Pattern.compile("\\bautoincrement\\b", Pattern.CASE_INSENSITIVE);

    static final class Column {
        String name;
        String source;
        final String type;
        final String rest;

        Column(String name, String source, String type, String rest) {
            this.name = name;
            this.source = source;
            this.type = type;
            this.rest = rest;
        }
    }

    static final class Key {
        final List<String> columns;
        final String table;
        final List<String> references;
        final String onUpdate;
        final String onDelete;

        Key(List<String> columns, String table, List<String> references, String onUpdate, String onDelete) {
            this.columns = columns;
            this.table = table;
            this.references = references;
            this.onUpdate = onUpdate;
            this.onDelete = onDelete;
        }
    }

    static final class Index {
        String name;
        final List<String> columns;
        final boolean isUnique;

        Index(String name, List<String> columns, boolean isUnique) {
            this.name = name;
            this.columns = columns;
            this.isUnique = isUnique;
        }
    }

    final String table;
    final List<Column> columns = new ArrayList<>();
    final List<String> primaryKey = new ArrayList<>();
    final List<List<String>> uniqueConstraints = new ArrayList<>();
    final List<Key> keys = new ArrayList<>();
    final List<Index> indexes = new ArrayList<>();
    String autoIncrementColumn;
    long sequence = -1;

    private SQLiteTableState(String table) {
        this.table = table;
    }

    static SQLiteTableState load(Connection connection, String table) {
        Object sql = connection.scalar("select sql from sqlite_master where type = 'table' and name = ?", table)
                .orElseThrow(() -> new IllegalArgumentException("Table " + table + " does not exist; in pretend"
                        + " mode a table made earlier in the same run does not exist yet"));
        String tableSql = sql.toString();
        refuseUnsupported(connection, table, tableSql);
        SQLiteTableState state = new SQLiteTableState(table);
        state.readColumns(connection);
        if (state.primaryKey.size() == 1 && AUTOINCREMENT.matcher(tableSql).find()) {
            state.autoIncrementColumn = state.primaryKey.get(0);
            state.primaryKey.clear();
            state.sequence = readSequence(connection, table);
        }
        state.readKeys(connection);
        state.readIndexes(connection);
        return state;
    }

    private static void refuseUnsupported(Connection connection, String table, String tableSql) {
        String reason = null;
        if (CHECK.matcher(tableSql).find()) {
            reason = "a CHECK constraint";
        } else if (COLLATE.matcher(tableSql).find()) {
            reason = "a COLLATE clause";
        } else if (WITHOUT_ROWID.matcher(tableSql).find()) {
            reason = "WITHOUT ROWID";
        } else if (count(connection, "select count(*) from sqlite_master where type = 'trigger' and tbl_name = ?",
                table) > 0 || isNamedIn(connection, "trigger", table)) {
            reason = "a trigger that uses it";
        } else if (GENERATED.matcher(tableSql).find()) {
            reason = "a generated column";
        } else if (isNamedIn(connection, "view", table)) {
            reason = "a view that reads it";
        }
        if (reason != null) {
            throw new UnsupportedOperationException("Table " + table + " has " + reason
                    + ", which a SQLite table rebuild cannot carry over");
        }
    }

    // A whole-name match, so a view or trigger over "users" does not block a rebuild of "user".
    private static boolean isNamedIn(Connection connection, String type, String table) {
        Pattern name = Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(table) + "(?![A-Za-z0-9_$])",
                Pattern.CASE_INSENSITIVE);
        for (Map<String, Object> row : connection.select("select sql from sqlite_master where type = ?", type)) {
            Object sql = row.get("sql");
            if (sql != null && name.matcher(sql.toString()).find()) {
                return true;
            }
        }
        return false;
    }

    private void readColumns(Connection connection) {
        List<Map<String, Object>> rows = connection.select(
                "select name, type, \"notnull\", dflt_value, pk from pragma_table_info(?) order by cid", table);
        List<String[]> keyed = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = text(row.get("name"));
            StringBuilder rest = new StringBuilder();
            if (number(row.get("notnull")) == 1) {
                rest.append(" not null");
            }
            Object defaultSql = row.get("dflt_value");
            if (defaultSql != null) {
                rest.append(" default ").append(defaultSql);
            }
            columns.add(new Column(name, name, text(row.get("type")), rest.toString()));
            long position = number(row.get("pk"));
            if (position > 0) {
                keyed.add(new String[] {Long.toString(position), name});
            }
        }
        keyed.sort((a, b) -> Long.compare(Long.parseLong(a[0]), Long.parseLong(b[0])));
        for (String[] entry : keyed) {
            primaryKey.add(entry[1]);
        }
    }

    private void readKeys(Connection connection) {
        List<Map<String, Object>> rows = connection.select("select id, \"table\", \"from\", \"to\", on_update,"
                + " on_delete from pragma_foreign_key_list(?) order by id, seq", table);
        long current = -1;
        List<String> from = null;
        List<String> to = null;
        for (Map<String, Object> row : rows) {
            long id = number(row.get("id"));
            if (id != current) {
                current = id;
                from = new ArrayList<>();
                to = new ArrayList<>();
                keys.add(new Key(from, text(row.get("table")), to, text(row.get("on_update")),
                        text(row.get("on_delete"))));
            }
            from.add(text(row.get("from")));
            Object target = row.get("to");
            if (target == null) {
                throw new UnsupportedOperationException("A foreign key of table " + table
                        + " references the other table's primary key without naming its columns, which a SQLite"
                        + " table rebuild cannot carry over");
            }
            to.add(target.toString());
        }
    }

    private void readIndexes(Connection connection) {
        List<Map<String, Object>> rows = connection.select(
                "select name, \"unique\", origin, partial from pragma_index_list(?) order by seq", table);
        for (Map<String, Object> row : rows) {
            String origin = text(row.get("origin"));
            if ("pk".equals(origin)) {
                continue;
            }
            String name = text(row.get("name"));
            if (number(row.get("partial")) == 1) {
                throw new UnsupportedOperationException("Index " + name + " of table " + table
                        + " is partial, which a SQLite table rebuild cannot carry over");
            }
            List<String> indexed = indexColumns(connection, name);
            if ("u".equals(origin)) {
                uniqueConstraints.add(indexed);
            } else {
                indexes.add(new Index(name, indexed, number(row.get("unique")) == 1));
            }
        }
    }

    private List<String> indexColumns(Connection connection, String index) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : connection.select(
                "select name from pragma_index_info(?) order by seqno", index)) {
            Object name = row.get("name");
            if (name == null) {
                throw new UnsupportedOperationException("Index " + index + " of table " + table
                        + " is on an expression, which a SQLite table rebuild cannot carry over");
            }
            names.add(name.toString());
        }
        return names;
    }

    private static long readSequence(Connection connection, String table) {
        if (count(connection, "select count(*) from sqlite_master where type = 'table' and name = 'sqlite_sequence'")
                == 0) {
            return -1;
        }
        Optional<Object> seq = connection.scalar("select seq from sqlite_sequence where name = ?", table);
        return seq.isPresent() ? number(seq.get()) : -1;
    }

    void addColumn(String name, String type, String rest, boolean hasRun) {
        if (find(name) != null) {
            throw new IllegalArgumentException("Column " + name + " already exists in table " + table);
        }
        // A column not yet in the live table has nothing to copy; it takes its default.
        columns.add(new Column(name, hasRun ? name : null, type, rest));
    }

    void dropColumn(String name) {
        Column column = require(name);
        for (List<String> unique : uniqueConstraints) {
            refuseIfUsed(name, unique, "a unique constraint");
        }
        refuseIfUsed(name, primaryKey, "the primary key");
        if (autoIncrementColumn != null && autoIncrementColumn.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Column " + name + " is the primary key; drop it before the column");
        }
        columns.remove(column);
    }

    void renameColumn(String from, String to, boolean hasRun) {
        Column column = require(from);
        if (find(to) != null) {
            throw new IllegalArgumentException("Column " + to + " already exists in table " + table);
        }
        column.name = to;
        if (hasRun) {
            column.source = to;
        }
        replace(primaryKey, from, to);
        for (List<String> unique : uniqueConstraints) {
            replace(unique, from, to);
        }
        for (Key key : keys) {
            replace(key.columns, from, to);
        }
        for (Index index : indexes) {
            replace(index.columns, from, to);
        }
        if (autoIncrementColumn != null && autoIncrementColumn.equalsIgnoreCase(from)) {
            autoIncrementColumn = to;
        }
    }

    void setPrimaryKey(List<String> columnsOfKey) {
        if (!primaryKey.isEmpty() || autoIncrementColumn != null) {
            throw new IllegalArgumentException("Table " + table + " already has a primary key; drop it first");
        }
        for (String name : columnsOfKey) {
            primaryKey.add(require(name).name);
        }
    }

    void dropPrimaryKey() {
        if (primaryKey.isEmpty() && autoIncrementColumn == null) {
            throw new IllegalArgumentException("Table " + table + " has no primary key");
        }
        primaryKey.clear();
        autoIncrementColumn = null;
    }

    void addKey(Key key) {
        for (String name : key.columns) {
            require(name);
        }
        keys.add(key);
    }

    void dropKey(List<String> columnsOfKey) {
        for (Key key : keys) {
            if (sameNames(key.columns, columnsOfKey)) {
                keys.remove(key);
                return;
            }
        }
        throw new IllegalArgumentException("Table " + table + " has no foreign key on " + columnsOfKey);
    }

    void addIndex(String name, List<String> columnsOfIndex, boolean isUnique) {
        for (String column : columnsOfIndex) {
            require(column);
        }
        indexes.add(new Index(name, new ArrayList<>(columnsOfIndex), isUnique));
    }

    void dropIndex(String name) {
        indexes.remove(requireIndex(name));
    }

    Index requireIndex(String name) {
        for (Index index : indexes) {
            if (index.name.equalsIgnoreCase(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Table " + table + " has no index " + name
                + " that can be changed; a unique constraint written into the table cannot be");
    }

    /** Marks every column as present under its current name, as after a rebuild. */
    void settle() {
        for (Column column : columns) {
            column.source = column.name;
        }
    }

    private Column require(String name) {
        Column column = find(name);
        if (column == null) {
            throw new IllegalArgumentException("Table " + table + " has no column " + name);
        }
        return column;
    }

    private Column find(String name) {
        for (Column column : columns) {
            if (column.name.equalsIgnoreCase(name)) {
                return column;
            }
        }
        return null;
    }

    private void refuseIfUsed(String name, List<String> used, String what) {
        for (String column : used) {
            if (column.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Column " + name + " of table " + table + " is used by " + what
                        + "; drop it before the column");
            }
        }
    }

    private static void replace(List<String> names, String from, String to) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(from)) {
                names.set(i, to);
            }
        }
    }

    static boolean sameNames(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static long count(Connection connection, String sql, Object... bindings) {
        return connection.scalar(sql, bindings).map(SQLiteTableState::number).orElse(0L);
    }

    static long number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return value == null ? 0 : Long.parseLong(value.toString().trim());
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
