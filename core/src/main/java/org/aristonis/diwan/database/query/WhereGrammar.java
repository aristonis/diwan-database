package org.aristonis.diwan.database.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The where layer of {@link QueryGrammar}: compiles the where clause and every condition type.
 *
 * <p>Package-private: its methods are documented as {@link QueryGrammar}'s own, and database
 * grammars override them there. The grammar is split into layers only to keep each file small.
 */
abstract class WhereGrammar extends GrammarQuoting {

    /** The keyword and space that open a compiled where clause, cut off when the clause is nested. */
    static final int WHERE_LENGTH = "where ".length();

    /** The keyword and space that open a join's compiled conditions, cut off when they are nested. */
    static final int ON_LENGTH = "on ".length();

    /** Bound keys per eager query by default: SQLite's oldest limit of 999 bindings, less a margin. */
    static final int DEFAULT_EAGER_KEY_CHUNK_SIZE = 900;

    /** Inlined integer keys per eager query by default: well under a 1,000,000-byte statement. */
    static final int DEFAULT_INLINE_KEY_CHUNK_SIZE = 20_000;

    /** Creates the where layer for a prefix already checked for {@code null}. */
    WhereGrammar(String tablePrefix) {
        super(tablePrefix);
    }

    /**
     * Compiles a select statement; a where condition holding a subquery compiles it with this.
     *
     * @param query the query, never {@code null}
     * @return the SQL with {@code ?} marks, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support a clause
     */
    public abstract String compileSelect(Builder query);

    // ---- eager key chunks ----

    /**
     * Returns how many keys an eager load binds in one query; a longer key list runs in chunks.
     *
     * <p>The default of 900 keeps a query under the 999 bindings of older SQLite builds, such as
     * Android's, with room for the bindings of an eager constraint. A grammar overrides it for a
     * database that allows more.
     *
     * @return the number of bound keys per query, at least 1
     */
    public int eagerKeyChunkSize() {
        return DEFAULT_EAGER_KEY_CHUNK_SIZE;
    }

    /**
     * Returns how many integer keys an eager load writes into one query's SQL; a longer key list
     * runs in chunks.
     *
     * <p>Inlined keys take no binding, so this limit guards the length of the statement instead:
     * 20,000 keys of at most 20 characters each, with their separators, stay well under
     * SQLite's default maximum of 1,000,000 bytes.
     *
     * @return the number of inlined keys per query, at least 1
     */
    public int inlineKeyChunkSize() {
        return DEFAULT_INLINE_KEY_CHUNK_SIZE;
    }

    // ---- where ----

    /**
     * Compiles the where clause, or the conditions of a join, which open with {@code on}.
     *
     * @param query the query, never {@code null}
     * @return {@code where ...}, or {@code on ...} for a {@link JoinClause}, or an empty string if
     *     the query has no conditions
     * @throws UnsupportedOperationException if a condition is not supported
     */
    public String compileWheres(Builder query) {
        List<Where> wheres = query.getWheres();
        if (wheres.isEmpty()) {
            return "";
        }
        List<String> sql = new ArrayList<>(wheres.size());
        for (Where where : wheres) {
            sql.add(where.conjunction() + " " + compileWhere(query, where));
        }
        return (query instanceof JoinClause ? "on " : "where ") + removeLeadingBoolean(String.join(" ", sql));
    }

