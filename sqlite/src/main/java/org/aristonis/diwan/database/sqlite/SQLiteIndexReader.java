package org.aristonis.diwan.database.sqlite;

import org.aristonis.diwan.database.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private Map<String, String> ownCollations;

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
        String own = ownCollations().get(column.toLowerCase(Locale.ROOT));
        return reported.toString().equalsIgnoreCase(own) ? "" : reported.toString();
    }

    // The collations written on the table's own columns, by lower-case column name.
    private Map<String, String> ownCollations() {
        if (ownCollations == null) {
            ownCollations = new HashMap<>();
            Optional<Object> sql = connection.scalar(
                    "select sql from sqlite_master where type = 'table' and name = ?", table);
            if (sql.isPresent()) {
                collectCollations(SQLiteSqlTokens.tokenize(sql.get().toString()));
            }
        }
        return ownCollations;
    }

    private void collectCollations(List<SQLiteSqlTokens.Token> tokens) {
        List<List<SQLiteSqlTokens.Token>> parts = SQLiteSqlTokens.parts(tokens);
        parts.remove(parts.size() - 1);
        for (List<SQLiteSqlTokens.Token> part : parts) {
            if (SQLiteSqlTokens.isTableConstraint(part)) {
                continue;
            }
            int depth = 0;
            for (int i = 1; i + 1 < part.size(); i++) {
                SQLiteSqlTokens.Token token = part.get(i);
                depth += token.isPunct('(') ? 1 : token.isPunct(')') ? -1 : 0;
                if (depth == 0 && token.isWord("collate")) {
                    ownCollations.put(part.get(0).text.toLowerCase(Locale.ROOT), part.get(i + 1).text);
                    break;
                }
            }
        }
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
