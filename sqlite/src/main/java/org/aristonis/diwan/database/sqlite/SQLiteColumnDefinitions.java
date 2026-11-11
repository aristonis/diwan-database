package org.aristonis.diwan.database.sqlite;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;

/**
 * What the {@code create table} text of one SQLite table says about its columns and the catalog
 * pragmas do not: the collation written on a column and the expression of a generated column.
 *
 * <p>The text is read once and split with {@link SQLiteSqlTokens}, so quoted names, comments and
 * nested parentheses do not mislead it. A view, or a table that does not exist, has no definitions,
 * and neither has a table whose text cannot be split. A temporary table is found in
 * {@code sqlite_temp_master}; the table name is matched without regard to letter case.
 * Column names are matched in lower case, with their quotes removed.
 */
final class SQLiteColumnDefinitions {

    private static final String CATALOG = "{catalog}";
    private static final String TABLE_SQL = "select sql from {catalog} where type = 'table' and name = ? collate nocase";

    private final Map<String, String> collations = new HashMap<>();
    private final Map<String, String> generations = new HashMap<>();

    private SQLiteColumnDefinitions() {
    }

    /**
     * Reads the definitions of a table.
     *
     * @param connection the connection to read with
     * @param table the table name
     * @return the definitions, empty when the table has no {@code create table} text or the text cannot be split
     */
    static SQLiteColumnDefinitions read(Connection connection, String table) {
        SQLiteColumnDefinitions definitions = new SQLiteColumnDefinitions();
        Optional<Object> sql = connection.scalar(TABLE_SQL.replace(CATALOG, "sqlite_master"), table);
        if (!sql.isPresent()) {
            sql = connection.scalar(TABLE_SQL.replace(CATALOG, "sqlite_temp_master"), table);
        }
        if (sql.isPresent()) {
            try {
                definitions.collect(sql.get().toString());
            } catch (IllegalArgumentException e) {
                return new SQLiteColumnDefinitions();
            }
        }
        return definitions;
    }

    /**
     * Refuses a table that is a temporary table of the connection, which a change cannot rebuild.
     *
     * @param connection the connection to look on
     * @param table the table name, matched without regard to letter case
     * @throws IllegalStateException if the table is a temporary table
     */
    static void refuseTemporary(Connection connection, String table) {
        if (connection.scalar(TABLE_SQL.replace(CATALOG, "sqlite_temp_master"), table).isPresent()) {
            throw new IllegalStateException("Changing a temporary table is not supported on SQLite");
        }
    }

    /**
     * Returns the collation written on a column.
     *
     * @param column the column name, in any case
     * @return the collation name, or {@code null} if the column has none of its own
     */
    String collation(String column) {
        return collations.get(column.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the expression of a generated column.
     *
     * @param column the column name, in any case
     * @return the text inside the parentheses after {@code as}, or {@code null} if the column is not generated
     */
    String generation(String column) {
        return generations.get(column.toLowerCase(Locale.ROOT));
    }

    private void collect(String sql) {
        List<List<SQLiteSqlTokens.Token>> parts = SQLiteSqlTokens.parts(SQLiteSqlTokens.tokenize(sql));
        parts.remove(parts.size() - 1);
        for (List<SQLiteSqlTokens.Token> part : parts) {
            if (!SQLiteSqlTokens.isTableConstraint(part)) {
                collectColumn(sql, part);
            }
        }
    }

    private void collectColumn(String sql, List<SQLiteSqlTokens.Token> part) {
        String name = part.get(0).text.toLowerCase(Locale.ROOT);
        int depth = 0;
        for (int i = 1; i + 1 < part.size(); i++) {
            SQLiteSqlTokens.Token token = part.get(i);
            depth += token.isPunct('(') ? 1 : token.isPunct(')') ? -1 : 0;
            if (depth != 0) {
                continue;
            }
            if (token.isWord("collate")) {
                collations.putIfAbsent(name, part.get(i + 1).text);
            } else if (token.isWord("as") && part.get(i + 1).isPunct('(')) {
                generations.putIfAbsent(name, expression(sql, part, i + 1));
            }
        }
    }

    // The raw text between the parenthesis at open and its partner, so nested parentheses are kept.
    private static String expression(String sql, List<SQLiteSqlTokens.Token> part, int open) {
        int depth = 0;
        for (int i = open; i < part.size(); i++) {
            SQLiteSqlTokens.Token token = part.get(i);
            depth += token.isPunct('(') ? 1 : token.isPunct(')') ? -1 : 0;
            if (depth == 0) {
                return sql.substring(part.get(open).end, token.start).trim();
            }
        }
        return sql.substring(part.get(open).end, part.get(part.size() - 1).end).trim();
    }
}