    /**
     * Compiles one condition, without its conjunction, by calling the method named after its type.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException if the condition is not supported
     */
    protected String compileWhere(Builder query, Where where) {
        if (where instanceof Where.Basic) {
            return whereBasic(query, (Where.Basic) where);
        } else if (where instanceof Where.Raw) {
            return whereRaw(query, (Where.Raw) where);
        } else if (where instanceof Where.Like) {
            return whereLike(query, (Where.Like) where);
        } else if (where instanceof Where.NullSafeEquals) {
            return whereNullSafeEquals(query, (Where.NullSafeEquals) where);
        } else if (where instanceof Where.In) {
            return whereIn(query, (Where.In) where);
        } else if (where instanceof Where.NotIn) {
            return whereNotIn(query, (Where.NotIn) where);
        } else if (where instanceof Where.Null) {
            return whereNull(query, (Where.Null) where);
        } else if (where instanceof Where.NotNull) {
            return whereNotNull(query, (Where.NotNull) where);
        } else if (where instanceof Where.Between) {
            return whereBetween(query, (Where.Between) where);
        } else if (where instanceof Where.BetweenColumns) {
            return whereBetweenColumns(query, (Where.BetweenColumns) where);
        } else if (where instanceof Where.ValueBetween) {
            return whereValueBetween(query, (Where.ValueBetween) where);
        } else if (where instanceof Where.DateBased) {
            return compileDateBased(query, (Where.DateBased) where);
        } else if (where instanceof Where.Column) {
            return whereColumn(query, (Where.Column) where);
        } else if (where instanceof Where.Nested) {
            return whereNested(query, (Where.Nested) where);
        }
        return compileSubqueryWhere(query, where);
    }

    private String compileSubqueryWhere(Builder query, Where where) {
        if (where instanceof WhereSub) {
            return whereSub(query, (WhereSub) where);
        } else if (where instanceof WhereSubValue) {
            return whereSubValue(query, (WhereSubValue) where);
        } else if (where instanceof WhereInSub) {
            return whereInSub(query, (WhereInSub) where);
        } else if (where instanceof WhereExists) {
            return whereExists(query, (WhereExists) where);
        } else if (where instanceof WhereRowValues) {
            return whereRowValues(query, (WhereRowValues) where);
        }
        throw new IllegalStateException("Unknown where condition " + where.getClass().getName());
    }

    private String compileDateBased(Builder query, Where.DateBased where) {
        switch (where.part()) {
            case DATE:
                return whereDate(query, where);
            case TIME:
                return whereTime(query, where);
            case DAY:
                return whereDay(query, where);
            case MONTH:
                return whereMonth(query, where);
            default:
                return whereYear(query, where);
        }
    }

    /**
     * Compiles a raw condition.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL as given, never {@code null}
     */
    protected String whereRaw(Builder query, Where.Raw where) {
        return where.sql();
    }

    /**
     * Compiles a column compared with a value.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereBasic(Builder query, Where.Basic where) {
        return basic(where.column(), where.operator(), where.value());
    }

    /**
     * Writes {@code column operator ?}. A {@code ?} inside the operator is doubled, Laravel's
     * escape for PostgreSQL operators such as {@code ?|}, so it is not read as a placeholder.
     */
    private String basic(String column, String operator, Object value) {
        return wrap(column) + " " + operator.replace("?", "??") + " " + parameter(value);
    }

    /**
     * Compiles a {@code like} condition.
     *
     * @implSpec The base supports only case-insensitive matching, which is what {@code like}
     *     already does on the databases it targets.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     * @throws UnsupportedOperationException if the match is case-sensitive
     */
    protected String whereLike(Builder query, Where.Like where) {
        if (where.caseSensitive()) {
            throw unsupported("Case-sensitive like");
        }
        return basic(where.column(), where.not() ? "not like" : "like", where.value());
    }

