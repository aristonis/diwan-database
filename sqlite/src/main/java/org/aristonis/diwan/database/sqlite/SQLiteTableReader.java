package org.aristonis.diwan.database.sqlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.sqlite.SQLiteTableState.Column;
import org.aristonis.diwan.database.sqlite.SQLiteTableState.Index;
import org.aristonis.diwan.database.sqlite.SQLiteTableState.Key;
import org.aristonis.diwan.database.sqlite.SQLiteTableState.TableCheck;

/**
 * Reads the shape of a live SQLite table into a {@link SQLiteTableState}: the pragma rows for columns,
 * keys and indexes, and the table's own SQL for enum checks, carried clauses and table checks. It is
 * split from the state class so that class holds only the edits a blueprint applies after the read.
 */
final class SQLiteTableReader {

    private static final Pattern LITERAL_DEFAULT = Pattern.compile(
            "'(?:[^']|'')*'|[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|null|true|false|current_time|current_date"
                    + "|current_timestamp|[xX]'[0-9A-Fa-f]*'", Pattern.CASE_INSENSITIVE);

    // What the table's own SQL says beyond pragma_table_info: enum values, carried clauses, table checks.
    private static final class Definition {
        final Map<String, List<String>> enums = new HashMap<>();
        final Map<String, List<String>> clauses = new HashMap<>();
        final Map<String, String> collations = new HashMap<>();
        final Map<String, Boolean> generated = new HashMap<>();
        final List<TableCheck> checks = new ArrayList<>();
    }

    private SQLiteTableReader() {
    }

    static SQLiteTableState load(Connection connection, String table, SQLiteSchemaGrammar grammar) {
        Object sql = connection.scalar("select sql from sqlite_master where type = 'table' and name = ?", table)
                .orElseThrow(() -> new IllegalArgumentException("Table " + table + " does not exist; in pretend"
                        + " mode a table made earlier in the same run does not exist yet"));
        String tableSql = sql.toString();
        refuseUnsupported(connection, table);
        List<SQLiteSqlTokens.Token> tokens = SQLiteSqlTokens.tokenize(tableSql);
        Definition definition = readDefinition(table, tableSql, tokens, grammar);
        SQLiteTableState state = new SQLiteTableState(table);
        readColumns(state, connection, grammar.supportsTableXinfo(connection));
        for (Column column : state.columns) {
            String key = column.name.toLowerCase(Locale.ROOT);
            List<String> allowed = definition.enums.get(key);
            if (allowed != null) {
                column.allowed = allowed;
            }
            List<String> clauses = definition.clauses.get(key);
            if (clauses != null) {
                StringBuilder extras = new StringBuilder();
                for (String clause : clauses) {
                    extras.append(' ').append(clause);
                }
                column.extras = extras.toString();
                column.expressions = clauses;
            }
            String collate = definition.collations.get(key);
            if (collate != null) {
                // Not an expression: a collation names no column, so a rename never has to search it.
                column.extras += " " + collate;
            }
            column.isGenerated = column.isGenerated || Boolean.TRUE.equals(definition.generated.get(key));
        }
        state.checks.addAll(definition.checks);
        if (state.primaryKey.size() == 1 && SQLiteSqlTokens.hasWord(tokens, "autoincrement")) {
            state.autoIncrementColumn = state.primaryKey.get(0);
            state.primaryKey.clear();
            state.sequence = readSequence(connection, table);
        }
        readKeys(state, connection);
        readIndexes(state, connection);
        return state;
    }

    private static void refuseUnsupported(Connection connection, String table) {
        String reason = null;
        if (count(connection, "select count(*) from sqlite_master where type = 'trigger' and tbl_name = ?",
                table) > 0 || isNamedIn(connection, "trigger", table)) {
            reason = "a trigger that uses it";
        } else if (isNamedIn(connection, "view", table)) {
            reason = "a view that reads it";
        }
        if (reason != null) {
            throw cannotCarry(table, reason);
        }
    }

    private static UnsupportedOperationException cannotCarry(String table, String reason) {
        return new UnsupportedOperationException("Table " + table + " has " + reason
                + ", which a SQLite table rebuild cannot carry over");
    }

    // Reads the table's own SQL, outside string literals. An enum check that belongs to its column and
    // reads back exactly as it would be written is kept as the enum's values; other checks and
    // generated clauses are kept as text; a column's own collation is kept, one in a table constraint is refused.
    private static Definition readDefinition(String table, String sql, List<SQLiteSqlTokens.Token> tokens,
            SQLiteSchemaGrammar grammar) {
        List<List<SQLiteSqlTokens.Token>> parts = SQLiteSqlTokens.parts(tokens);
        List<SQLiteSqlTokens.Token> trailing = parts.remove(parts.size() - 1);
        if (SQLiteSqlTokens.hasWord(trailing, "rowid")) {
            throw cannotCarry(table, "WITHOUT ROWID");
        }
        if (SQLiteSqlTokens.hasWord(trailing, "strict")) {
            throw cannotCarry(table, "STRICT typing");
        }
        Definition definition = new Definition();
        for (List<SQLiteSqlTokens.Token> part : parts) {
            if (SQLiteSqlTokens.isTableConstraint(part)) {
                if (SQLiteSqlTokens.hasWord(part, "collate")) {
                    throw cannotCarry(table, "a COLLATE clause");
                }
                readTableCheck(sql, part, definition);
            } else {
                readColumnClauses(sql, part, grammar, definition);
            }
        }
        return definition;
    }

