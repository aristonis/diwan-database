package org.aristonis.diwan.database.sqlite;

import org.aristonis.diwan.database.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the indexes of one SQLite table: the columns with their order and collation, and the
 * condition of a partial index.
 *
 * <p>A collation is reported only when it differs from the column's own, so a default index has an
 * empty collation for every column.
 */
final class SQLiteIndexReader {

    private static final String BINARY = "BINARY";

    /** One index as SQLite lists it. */
    static final class Entry {
        final String name;
        final boolean isUnique;
        final String origin;
        final boolean isPartial;
        // A null column name stands for an expression.
        final List<String> columns = new ArrayList<>();
        final List<String> orders = new ArrayList<>();
        final List<String> collations = new ArrayList<>();
        String where;
        // The stored create text; read only for an index on an expression, otherwise null.
        String sql;

        Entry(String name, boolean isUnique, String origin, boolean isPartial) {
            this.name = name;
            this.isUnique = isUnique;
            this.origin = origin;
            this.isPartial = isPartial;
        }

        boolean hasExpression() {
            return columns.contains(null);
        }
    }

    private final Connection connection;
    private final String table;
    private SQLiteColumnDefinitions definitions;

    private SQLiteIndexReader(Connection connection, String table) {
        this.connection = connection;
        this.table = table;
    }

    /**
     * Reads every index of a table, the ones that stand for a primary key or unique constraint too.
     *
     * @param connection the connection to read with
     * @param table the table name
     * @return the indexes in SQLite's order
     */
    static List<Entry> read(Connection connection, String table) {
        return new SQLiteIndexReader(connection, table).readAll();
    }

    private List<Entry> readAll() {
        List<Entry> found = new ArrayList<>();
        for (Map<String, Object> row : connection.select(
                "select name, \"unique\", origin, partial from pragma_index_list(?) order by seq", table)) {
            Entry entry = new Entry(String.valueOf(row.get("name")), SQLiteTableState.number(row.get("unique")) == 1,
                    String.valueOf(row.get("origin")), SQLiteTableState.number(row.get("partial")) == 1);
            readColumns(entry);
            if (entry.hasExpression()) {
                entry.sql = readSql(entry.name);
            }
            if (entry.isPartial) {
                entry.where = readWhere(entry.name);
            }
            found.add(entry);
        }
        return Collections.unmodifiableList(found);
    }

    private void readColumns(Entry entry) {
        for (Map<String, Object> part : connection.select(
                "select name, \"desc\", coll from pragma_index_xinfo(?) where key = 1 order by seqno", entry.name)) {
            Object name = part.get("name");
            entry.columns.add(name == null ? null : name.toString());
            entry.orders.add(SQLiteTableState.number(part.get("desc")) == 1 ? "desc" : "asc");
            entry.collations.add(collationOf(name == null ? null : name.toString(), part.get("coll")));
        }
    }

    private String collationOf(String column, Object reported) {
        if (column == null || reported == null || BINARY.equalsIgnoreCase(reported.toString())) {
            return "";
        }
        String own = definitions().collation(column);
        return reported.toString().equalsIgnoreCase(own) ? "" : reported.toString();
    }

    private SQLiteColumnDefinitions definitions() {
        if (definitions == null) {
            definitions = SQLiteColumnDefinitions.read(connection, table);
        }
        return definitions;
    }

    private String readSql(String index) {
        return connection.scalar("select sql from sqlite_master where type = 'index' and name = ?", index)
                .map(Object::toString).orElse(null);
    }

    // The text after the closing parenthesis of the column list, without the leading where.
    private String readWhere(String index) {
        Optional<Object> sql = connection.scalar(
                "select sql from sqlite_master where type = 'index' and name = ?", index);
        if (!sql.isPresent()) {
            return null;
        }
        String text = sql.get().toString();
        List<List<SQLiteSqlTokens.Token>> parts = SQLiteSqlTokens.parts(SQLiteSqlTokens.tokenize(text));
        List<SQLiteSqlTokens.Token> trailing = parts.get(parts.size() - 1);
        if (trailing.isEmpty() || !trailing.get(0).isWord("where")) {
            return null;
        }
        // Rebuilt from the tokens, so a comment in the stored text is not carried into a rebuild.
        StringBuilder where = new StringBuilder();
        for (SQLiteSqlTokens.Token token : trailing.subList(1, trailing.size())) {
            where.append(where.length() == 0 ? "" : " ").append(text, token.start, token.end);
        }
        return where.toString();
    }
}