    /**
     * Compiles a null-safe comparison.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereNullSafeEquals(Builder query, Where.NullSafeEquals where) {
        return wrap(where.column()) + " is not distinct from " + parameter(where.value());
    }

    /**
     * Compiles an {@code in} condition; an empty list writes {@code 0 = 1}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereIn(Builder query, Where.In where) {
        if (where.values().isEmpty()) {
            return "0 = 1";
        }
        return wrap(where.column()) + " in (" + parameterize(where.values()) + ")";
    }

    /**
     * Compiles a {@code not in} condition; an empty list writes {@code 1 = 1}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereNotIn(Builder query, Where.NotIn where) {
        if (where.values().isEmpty()) {
            return "1 = 1";
        }
        return wrap(where.column()) + " not in (" + parameterize(where.values()) + ")";
    }

    /**
     * Compiles an {@code is null} condition.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereNull(Builder query, Where.Null where) {
        return wrap(where.column()) + " is null";
    }

    /**
     * Compiles an {@code is not null} condition.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereNotNull(Builder query, Where.NotNull where) {
        return wrap(where.column()) + " is not null";
    }

    /**
     * Compiles a {@code between} condition on values.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereBetween(Builder query, Where.Between where) {
        return wrap(where.column()) + " " + between(where.not()) + " " + parameter(where.values().get(0)) + " and "
                + parameter(where.values().get(1));
    }

    /**
     * Compiles a {@code between} condition on columns.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereBetweenColumns(Builder query, Where.BetweenColumns where) {
        return wrap(where.column()) + " " + between(where.not()) + " " + wrap(where.columns().get(0)) + " and "
                + wrap(where.columns().get(1));
    }

    /**
     * Compiles a value between two columns.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereValueBetween(Builder query, Where.ValueBetween where) {
        return parameter(where.value()) + " " + between(where.not()) + " " + wrap(where.columns().get(0)) + " and "
                + wrap(where.columns().get(1));
    }

    static String between(boolean not) {
        return not ? "not between" : "between";
    }

    /**
     * Compiles a condition on the date part of a column.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereDate(Builder query, Where.DateBased where) {
        return dateBasedWhere("date", query, where);
    }

    /**
     * Compiles a condition on the time part of a column.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereTime(Builder query, Where.DateBased where) {
        return dateBasedWhere("time", query, where);
    }

    /**
     * Compiles a condition on the day of the month of a column.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereDay(Builder query, Where.DateBased where) {
        return dateBasedWhere("day", query, where);
    }

    /**
     * Compiles a condition on the month of a column.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereMonth(Builder query, Where.DateBased where) {
        return dateBasedWhere("month", query, where);
    }

    /**
     * Compiles a condition on the year of a column.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereYear(Builder query, Where.DateBased where) {
        return dateBasedWhere("year", query, where);
    }

    /**
     * Writes {@code function(column) operator ?}.
     *
     * @param type the SQL function, a constant of the grammar, never user input
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String dateBasedWhere(String type, Builder query, Where.DateBased where) {
        return type + "(" + wrap(where.column()) + ") " + where.operator() + " " + parameter(where.value());
    }

    /**
     * Compiles two columns compared with each other.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereColumn(Builder query, Where.Column where) {
        return wrap(where.first()) + " " + where.operator().replace("?", "??") + " " + wrap(where.second());
    }

    /**
     * Compiles a group of conditions in parentheses.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereNested(Builder query, Where.Nested where) {
        Builder group = where.builder();
        return "(" + compileWheres(group).substring(group instanceof JoinClause ? ON_LENGTH : WHERE_LENGTH) + ")";
    }

    /**
     * Compiles a column compared with a subquery: {@code "a" = (select ...)}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereSub(Builder query, WhereSub where) {
        return wrap(where.column()) + " " + where.operator().replace("?", "??") + " ("
                + compileSelect(where.builder()) + ")";
    }

    /**
     * Compiles a subquery compared with a value: {@code (select ...) > ?}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereSubValue(Builder query, WhereSubValue where) {
        return "(" + compileSelect(where.builder()) + ") " + where.operator().replace("?", "??") + " "
                + parameter(where.value());
    }

    /**
     * Compiles a column in or not in a subquery: {@code "a" in (select ...)}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereInSub(Builder query, WhereInSub where) {
        return wrap(where.column()) + (where.not() ? " not in (" : " in (") + compileSelect(where.builder()) + ")";
    }

    /**
     * Compiles an exists condition: {@code exists (select ...)} or {@code not exists (select ...)}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereExists(Builder query, WhereExists where) {
        return (where.not() ? "not exists (" : "exists (") + compileSelect(where.builder()) + ")";
    }

    /**
     * Compiles a row of columns compared with a row of values: {@code ("a", "b") < (?, ?)}.
     *
     * @param query the query, never {@code null}
     * @param where the condition, never {@code null}
     * @return the SQL, never {@code null}
     */
    protected String whereRowValues(Builder query, WhereRowValues where) {
        return "(" + columnize(where.columns()) + ") " + where.operator().replace("?", "??") + " ("
                + parameterize(where.values()) + ")";
    }
}