    private static void readTableCheck(String sql, List<SQLiteSqlTokens.Token> part, Definition definition) {
        if (part.isEmpty() || !hasTopLevelCheck(part)) {
            return;
        }
        String name = part.get(0).isWord("constraint") && part.size() > 1 ? part.get(1).text : null;
        definition.checks.add(new TableCheck(name, sql.substring(part.get(0).start, part.get(part.size() - 1).end)));
    }

    private static boolean hasTopLevelCheck(List<SQLiteSqlTokens.Token> part) {
        int depth = 0;
        for (SQLiteSqlTokens.Token token : part) {
            if (token.isPunct('(')) {
                depth++;
            } else if (token.isPunct(')')) {
                depth--;
            } else if (depth == 0 && token.isWord("check")) {
                return true;
            }
        }
        return false;
    }

    private static void readColumnClauses(String sql, List<SQLiteSqlTokens.Token> part, SQLiteSchemaGrammar grammar,
            Definition definition) {
        String column = part.get(0).text;
        String key = column.toLowerCase(Locale.ROOT);
        List<String> clauses = new ArrayList<>();
        int depth = 0;
        for (int i = 1; i < part.size(); i++) {
            SQLiteSqlTokens.Token token = part.get(i);
            if (token.isPunct('(')) {
                depth++;
                continue;
            }
            if (token.isPunct(')')) {
                depth--;
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (token.isWord("collate") && i + 1 < part.size() && !part.get(i + 1).isPunct('(')
                    && !part.get(i + 1).isPunct(')')) {
                definition.collations.put(key, sql.substring(token.start, part.get(i + 1).end));
                i++;
                continue;
            }
            int start = i;
            if (token.isWord("constraint") && i + 2 < part.size() && part.get(i + 2).isWord("check")) {
                i += 2;
            }
            if (part.get(i).isWord("check") && SQLiteTableState.at(part, i + 1, '(')) {
                int end = closing(part, i + 1);
                List<String> values = start == i ? enumValues(part, i, enumCheckEnd(part, i, column)) : null;
                // The round trip uses the name as the check spells it, which may differ in case.
                if (values != null && !definition.enums.containsKey(key)
                        && sql.substring(part.get(i).start, part.get(end).end)
                                .equals(grammar.enumCheck(part.get(i + 2).text, values))) {
                    definition.enums.put(key, values);
                } else {
                    clauses.add(sql.substring(part.get(start).start, part.get(end).end));
                }
                i = end;
                continue;
            }
            i = start;
            // "as (" at the top of a column is a generated column; "as" inside a default, such as
            // cast(x as integer), is not.
            int as = token.isWord("generated") ? i + 2 : i;
            if (as < part.size() && part.get(as).isWord("as") && SQLiteTableState.at(part, as + 1, '(')) {
                int end = closing(part, as + 1);
                if (end + 1 < part.size() && (part.get(end + 1).isWord("stored") || part.get(end + 1).isWord("virtual"))) {
                    end++;
                }
                clauses.add(sql.substring(part.get(start).start, part.get(end).end));
                definition.generated.put(key, true);
                i = end;
            }
        }
        if (!clauses.isEmpty()) {
            definition.clauses.put(key, Collections.unmodifiableList(clauses));
        }
    }

    private static int closing(List<SQLiteSqlTokens.Token> part, int open) {
        int depth = 0;
        for (int i = open; i < part.size(); i++) {
            if (part.get(i).isPunct('(')) {
                depth++;
            } else if (part.get(i).isPunct(')') && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("The table definition has an unclosed parenthesis");
    }

    // The index of the closing parenthesis of check (<column> in (...)), or -1 when the shape differs.
    private static int enumCheckEnd(List<SQLiteSqlTokens.Token> part, int check, String column) {
        int i = check + 1;
        if (!SQLiteTableState.at(part, i, '(') || i + 2 >= part.size()) {
            return -1;
        }
        SQLiteSqlTokens.Token owner = part.get(i + 1);
        boolean isOwner = (owner.kind == SQLiteSqlTokens.Kind.NAME || owner.kind == SQLiteSqlTokens.Kind.WORD)
                && owner.text.equalsIgnoreCase(column);
        if (!isOwner || !part.get(i + 2).isWord("in") || !SQLiteTableState.at(part, i + 3, '(')) {
            return -1;
        }
        i += 4;
        while (i < part.size() && part.get(i).kind == SQLiteSqlTokens.Kind.STRING) {
            i++;
            if (SQLiteTableState.at(part, i, ',')) {
                i++;
            } else {
                break;
            }
        }
        return SQLiteTableState.at(part, i, ')') && SQLiteTableState.at(part, i + 1, ')') ? i + 1 : -1;
    }

    private static List<String> enumValues(List<SQLiteSqlTokens.Token> part, int check, int end) {
        if (end < 0) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (int i = check + 5; i < end - 1; i++) {
            if (part.get(i).kind == SQLiteSqlTokens.Kind.STRING) {
                values.add(part.get(i).text);
            }
        }
        return values.isEmpty() ? null : Collections.unmodifiableList(values);
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

    // pragma_table_info leaves generated columns out; table_xinfo marks them hidden 2 (virtual) or 3 (stored).
    private static void readColumns(SQLiteTableState state, Connection connection, boolean hasXinfo) {
        List<Map<String, Object>> rows = connection.select(hasXinfo
                ? "select name, type, \"notnull\", dflt_value, pk, hidden from pragma_table_xinfo(?) where hidden in"
                        + " (0, 2, 3) order by cid"
                : "select name, type, \"notnull\", dflt_value, pk, 0 as hidden from pragma_table_info(?) order by cid",
                state.table);
        List<String[]> keyed = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = text(row.get("name"));
            StringBuilder rest = new StringBuilder();
            if (SQLiteTableState.number(row.get("notnull")) == 1) {
                rest.append(" not null");
            }
            Object defaultSql = row.get("dflt_value");
            if (defaultSql != null) {
                rest.append(" default ").append(defaultSql(defaultSql.toString()));
            }
            Column column = new Column(name, name, text(row.get("type")), rest.toString());
            column.isGenerated = SQLiteTableState.number(row.get("hidden")) != 0;
            state.columns.add(column);
            long position = SQLiteTableState.number(row.get("pk"));
            if (position > 0) {
                keyed.add(new String[] {Long.toString(position), name});
            }
        }
        keyed.sort((a, b) -> Long.compare(Long.parseLong(a[0]), Long.parseLong(b[0])));
        for (String[] entry : keyed) {
            state.primaryKey.add(entry[1]);
        }
    }

    // SQLite reports an expression default without the parentheses it needs, such as
    // CAST(strftime('%Y', 'now') AS INTEGER); a plain literal is written back as it came.
    private static String defaultSql(String sql) {
        return LITERAL_DEFAULT.matcher(sql).matches() ? sql : "(" + sql + ")";
    }

    private static void readKeys(SQLiteTableState state, Connection connection) {
        List<Map<String, Object>> rows = connection.select("select id, \"table\", \"from\", \"to\", on_update,"
                + " on_delete from pragma_foreign_key_list(?) order by id, seq", state.table);
        long current = -1;
        List<String> from = null;
        List<String> to = null;
        for (Map<String, Object> row : rows) {
            long id = SQLiteTableState.number(row.get("id"));
            if (id != current) {
                current = id;
                from = new ArrayList<>();
                to = new ArrayList<>();
                state.keys.add(new Key(from, text(row.get("table")), to, text(row.get("on_update")),
                        text(row.get("on_delete"))));
            }
            from.add(text(row.get("from")));
            Object target = row.get("to");
            if (target == null) {
                throw new UnsupportedOperationException("A foreign key of table " + state.table
                        + " references the other table's primary key without naming its columns, which a SQLite"
                        + " table rebuild cannot carry over");
            }
            to.add(target.toString());
        }
    }

    private static void readIndexes(SQLiteTableState state, Connection connection) {
        for (SQLiteIndexReader.Entry entry : SQLiteIndexReader.read(connection, state.table)) {
            if ("pk".equals(entry.origin)) {
                continue;
            }
            if (entry.hasExpression() && entry.sql != null) {
                state.indexes.add(new Index(entry.name, entry.sql));
                continue;
            }
            if (entry.hasExpression()) {
                throw new UnsupportedOperationException("Index " + entry.name + " of table " + state.table
                        + " is on an expression, which a SQLite table rebuild cannot carry over");
            }
            if ("u".equals(entry.origin)) {
                state.uniqueConstraints.add(entry.columns);
            } else {
                state.indexes.add(new Index(entry.name, entry.columns, entry.isUnique, entry.orders, entry.collations,
                        entry.where));
            }
        }
    }

    private static long readSequence(Connection connection, String table) {
        if (count(connection, "select count(*) from sqlite_master where type = 'table' and name = 'sqlite_sequence'")
                == 0) {
            return -1;
        }
        Optional<Object> seq = connection.scalar("select seq from sqlite_sequence where name = ?", table);
        return seq.isPresent() ? SQLiteTableState.number(seq.get()) : -1;
    }

    private static long count(Connection connection, String sql, Object... bindings) {
        return connection.scalar(sql, bindings).map(SQLiteTableState::number).orElse(0L);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
