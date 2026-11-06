package org.aristonis.diwan.database.sqlite;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryGrammar;
import org.aristonis.diwan.database.query.Where;
import org.aristonis.diwan.database.values.Instants;

/**
 * Compiles queries into SQLite SQL, a hand port of Laravel's
 * {@code Illuminate\Database\Query\Grammars\SQLiteGrammar}.
 *
 * <p>Differences from the base grammar: SQLite's extra operators, case-sensitive {@code like}
 * through {@code glob}, {@code is} as the null-safe comparison, date wheres through
 * {@code strftime}, an update or delete with joins or a limit through a {@code rowid} subselect,
 * each union part wrapped as {@code select * from (...)}, {@code insert or ignore} from a subquery,
 * truncate as two deletes, and every {@link Instant} binding written as the whole microseconds an
 * {@code instant} column stores.
 *
 * <p>Two limits come from SQLite itself and are not checked here. A right join needs SQLite 3.39
 * or newer; older SQLite, such as the one in Android 9, refuses it with its own syntax error, which
 * reaches the caller as a {@code QueryException}. An update or delete with joins selects the
 * table's {@code rowid}, so on a {@code WITHOUT ROWID} table it fails the same way, with SQLite's
 * "no such column" error; telling such a table apart would need a schema read on every call.
 *
 * <p>Laravel's {@code compileLock} returns an empty string for SQLite, and so does the base grammar
 * here for {@code lockForUpdate} and {@code sharedLock}, so nothing is overridden. A lock strength
 * or wait only PostgreSQL has is refused with {@code UnsupportedOperationException}.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public class SQLiteQueryGrammar extends QueryGrammar {

    private static final Set<String> OPERATORS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "=", "<", ">", "<=", ">=", "<>", "!=",
            "like", "not like", "ilike",
            "&", "|", "<<", ">>")));
    private static final Pattern ALIAS = Pattern.compile("\\s+as\\s+", Pattern.CASE_INSENSITIVE);
    private static final String ROWID = "rowid";
    private static final int UPSERT_MAJOR = 3;
    private static final int UPSERT_MINOR = 24;

    /** Bound eager keys per query: under the 999 bindings of SQLite before 3.32, as on Android 9. */
    private static final int EAGER_KEY_CHUNK_SIZE = 900;

    /** Inlined eager keys per query: under SQLite's default 1,000,000-byte statement limit. */
    private static final int INLINE_KEY_CHUNK_SIZE = 20_000;

    /**
     * Translates a {@code like} pattern into a {@code glob} pattern inside SQL, as Laravel's
     * {@code prepareWhereLikeBinding} does in PHP: {@code [}, {@code *} and {@code ?} become literal
     * ({@code [} first, since the next two escapes write one), then
     * {@code %} and {@code _} become the glob wildcards. The core builder binds the pattern as
     * given, so the translation runs in the database, on the bound value.
     */
    private static final String GLOB_PATTERN = "replace(replace(replace(replace(replace(%s, '[', '[[]'), '*', '[*]'), '?', '[?]'), "
            + "'%%', '*'), '_', '?')";

    /**
     * Creates a SQLite grammar.
     *
     * @param tablePrefix the table prefix of the connection this grammar serves, never
     *     {@code null}, empty for none
     * @throws NullPointerException if {@code tablePrefix} is {@code null}
     */
    public SQLiteQueryGrammar(String tablePrefix) {
        super(tablePrefix);
    }

    /**
     * Returns 900: SQLite before 3.32, which Android 9 ships, allows 999 bindings per statement, and
     * the margin leaves room for the bindings of an eager constraint.
     *
     * @return the number of bound keys per eager query
     */
    @Override
    public int eagerKeyChunkSize() {
        return EAGER_KEY_CHUNK_SIZE;
    }

    /**
     * Returns 20,000: at most 20 characters per key plus a separator keep the statement well under
     * SQLite's default {@code SQLITE_MAX_SQL_LENGTH} of 1,000,000 bytes.
     *
     * @return the number of inlined keys per eager query
     */
    @Override
    public int inlineKeyChunkSize() {
        return INLINE_KEY_CHUNK_SIZE;
    }

    /**
     * Returns SQLite's operators: the comparisons, {@code like}, {@code not like}, {@code ilike}
     * and the bitwise {@code &}, {@code |}, {@code <<} and {@code >>}.
     *
     * @return the lower-case operators in Laravel's order, unmodifiable, never {@code null}
     */
    @Override
    public Set<String> getOperators() {
        return OPERATORS;
    }

    /**
     * Compiles a {@code like} condition; a case-sensitive one becomes {@code glob}, since SQLite's
     * {@code like} ignores ASCII case.
     *
     * <p>The pattern keeps {@code like}'s wildcards: {@code %} and {@code _} are translated to
     * {@code *} and {@code ?} in SQL, and a {@code *} or {@code ?} in the pattern matches itself.
     * A {@code [} matches itself too; Laravel leaves it opening a glob character class, so a value
     * such as {@code [a]} there matches the text {@code a}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String whereLike(Builder query, Where.Like where) {
        if (!where.caseSensitive()) {
            return super.whereLike(query, where);
        }
        return wrap(where.column()) + (where.not() ? " not glob " : " glob ")
                + String.format(GLOB_PATTERN, parameter(where.value()));
    }

    /**
     * Compiles a null-safe comparison with SQLite's {@code is}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String whereNullSafeEquals(Builder query, Where.NullSafeEquals where) {
        return wrap(where.column()) + " is " + parameter(where.value());
    }

    /**
     * Compiles a condition on the date part of a text date column, as {@code yyyy-MM-dd}.
     *
     * <p>Wrong on an {@code instant} column: {@code strftime} reads its whole microseconds as a
     * Julian day number. Use {@link Builder#whereInstantBetween} there.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String whereDate(Builder query, Where.DateBased where) {
        return dateBasedWhere("%Y-%m-%d", query, where);
    }

    /**
     * Compiles a condition on the time part of a text date column, as {@code HH:mm:ss}.
     *
     * <p>Wrong on an {@code instant} column, as for {@link #whereDate}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String whereTime(Builder query, Where.DateBased where) {
        return dateBasedWhere("%H:%M:%S", query, where);
    }

    /**
     * Compiles a condition on the two-digit day of the month of a text date column.
     *
     * <p>Wrong on an {@code instant} column, as for {@link #whereDate}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String whereDay(Builder query, Where.DateBased where) {
        return dateBasedWhere("%d", query, where);
    }

    /**
     * Compiles a condition on the two-digit month of a text date column.
     *
     * <p>Wrong on an {@code instant} column, as for {@link #whereDate}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String whereMonth(Builder query, Where.DateBased where) {
        return dateBasedWhere("%m", query, where);
    }

    /**
     * Compiles a condition on the year of a text date column.
     *
     * <p>Wrong on an {@code instant} column, as for {@link #whereDate}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String whereYear(Builder query, Where.DateBased where) {
        return dateBasedWhere("%Y", query, where);
    }

    /**
     * Writes {@code strftime('format', column) operator cast(? as text)}. The value is cast so
     * that a number such as month {@code 3} compares with the text {@code '03'} the way Laravel's
     * does; it matches only when written with the same digits.
     *
     * @param type the {@code strftime} format, a constant of this grammar, never user input
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String dateBasedWhere(String type, Builder query, Where.DateBased where) {
        return "strftime('" + type + "', " + wrap(where.column()) + ") " + where.operator() + " cast("
                + parameter(where.value()) + " as text)";
    }

    /**
     * Wraps one part of a union as {@code select * from (sql)}: SQLite refuses a parenthesised
     * select in a compound, and the subquery keeps each part's own order and limit.
     *
     * @param sql the part's SQL, never {@code null}
     * @return the wrapped SQL, never {@code null}
     */
    @Override
    protected String wrapUnion(String sql) {
        return "select * from (" + sql + ")";
    }

    /**
     * Compiles an insert of the rows a subquery returns that skips rows breaking a unique or
     * primary key constraint, with SQLite's {@code insert or ignore}.
     *
     * @param query the query naming the target table, never {@code null}
     * @param columns the target columns, never {@code null}; empty or {@code ["*"]} leaves the
     *     column list out
     * @param sql the subquery's SQL, never {@code null}
     * @return {@code insert or ignore into "t" ("a") select ...}, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    @Override
    public String compileInsertOrIgnoreUsing(Builder query, List<String> columns, String sql) {
        return "insert or ignore" + compileInsertUsing(query, columns, sql).substring("insert".length());
    }

    /**
     * Compiles an insert of rows that skips a row breaking a unique or primary key constraint,
     * with SQLite's {@code insert or ignore}. SQLite also skips a row that fails a {@code not null}
     * or {@code check} constraint.
     *
     * @param query the query naming the target table, never {@code null}
     * @param rows the rows, never {@code null} or empty
     * @return {@code insert or ignore into "t" ("a") values (?)}, never {@code null}
     */
    @Override
    protected String compileInsertOrIgnore(Builder query, List<? extends Map<String, ?>> rows) {
        return "insert or ignore" + compileInsert(query, rows).orElseThrow(IllegalStateException::new)
                .substring("insert".length());
    }

    /**
     * Compiles an insert with {@code on conflict (unique columns) do update set}, or
     * {@code do nothing} when there is nothing to set.
     *
     * @param query the query naming the target table, never {@code null}
     * @param rows the rows, never {@code null} or empty
     * @param uniqueBy the columns of the unique index, never {@code null} or empty
     * @param excluded the columns to take from the refused row, never {@code null}
     * @param values the columns to set to a given value, never {@code null}
     * @return the SQL, never {@code null}
     */
    @Override
    protected String compileUpsert(Builder query, List<? extends Map<String, ?>> rows, List<String> uniqueBy,
            List<String> excluded, Map<String, ?> values) {
        return compileInsert(query, rows).orElseThrow(IllegalStateException::new) + " "
                + compileOnConflict(uniqueBy, excluded, values);
    }

    /**
     * Refuses an upsert on a SQLite older than 3.24, which has no {@code on conflict do update}.
     * Android before API 30 ships such a SQLite. A version that cannot be read is refused with its own message.
     *
     * @param databaseVersion the version the driver reports, never {@code null}
     * @throws UnsupportedOperationException if the version is older than 3.24 or unreadable
     */
    @Override
    protected void checkUpsert(String databaseVersion) {
        if (!SqliteVersion.isReadable(databaseVersion)) {
            throw new UnsupportedOperationException("Cannot read the SQLite version '" + databaseVersion
                    + "'; upsert needs SQLite 3.24 or later");
        }
        if (!SqliteVersion.atLeast(databaseVersion, UPSERT_MAJOR, UPSERT_MINOR, 0)) {
            throw new UnsupportedOperationException("Upsert needs SQLite 3.24 or later (Android API 30+); "
                    + "this database is " + databaseVersion);
        }
    }

    /**
     * Compiles an update of a query with joins: the joined query, on a copy, selects the rowids
     * of the rows to change. Bindings follow the {@code set} values, then the joins and wheres
     * of the subselect, the order {@link QueryGrammar#prepareBindingsForUpdate} gives.
     *
     * @param query the query, never {@code null}, with a table; not changed
     * @param table the wrapped table, never {@code null}
     * @param columns the compiled {@code set} list, never {@code null}
     * @param where the compiled where clause, never {@code null}; unused, the subselect holds it
     * @return the SQL, never {@code null}
     */
    @Override
    protected String compileUpdateWithJoins(Builder query, String table, String columns, String where) {
        return "update " + table + " set " + columns + " where " + rowidIn(query);
    }

    /**
     * Compiles a delete of a query with joins through a {@code rowid} subselect, as for
     * {@link #compileUpdateWithJoins}.
     *
     * @param query the query, never {@code null}, with a table; not changed
     * @param table the wrapped table, never {@code null}
     * @param where the compiled where clause, never {@code null}; unused, the subselect holds it
     * @return the SQL, never {@code null}
     */
    @Override
    protected String compileDeleteWithJoins(Builder query, String table, String where) {
        return "delete from " + table + " where " + rowidIn(query);
    }

    /**
     * Compiles an update; with a limit, the rows are chosen by a {@code rowid} subselect, since
     * SQLite builds without {@code update ... limit} by default.
     *
     * @param query the query, never {@code null}, with a table; not changed
     * @param values the columns and their new values, never {@code null} or empty
     * @return the SQL, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code values} is empty
     */
    @Override
    public String compileUpdate(Builder query, Map<String, ?> values) {
        Objects.requireNonNull(query, "query");
        if (!query.getLimit().isPresent()) {
            return super.compileUpdate(query, values);
        }
        if (Objects.requireNonNull(values, "values").isEmpty()) {
            throw new IllegalArgumentException("An update needs at least one column");
        }
        return "update " + wrapTable(query.getFrom()) + " set " + compileUpdateColumns(query, values) + " where "
                + rowidIn(query);
    }

    /**
     * Compiles a delete; with a limit, the rows are chosen by a {@code rowid} subselect, as for
     * {@link #compileUpdate}.
     *
     * @param query the query, never {@code null}, with a table; not changed
     * @return the SQL, never {@code null}
     */
    @Override
    public String compileDelete(Builder query) {
        if (!query.getLimit().isPresent()) {
            return super.compileDelete(query);
        }
        return "delete from " + wrapTable(query.getFrom()) + " where " + rowidIn(query);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote {@code true} with joins or a limit: the {@code rowid} subselect is a full select.
     */
    @Override
    protected boolean writesFullSelect(Builder query) {
        return query.hasJoins() || query.getLimit().isPresent();
    }

    /**
     * Writes {@code "rowid" in (select alias.rowid ...)} from a copy of the query, so the caller's
     * columns stay as they were; Laravel selects the rowid on the caller's builder itself.
     */
    private String rowidIn(Builder query) {
        String[] parts = ALIAS.split(query.getFrom());
        Builder rowids = query.clone().select(parts[parts.length - 1] + "." + ROWID);
        return wrap(ROWID) + " in (" + compileSelect(rowids) + ")";
    }

    /**
     * Compiles the two statements that empty a table: reset its {@code autoincrement} counter in
     * {@code sqlite_sequence}, then delete every row.
     *
     * <p>The counter statement needs {@code sqlite_sequence} to exist, which SQLite creates with
     * the first {@code autoincrement} table of the database; Diwan's {@code id()} column is one.
     *
     * @param query the query, never {@code null}, with a table, optionally {@code schema.table}
     * @return the counter reset bound to the prefixed table name, then the delete, in run order,
     *     unmodifiable, never {@code null}
     */
    @Override
    public Map<String, List<Object>> compileTruncate(Builder query) {
        String table = ALIAS.split(query.getFrom())[0];
        int dot = table.indexOf('.');
        String schema = dot < 0 ? "" : wrapValue(table.substring(0, dot)) + ".";
        String name = dot < 0 ? table : table.substring(dot + 1);
        Map<String, List<Object>> statements = new LinkedHashMap<>();
        statements.put("delete from " + schema + "sqlite_sequence where name = ?", Collections.<Object>singletonList(getTablePrefix() + name));
        statements.put("delete from " + wrapTable(query.getFrom()), Collections.emptyList());
        return Collections.unmodifiableMap(statements);
    }

    /**
     * Converts bindings to what SQLite stores: each {@link Instant} becomes its whole
     * microseconds since the epoch, what an {@code instant} column holds; other values pass
     * unchanged.
     *
     * @param bindings the bindings, never {@code null}, may contain {@code null}
     * @return the converted bindings, unmodifiable, never {@code null}
     * @throws NullPointerException if {@code bindings} is {@code null}
     * @throws IllegalArgumentException if an {@code Instant} is finer than a microsecond or too far
     *     from the epoch
     */
    @Override
    public List<Object> prepareBindings(List<?> bindings) {
        List<Object> prepared = new ArrayList<>(Objects.requireNonNull(bindings, "bindings").size());
        for (Object binding : bindings) {
            prepared.add(binding instanceof Instant ? (Object) Instants.toMicros((Instant) binding) : binding);
        }
        return Collections.unmodifiableList(prepared);
    }
}
