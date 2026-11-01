package org.aristonis.diwan.database.pgsql;

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

/**
 * Compiles queries into PostgreSQL SQL, a hand port of Laravel's
 * {@code Illuminate\Database\Query\Grammars\PostgresGrammar}.
 *
 * <p>Differences from the base grammar: {@code like} compares the column as text, date wheres
 * use casts and {@code extract}, {@code insertGetId} uses {@code returning}, an update or delete
 * with a limit goes through a {@code ctid} subselect, and truncate restarts the identity.
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
     * @implNote With a limit, the rows to change are picked by a {@code ctid} subselect, which
     *     keeps the query's wheres, orders and limit. The subselect is compiled from a copy, so
     *     {@code query} is not changed.
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
        return "update " + wrapTable(query.getFrom()) + " set " + compileUpdateColumns(query, values)
                + " where " + wrap("ctid") + " in (" + ctidSelect(query) + ")";
    }

    /**
     * {@inheritDoc}
     *
     * @implNote With a limit, the rows to delete are picked by a {@code ctid} subselect compiled
     *     from a copy of {@code query}.
     */
    @Override
    public String compileDelete(Builder query) {
        Objects.requireNonNull(query, "query");
        if (!query.getLimit().isPresent()) {
            return super.compileDelete(query);
        }
        return "delete from " + wrapTable(query.getFrom()) + " where " + wrap("ctid") + " in ("
                + ctidSelect(query) + ")";
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
