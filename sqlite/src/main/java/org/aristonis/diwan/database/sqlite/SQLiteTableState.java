package org.aristonis.diwan.database.sqlite;

import org.aristonis.diwan.database.sqlite.SQLiteSchemaGrammar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;

/**
 * The shape of one SQLite table while a blueprint changes it: read once from the live table, then
 * updated by each command in order, so a rebuild copies what the table holds at that point, as
 * Laravel's {@code BlueprintState} does.
 *
 * <p>Each column remembers its name in the live table ({@code source}), which differs from its
 * name here only while a rename waits for the rebuild.
 */
final class SQLiteTableState {

    static final class Column {
        String name;
        String source;
        String type;
        String rest;
        // The values of an enum's check, written again under the column's current name on rebuild.
        List<String> allowed = Collections.emptyList();
        // Check and generated clauses read from the live table, written back as they were.
        String extras = "";
        // The SQL of this column's checks and generated expression, searched for column names.
        List<String> expressions = Collections.emptyList();
        boolean isGenerated;

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

    static final class TableCheck {
        final String name;
        final String sql;

        TableCheck(String name, String sql) {
            this.name = name;
            this.sql = sql;
        }
    }

    static final class Index {
        String name;
        final List<String> columns;
        final boolean isUnique;
        // One entry per column: "asc" or "desc", and a collation name or "" for the column's own.
        final List<String> orders;
        final List<String> collations;
        // The condition of a partial index without its leading where, or null.
        final String where;
        // The whole create text of an index on an expression, or null for an index on columns.
        final String rawSql;

        Index(String name, List<String> columns, boolean isUnique, List<String> orders, List<String> collations,
                String where) {
            this.name = name;
            this.columns = columns;
            this.isUnique = isUnique;
            this.orders = orders;
            this.collations = collations;
            this.where = where;
            this.rawSql = null;
        }

        /** An index on an expression, kept as the text that creates it; the text holds any unique or where. */
        Index(String name, String rawSql) {
            this.name = name;
            this.columns = Collections.emptyList();
            this.isUnique = false;
            this.orders = Collections.emptyList();
            this.collations = Collections.emptyList();
            this.where = null;
            this.rawSql = rawSql;
        }
    }

    final String table;
    final List<Column> columns = new ArrayList<>();
    final List<String> primaryKey = new ArrayList<>();
    final List<List<String>> uniqueConstraints = new ArrayList<>();
    final List<Key> keys = new ArrayList<>();
    final List<Index> indexes = new ArrayList<>();
    final List<TableCheck> checks = new ArrayList<>();
    String autoIncrementColumn;
    long sequence = -1;

    SQLiteTableState(String table) {
        this.table = table;
    }

    static SQLiteTableState load(Connection connection, String table, SQLiteSchemaGrammar grammar) {
        return SQLiteTableReader.load(connection, table, grammar);
    }

    void addColumn(String name, String type, List<String> allowed, String rest, boolean hasRun) {
        if (find(name) != null) {
            throw new IllegalArgumentException("Column " + name + " already exists in table " + table);
        }
        // A column not yet in the live table has nothing to copy; it takes its default.
        Column column = new Column(name, hasRun ? name : null, type, rest);
        column.allowed = allowed;
        columns.add(column);
    }

    void changeColumn(String name, String type, List<String> allowed, String rest) {
        Column column = require(name);
        if (autoIncrementColumn != null && autoIncrementColumn.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Column " + name + " is auto-increment and cannot be changed");
        }
        column.type = type;
        column.rest = rest;
        column.allowed = allowed;
        column.extras = "";
        column.expressions = Collections.emptyList();
        column.isGenerated = false;
    }

    /** Records the SQL of a column's checks and generated expression from its new definition. */
    void describe(String name, List<String> expressions, boolean isGenerated) {
        Column column = require(name);
        column.expressions = expressions;
        column.isGenerated = isGenerated;
    }

    void addCheck(String name, String sql) {
        if (findCheck(name) != null) {
            throw new IllegalArgumentException("Table " + table + " already has a check named " + name);
        }
        checks.add(new TableCheck(name, sql));
    }

    void dropCheck(String name) {
        TableCheck check = findCheck(name);
        if (check == null) {
            throw new IllegalArgumentException("Table " + table + " has no table check named " + name);
        }
        checks.remove(check);
    }

    private TableCheck findCheck(String name) {
        for (TableCheck check : checks) {
            if (check.name != null && check.name.equalsIgnoreCase(name)) {
                return check;
            }
        }
        return null;
    }

    /**
     * Refuses a rename or drop of a column that a carried check or generated expression names; the
     * rebuild writes those expressions back as text and cannot follow the column.
     */
    void refuseIfMentioned(String name, boolean isOwnAllowed) {
        for (Column column : columns) {
            boolean isOwn = column.name.equalsIgnoreCase(name);
            if (isOwnAllowed && isOwn) {
                continue;
            }
            for (String expression : column.expressions) {
                refuseIfNames(expression, name);
            }
        }
        for (TableCheck check : checks) {
            refuseIfNames(check.sql, name);
        }
        for (Index index : indexes) {
            if (index.rawSql != null && names(index.rawSql, name)) {
                throw new UnsupportedOperationException("Column " + name + " of table " + table + " is named in"
                        + " expression index " + index.name + ", which a SQLite table rebuild cannot rewrite;"
                        + " drop the index first");
            }
            if (index.where != null && names(index.where, name)) {
                throw new UnsupportedOperationException("Column " + name + " of table " + table + " is named in the"
                        + " condition of partial index " + index.name + ", which a SQLite table rebuild cannot"
                        + " rewrite; drop the index first");
            }
        }
    }

    // Constraint names and function names are skipped, so a column called "length" can still be renamed.
    private void refuseIfNames(String expression, String name) {
        if (names(expression, name)) {
            throw new UnsupportedOperationException("Column " + name + " of table " + table + " is named in a"
                    + " check or generated column, which a SQLite table rebuild cannot rewrite; drop or change"
                    + " that first");
        }
    }

    private boolean names(String expression, String name) {
        List<SQLiteSqlTokens.Token> tokens = SQLiteSqlTokens.tokenize(expression);
        for (int i = 0; i < tokens.size(); i++) {
            SQLiteSqlTokens.Token token = tokens.get(i);
            if (i > 0 && tokens.get(i - 1).isWord("constraint") || at(tokens, i + 1, '(')) {
                continue;
            }
            // The tokenizer keeps "t.col" as one word; the column is the part after the last dot.
            String text = token.kind == SQLiteSqlTokens.Kind.WORD
                    ? token.text.substring(token.text.lastIndexOf('.') + 1) : token.text;
            if ((token.kind == SQLiteSqlTokens.Kind.WORD || token.kind == SQLiteSqlTokens.Kind.NAME)
                    && text.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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

    void addIndex(Index index) {
        // An index on an expression names no column to check.
        for (String column : index.columns) {
            require(column);
        }
        indexes.add(index);
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

    static boolean at(List<SQLiteSqlTokens.Token> part, int i, char mark) {
        return i < part.size() && part.get(i).isPunct(mark);
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


    static long number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return value == null ? 0 : Long.parseLong(value.toString().trim());
    }
}
