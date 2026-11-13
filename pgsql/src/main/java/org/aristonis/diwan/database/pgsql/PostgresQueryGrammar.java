package org.aristonis.diwan.database.pgsql;

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
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.LockStrength;
import org.aristonis.diwan.database.query.LockWait;
import org.aristonis.diwan.database.query.QueryGrammar;
import org.aristonis.diwan.database.query.RowLock;
import org.aristonis.diwan.database.query.SearchOptions;
import org.aristonis.diwan.database.query.SearchWhere;
import org.aristonis.diwan.database.query.Where;

/**
 * Compiles queries into PostgreSQL SQL, a hand port of Laravel's
 * {@code Illuminate\Database\Query\Grammars\PostgresGrammar}.
 *
 * <p>Differences from the base grammar: {@code like} compares the column as text, date wheres
 * use casts and {@code extract}, {@code insertGetId} uses {@code returning}, an insert that ignores
 * errors uses {@code on conflict do nothing}, an update or delete with joins or a limit goes through
 * a {@code ctid} subselect, and truncate restarts the identity.
 *
 * <p>Laravel's operators that hold a {@code ?} (the JSON operators {@code ?}, {@code ?|},
 * {@code ?&} and {@code @?}) are left out, because JSON wheres are not supported; so are its
 * bitwise-only operators. Truncate never cascades: Laravel cascades by default, which also empties
 * every table that references this one.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public class PostgresQueryGrammar extends QueryGrammar {

    private static final Set<String> OPERATORS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "=", "<", ">", "<=", ">=", "<>", "!=",
            "like", "not like", "between", "ilike", "not ilike",
            "~", "&", "|", "<<", ">>",
            "&&", "@>", "<@", "||", "-", "@@", "#-",
            "is distinct from", "is not distinct from")));
    private static final Pattern ALIAS = Pattern.compile("\\s+as\\s+", Pattern.CASE_INSENSITIVE);
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int NIBBLE = 4;
    private static final int LOW_NIBBLE = 0x0f;

    /** Bound eager keys per query: far under the protocol's 65,535 parameters per statement. */
    private static final int EAGER_KEY_CHUNK_SIZE = 10_000;

    /** Inlined eager keys per query: PostgreSQL has no small statement length limit. */
    private static final int INLINE_KEY_CHUNK_SIZE = 50_000;

    /**
     * Creates a grammar.
     *
     * @param tablePrefix the table prefix of the connection this grammar serves, never
     *     {@code null}, empty for none
     * @throws NullPointerException if {@code tablePrefix} is {@code null}
     */
    public PostgresQueryGrammar(String tablePrefix) {
        super(tablePrefix);
    }

    /**
     * Returns 10,000: the wire protocol allows 65,535 parameters per statement, and the margin
     * leaves room for the bindings of an eager constraint.
     *
     * @return the number of bound keys per eager query
     */
    @Override
    public int eagerKeyChunkSize() {
        return EAGER_KEY_CHUNK_SIZE;
    }

    /**
     * Returns 50,000: PostgreSQL has no small statement length limit, so the chunk only bounds the
     * size of one statement in memory.
     *
     * @return the number of inlined keys per eager query
     */
    @Override
    public int inlineKeyChunkSize() {
        return INLINE_KEY_CHUNK_SIZE;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Laravel's PostgreSQL list without the operators that hold a {@code ?} and without
     *     {@code #}, {@code <<=} and {@code >>=}, which Laravel treats as bitwise only.
     */
    @Override
    public Set<String> getOperators() {
        return OPERATORS;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote A {@code like} operator compares the column cast to {@code text}, so it also works
     *     on numbers, dates and uuids.
     */
    @Override
    protected String whereBasic(Builder query, Where.Basic where) {
        if (where.operator().contains("like")) {
            return likeClause(where.column(), where.operator(), where.value());
        }
        return super.whereBasic(query, where);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Case-insensitive matches use {@code ilike}, case-sensitive ones {@code like},
     *     both on the column cast to {@code text}.
     */
    @Override
    protected String whereLike(Builder query, Where.Like where) {
        String operator = (where.not() ? "not " : "") + (where.caseSensitive() ? "like" : "ilike");
        return likeClause(where.column(), operator, where.value());
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Both case modes use the base pattern.
     * @deprecated since 0.5.0, for removal in 0.6.0; use
     *     {@link QueryGrammar#searchPattern(String, SearchWhere.Anchor, SearchOptions)}
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @SuppressWarnings("removal")
    @Override
    public String searchPattern(String value, SearchWhere.Anchor anchor, boolean caseSensitive) {
        return super.searchPattern(value, anchor, false);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Writes {@code column::text like|ilike ? escape '!'}.
     */
    @Override
    protected String whereSearch(Builder query, SearchWhere where) {
        String operator = (where.not() ? "not " : "") + (where.caseSensitive() ? "like" : "ilike");
        return wrap(where.column()) + "::text " + operator + " ? escape '!'";
    }

    private String likeClause(String column, String operator, Object value) {
        return wrap(column) + "::text " + operator + " " + parameter(value);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Writes {@code column::date operator ?}.
     */
    @Override
    protected String whereDate(Builder query, Where.DateBased where) {
        return wrap(where.column()) + "::date " + where.operator() + " " + parameter(where.value());
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Writes {@code column::time operator ?}.
     */
    @Override
    protected String whereTime(Builder query, Where.DateBased where) {
        return wrap(where.column()) + "::time " + where.operator() + " " + parameter(where.value());
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Writes {@code extract(type from column) operator ?}.
     */
    @Override
    protected String dateBasedWhere(String type, Builder query, Where.DateBased where) {
        return "extract(" + type + " from " + wrap(where.column()) + ") " + where.operator() + " "
                + parameter(where.value());
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Appends {@code returning} and the quoted sequence column.
     */
    @Override
    public String compileInsertGetId(Builder query, Map<String, ?> row, String sequence) {
        return super.compileInsertGetId(query, row, sequence) + " returning " + wrap(sequence);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Appends {@code on conflict do nothing}, which skips rows breaking any unique or
     *     primary key constraint.
     */
    @Override
    public String compileInsertOrIgnoreUsing(Builder query, List<String> columns, String sql) {
        return compileInsertUsing(query, columns, sql) + " on conflict do nothing";
    }

    /**
     * Compiles an insert of rows that skips a row breaking a unique or primary key constraint,
     * with {@code on conflict do nothing}.
     *
     * @param query the query naming the target table, never {@code null}
     * @param rows the rows, never {@code null} or empty
     * @return {@code insert into "t" ("a") values (?) on conflict do nothing}, never {@code null}
     */
    @Override
    protected String compileInsertOrIgnore(Builder query, List<? extends Map<String, ?>> rows) {
        return compileInsert(query, rows).orElseThrow(IllegalStateException::new) + " on conflict do nothing";
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
     * Compiles the row lock: {@code for update}, {@code for no key update}, {@code for share} or
     * {@code for key share}, then {@code nowait} or {@code skip locked}.
     *
     * @param query the query, never {@code null}
     * @param lock the lock, never {@code null}
     * @return the SQL, such as {@code for update skip locked}, never {@code null}
     * @throws IllegalArgumentException if the query has unions
     */
    @Override
    protected String compileLock(Builder query, RowLock lock) {
        checkLockable(query);
        String strength = lock.strength() == LockStrength.UPDATE ? "for update"
                : lock.strength() == LockStrength.NO_KEY_UPDATE ? "for no key update"
                : lock.strength() == LockStrength.SHARE ? "for share" : "for key share";
        if (lock.lockWait() == LockWait.NOWAIT) {
            return strength + " nowait";
        }
        return lock.lockWait() == LockWait.SKIP_LOCKED ? strength + " skip locked" : strength;
    }

    /**
     * Tells that a lock ends with its transaction.
     *
     * @return {@code true}
     */
    @Override
    protected boolean locksNeedTransaction() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote With joins or a limit, the rows to change are picked by a {@code ctid} subselect,
     *     which keeps the query's joins, wheres, orders and limit. Its bindings (join, then where,
     *     then order) follow the set values, the order {@code prepareBindingsForUpdate} gives. The
     *     subselect is compiled from a copy, so {@code query} is not changed.
     */
    @Override
    public String compileUpdate(Builder query, Map<String, ?> values) {
        Objects.requireNonNull(query, "query");
        if (!needsCtid(query)) {
            return super.compileUpdate(query, values);
        }
        if (Objects.requireNonNull(values, "values").isEmpty()) {
            throw new IllegalArgumentException("An update needs at least one column");
        }
        return "update " + wrapTable(query.getFrom()) + " set " + compileUpdateColumns(query, values)
                + " where " + wrap("ctid") + " in (" + ctidSelect(query) + ")";
    }

    /**
     * {@inheritDoc}
     *
     * @implNote With joins or a limit, the rows to delete are picked by a {@code ctid} subselect
     *     compiled from a copy of {@code query}.
     */
    @Override
    public String compileDelete(Builder query) {
        Objects.requireNonNull(query, "query");
        if (!needsCtid(query)) {
            return super.compileDelete(query);
        }
        return "delete from " + wrapTable(query.getFrom()) + " where " + wrap("ctid") + " in ("
                + ctidSelect(query) + ")";
    }

    /**
     * {@inheritDoc}
     *
     * @implNote {@code true} with joins or a limit: the {@code ctid} subselect is a full select.
     */
    @Override
    protected boolean writesFullSelect(Builder query) {
        return needsCtid(query);
    }

    private static boolean needsCtid(Builder query) {
        return query.getLimit().isPresent() || query.hasJoins();
    }

    private String ctidSelect(Builder query) {
        String[] parts = ALIAS.split(query.getFrom());
        return compileSelect(query.clone().select(parts[parts.length - 1] + ".ctid"));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Writes {@code truncate table restart identity}, without {@code cascade}.
     */
    @Override
    public Map<String, List<Object>> compileTruncate(Builder query) {
        Map<String, List<Object>> statements = new LinkedHashMap<>();
        statements.put("truncate " + wrapTable(query.getFrom()) + " restart identity", Collections.emptyList());
        return Collections.unmodifiableMap(statements);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Writes one {@code truncate "a", "b" restart identity}, with {@code cascade} added
     *     when asked.
     */
    @Override
    public Map<String, List<Object>> compileTruncateMany(List<String> tables, boolean cascade) {
        Map<String, List<Object>> statements = new LinkedHashMap<>();
        if (tables.isEmpty()) {
            return Collections.unmodifiableMap(statements);
        }
        List<String> wrapped = new ArrayList<>();
        for (String table : tables) {
            wrapped.add(wrapTable(table));
        }
        statements.put("truncate " + String.join(", ", wrapped) + " restart identity" + (cascade ? " cascade" : ""),
                Collections.emptyList());
        return Collections.unmodifiableMap(statements);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Moves each sequence owned by a column of the tables to just past the highest
     *     committed value, with {@code setval}. The sequences are read from the catalog, which
     *     needs PostgreSQL 10 or later.
     * @throws UnsupportedOperationException if the server is older than PostgreSQL 10
     */
    @Override
    protected void restartIdentities(Connection connection, List<String> tables) {
        PostgresIdentityRestart.run(connection, this, tables);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Writes {@code true} or {@code false}.
     */
    @Override
    protected String escapeBool(boolean value) {
        return value ? "true" : "false";
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Writes a {@code bytea} hex literal: {@code '\x00ff'::bytea}.
     */
    @Override
    protected String escapeBinary(byte[] value) {
        StringBuilder hex = new StringBuilder(value.length * 2 + "'\\x'::bytea".length());
        hex.append("'\\x");
        for (byte b : value) {
            hex.append(HEX[(b >> NIBBLE) & LOW_NIBBLE]).append(HEX[b & LOW_NIBBLE]);
        }
        return hex.append("'::bytea").toString();
    }
}
