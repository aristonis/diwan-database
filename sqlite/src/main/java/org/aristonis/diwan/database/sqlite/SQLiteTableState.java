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

/**
 * The shape of one SQLite table while a blueprint changes it: read once from the live table, then
 * updated by each command in order, so a rebuild copies what the table holds at that point, as
 * Laravel's {@code BlueprintState} does.
 *
 * <p>Each column remembers its name in the live table ({@code source}), which differs from its
 * name here only while a rename waits for the rebuild.
 */
final class SQLiteTableState {


    private static final Pattern LITERAL_DEFAULT = Pattern.compile(
            "'(?:[^']|'')*'|[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|null|true|false|current_time|current_date"
                    + "|current_timestamp|[xX]'[0-9A-Fa-f]*'", Pattern.CASE_INSENSITIVE);

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

    // What the table's own SQL says beyond pragma_table_info: enum values, carried clauses, table checks.
    private static final class Definition {
        final Map<String, List<String>> enums = new HashMap<>();
        final Map<String, List<String>> clauses = new HashMap<>();
        final Map<String, Boolean> generated = new HashMap<>();
        final List<TableCheck> checks = new ArrayList<>();
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
    final List<TableCheck> checks = new ArrayList<>();
    String autoIncrementColumn;
    long sequence = -1;

    private SQLiteTableState(String table) {
        this.table = table;
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
        state.readColumns(connection, grammar.supportsTableXinfo(connection));
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
            column.isGenerated = column.isGenerated || Boolean.TRUE.equals(definition.generated.get(key));
        }
        state.checks.addAll(definition.checks);
        if (state.primaryKey.size() == 1 && SQLiteSqlTokens.hasWord(tokens, "autoincrement")) {
            state.autoIncrementColumn = state.primaryKey.get(0);
            state.primaryKey.clear();
            state.sequence = readSequence(connection, table);
        }
        state.readKeys(connection);
        state.readIndexes(connection);
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
    // generated clauses are kept as text; a collation is refused.
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
            if (SQLiteSqlTokens.hasWord(part, "collate")) {
                throw cannotCarry(table, "a COLLATE clause");
            }
            if (SQLiteSqlTokens.isTableConstraint(part)) {
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
            int start = i;
            if (token.isWord("constraint") && i + 2 < part.size() && part.get(i + 2).isWord("check")) {
                i += 2;
            }
            if (part.get(i).isWord("check") && at(part, i + 1, '(')) {
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
            if (as < part.size() && part.get(as).isWord("as") && at(part, as + 1, '(')) {
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
        if (!at(part, i, '(') || i + 2 >= part.size()) {
            return -1;
        }
        SQLiteSqlTokens.Token owner = part.get(i + 1);
        boolean isOwner = (owner.kind == SQLiteSqlTokens.Kind.NAME || owner.kind == SQLiteSqlTokens.Kind.WORD)
                && owner.text.equalsIgnoreCase(column);
        if (!isOwner || !part.get(i + 2).isWord("in") || !at(part, i + 3, '(')) {
            return -1;
        }
        i += 4;
        while (i < part.size() && part.get(i).kind == SQLiteSqlTokens.Kind.STRING) {
            i++;
            if (at(part, i, ',')) {
                i++;
            } else {
                break;
            }
        }
        return at(part, i, ')') && at(part, i + 1, ')') ? i + 1 : -1;
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

    private static boolean at(List<SQLiteSqlTokens.Token> part, int i, char mark) {
        return i < part.size() && part.get(i).isPunct(mark);
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
    private void readColumns(Connection connection, boolean hasXinfo) {
        List<Map<String, Object>> rows = connection.select(hasXinfo
                ? "select name, type, \"notnull\", dflt_value, pk, hidden from pragma_table_xinfo(?) where hidden in"
                        + " (0, 2, 3) order by cid"
                : "select name, type, \"notnull\", dflt_value, pk, 0 as hidden from pragma_table_info(?) order by cid",
                table);
        List<String[]> keyed = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = text(row.get("name"));
            StringBuilder rest = new StringBuilder();
            if (number(row.get("notnull")) == 1) {
                rest.append(" not null");
            }
            Object defaultSql = row.get("dflt_value");
            if (defaultSql != null) {
                rest.append(" default ").append(defaultSql(defaultSql.toString()));
            }
            Column column = new Column(name, name, text(row.get("type")), rest.toString());
            column.isGenerated = number(row.get("hidden")) != 0;
            columns.add(column);
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

    // SQLite reports an expression default without the parentheses it needs, such as
    // CAST(strftime('%Y', 'now') AS INTEGER); a plain literal is written back as it came.
    private static String defaultSql(String sql) {
        return LITERAL_DEFAULT.matcher(sql).matches() ? sql : "(" + sql + ")";
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
    }

    // Constraint names and function names are skipped, so a column called "length" can still be renamed.
    private void refuseIfNames(String expression, String name) {
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
                throw new UnsupportedOperationException("Column " + name + " of table " + table + " is named in a"
                        + " check or generated column, which a SQLite table rebuild cannot rewrite; drop or change"
                        + " that first");
            }
        }
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
