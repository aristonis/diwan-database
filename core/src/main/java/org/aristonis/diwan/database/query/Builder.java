package org.aristonis.diwan.database.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;

/**
 * Builds one query fluently, a hand port of Laravel's {@code Illuminate\Database\Query\Builder}.
 *
 * <pre>{@code
 * String sql = db.table("users").select("id", "name").where("active", true).orderBy("name").toSql();
 * // select "id", "name" from "users" where "active" = ? order by "name" asc
 * }</pre>
 *
 * <p>Method names and behavior follow Laravel. PHP's optional arguments become overloads: a
 * two-argument {@code where(column, value)} compares with {@code =}, a nested group is a
 * {@code Consumer<Builder>}, and a map of conditions is a {@code Map}. As in Laravel:
 * <ul>
 *   <li>a {@code null} value with {@code =} or {@code <=>} becomes {@code is null}, and with
 *       {@code !=} or {@code <>} becomes {@code is not null}; with any other known operator it is
 *       rejected;</li>
 *   <li>an operator the builder and grammar do not know is taken as the value, compared with
 *       {@code =}, so an operator never reaches the SQL unchecked;</li>
 *   <li>a negative limit is ignored and a negative offset becomes zero.</li>
 * </ul>
 *
 * <p>Values are always bound as {@code ?}; an {@link Expression} value is written as given.
 * Identifiers are always quoted by the grammar. Methods ending in {@code Raw} write trusted SQL
 * as given and must never receive user input. An enum value is bound by its name.
 *
 * <p>A builder is mutable and not safe to share between threads. Every method that returns
 * {@code Builder} changes this builder and returns it. {@link #clone()} gives an independent copy.
 * Lists returned by the getters are unmodifiable views of a snapshot.
 */
public final class Builder {

    private static final String AND = "and";
    private static final String OR = "or";
    private static final String NOT = " not";
    private static final String ASC = "asc";
    private static final String DESC = "desc";
    private static final String DEFAULT_KEY = "id";
    private static final String DEFAULT_TIMESTAMP = "created_at";
    private static final int DEFAULT_PER_PAGE = 15;

    private final Connection connection;
    private final QueryGrammar grammar;
    private final Processor processor;
    private final EnumMap<BindingType, List<Object>> bindings = new EnumMap<>(BindingType.class);
    private List<Object> columns = new ArrayList<>();
    private boolean distinct;
    private String from;
    private List<Where> wheres = new ArrayList<>();
    private List<Object> groups = new ArrayList<>();
    private List<Having> havings = new ArrayList<>();
    private List<Order> orders = new ArrayList<>();
    private Integer limit;
    private Integer offset;
    private Aggregate aggregate;

    /**
     * Creates an empty builder. Applications start from a {@link QueryFactory} instead.
     *
     * @param connection the connection, never {@code null}
     * @param grammar the grammar, never {@code null}
     * @param processor the processor, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder(Connection connection, QueryGrammar grammar, Processor processor) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.grammar = Objects.requireNonNull(grammar, "grammar");
        this.processor = Objects.requireNonNull(processor, "processor");
        for (BindingType type : BindingType.values()) {
            bindings.put(type, new ArrayList<>());
        }
    }

    // ---- select and from ----

    /**
     * Replaces the selected columns; no columns selects {@code *}.
     *
     * @param columns the columns, each a {@code String} (may hold {@code "name as alias"} or
     *     {@code "table.*"}) or an {@link Expression}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     * @throws IllegalArgumentException if a column is neither a {@code String} nor an
     *     {@code Expression}
     */
    public Builder select(Object... columns) {
        List<Object> checked = checkColumns(columns.length == 0 ? new Object[] {"*"} : columns);
        this.columns = new ArrayList<>(checked);
        bindings.get(BindingType.SELECT).clear();
        return this;
    }

    /**
     * Adds columns to the selection, skipping any name already selected; an {@link Expression} is
     * always added.
     *
     * @param columns the columns, as for {@link #select(Object...)}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     * @throws IllegalArgumentException if a column is neither a {@code String} nor an
     *     {@code Expression}
     */
    public Builder addSelect(Object... columns) {
        for (Object column : checkColumns(columns)) {
            // Only names are deduplicated. Laravel compares objects by identity, so two raw
            // expressions with the same text are both kept, each with its own bindings.
            if (column instanceof Expression || !this.columns.contains(column)) {
                this.columns.add(column);
            }
        }
        return this;
    }

    /**
     * Adds trusted SQL to the selection.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public Builder selectRaw(String sql) {
        return selectRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings to the selection.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public Builder selectRaw(String sql, List<?> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        addSelect(Expression.raw(sql));
        return addBindings(bindings, BindingType.SELECT);
    }

    /**
     * Makes the query return only distinct rows.
     *
     * @return this builder
     */
    public Builder distinct() {
        this.distinct = true;
        return this;
    }

    /**
     * Sets the table.
     *
     * @param table the table name, never {@code null}, optionally {@code "name as alias"}
     * @return this builder
     * @throws NullPointerException if {@code table} is {@code null}
     */
    public Builder from(String table) {
        this.from = Objects.requireNonNull(table, "table");
        return this;
    }

    /**
     * Sets the table with an alias.
     *
     * @param table the table name, never {@code null}
     * @param as the alias, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder from(String table, String as) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(as, "as");
        return from(table + " as " + as);
    }

    // ---- basic where ----

    /**
     * Adds a condition comparing a column with a value using {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the value, may be {@code null} (compiles to {@code is null}), or an
     *     {@link Expression}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder where(String column, Object value) {
        return addWhere(column, "=", value, AND);
    }

    /**
     * Adds a condition comparing a column with a value.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, never {@code null}; an unknown one is taken as the value
     * @param value the value, may be {@code null} with {@code =}, {@code !=}, {@code <>} or
     *     {@code <=>}, or an {@link Expression}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the value is {@code null} with another known operator,
     *     or is a list, map, array or builder
     */
    public Builder where(String column, String operator, Object value) {
        return addWhere(column, operator, value, AND);
    }

    /**
     * Adds a group of {@code column = value} conditions, joined by {@code and}, in the map's
     * iteration order.
     *
     * @param conditions the columns and values, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code conditions} or a key is {@code null}
     * @throws IllegalArgumentException if a value is a list, map, array or builder
     */
    public Builder where(Map<String, ?> conditions) {
        return addArrayOfWheres(conditions, AND);
    }

    /**
     * Adds a group of conditions in parentheses; an empty group adds nothing.
     *
     * @param group fills a fresh builder with the group's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public Builder where(Consumer<Builder> group) {
        return whereNested(group, AND);
    }

    /**
     * Adds an {@code or} condition comparing a column with a value using {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhere(String column, Object value) {
        return addWhere(column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition comparing a column with a value.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orWhere(String column, String operator, Object value) {
        return addWhere(column, operator, value, OR);
    }

    /**
     * Adds an {@code or} group of {@code column = value} conditions. As in Laravel, the conditions
     * inside the group are joined by {@code or} too.
     *
     * @param conditions the columns and values, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code conditions} or a key is {@code null}
     * @throws IllegalArgumentException if a value is a list, map, array or builder
     */
    public Builder orWhere(Map<String, ?> conditions) {
        return addArrayOfWheres(conditions, OR);
    }

    /**
     * Adds an {@code or} group of conditions in parentheses.
     *
     * @param group fills a fresh builder with the group's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public Builder orWhere(Consumer<Builder> group) {
        return whereNested(group, OR);
    }

    /**
     * Adds a negated condition comparing a column with a value using {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder whereNot(String column, Object value) {
        return addWhere(column, "=", value, AND + NOT);
    }

    /**
     * Adds a negated condition comparing a column with a value.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder whereNot(String column, String operator, Object value) {
        return addWhere(column, operator, value, AND + NOT);
    }

    /**
     * Adds a negated group of {@code column = value} conditions.
     *
     * @param conditions the columns and values, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code conditions} or a key is {@code null}
     * @throws IllegalArgumentException if a value is a list, map, array or builder
     */
    public Builder whereNot(Map<String, ?> conditions) {
        Objects.requireNonNull(conditions, "conditions");
        return whereNested(query -> query.addArrayOfWheres(conditions, AND), AND + NOT);
    }

    /**
     * Adds a negated group of conditions in parentheses.
     *
     * @param group fills a fresh builder with the group's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public Builder whereNot(Consumer<Builder> group) {
        return whereNested(group, AND + NOT);
    }

    /**
     * Adds an {@code or not} condition comparing a column with a value using {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhereNot(String column, Object value) {
        return addWhere(column, "=", value, OR + NOT);
    }

    /**
     * Adds an {@code or not} condition comparing a column with a value.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orWhereNot(String column, String operator, Object value) {
        return addWhere(column, operator, value, OR + NOT);
    }

    /**
     * Adds an {@code or not} group of {@code column = value} conditions.
     *
     * @param conditions the columns and values, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code conditions} or a key is {@code null}
     * @throws IllegalArgumentException if a value is a list, map, array or builder
     */
    public Builder orWhereNot(Map<String, ?> conditions) {
        Objects.requireNonNull(conditions, "conditions");
        return whereNested(query -> query.addArrayOfWheres(conditions, OR), OR + NOT);
    }

    /**
     * Adds an {@code or not} group of conditions in parentheses.
     *
     * @param group fills a fresh builder with the group's conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public Builder orWhereNot(Consumer<Builder> group) {
        return whereNested(group, OR + NOT);
    }

    // ---- column, raw, like, null-safe ----

    /**
     * Adds a condition that two columns are equal.
     *
     * @param first the first column name, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder whereColumn(String first, String second) {
        return addWhereColumn(first, "=", second, AND);
    }

    /**
     * Adds a condition comparing two columns.
     *
     * @param first the first column name, never {@code null}
     * @param operator the operator, never {@code null}; an unknown one is taken as the second
     *     column, compared with {@code =}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder whereColumn(String first, String operator, String second) {
        return addWhereColumn(first, operator, second, AND);
    }

    /**
     * Adds an {@code or} condition that two columns are equal.
     *
     * @param first the first column name, never {@code null}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder orWhereColumn(String first, String second) {
        return addWhereColumn(first, "=", second, OR);
    }

    /**
     * Adds an {@code or} condition comparing two columns.
     *
     * @param first the first column name, never {@code null}
     * @param operator the operator, as for {@link #whereColumn(String, String, String)}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder orWhereColumn(String first, String operator, String second) {
        return addWhereColumn(first, operator, second, OR);
    }

    /**
     * Adds trusted SQL as a condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public Builder whereRaw(String sql) {
        return whereRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings as a condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder whereRaw(String sql, List<?> bindings) {
        return addWhereRaw(sql, bindings, AND);
    }

    /**
     * Adds trusted SQL as an {@code or} condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public Builder orWhereRaw(String sql) {
        return orWhereRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings as an {@code or} condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder orWhereRaw(String sql, List<?> bindings) {
        return addWhereRaw(sql, bindings, OR);
    }

    /**
     * Adds a case-insensitive {@code like} condition.
     *
     * @param column the column name, never {@code null}
     * @param value the pattern
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder whereLike(String column, Object value) {
        return addWhereLike(column, value, false, AND, false);
    }

    /**
     * Adds a {@code like} condition.
     *
     * @param column the column name, never {@code null}
     * @param value the pattern
     * @param caseSensitive whether the match respects case; the base grammar refuses {@code true}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder whereLike(String column, Object value, boolean caseSensitive) {
        return addWhereLike(column, value, caseSensitive, AND, false);
    }

    /**
     * Adds a case-insensitive {@code or like} condition.
     *
     * @param column the column name, never {@code null}
     * @param value the pattern
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orWhereLike(String column, Object value) {
        return addWhereLike(column, value, false, OR, false);
    }

    /**
     * Adds an {@code or like} condition.
     *
     * @param column the column name, never {@code null}
     * @param value the pattern
     * @param caseSensitive whether the match respects case
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orWhereLike(String column, Object value, boolean caseSensitive) {
        return addWhereLike(column, value, caseSensitive, OR, false);
    }

    /**
     * Adds a case-insensitive {@code not like} condition.
     *
     * @param column the column name, never {@code null}
     * @param value the pattern
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder whereNotLike(String column, Object value) {
        return addWhereLike(column, value, false, AND, true);
    }

    /**
     * Adds a {@code not like} condition.
     *
     * @param column the column name, never {@code null}
     * @param value the pattern
     * @param caseSensitive whether the match respects case
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder whereNotLike(String column, Object value, boolean caseSensitive) {
        return addWhereLike(column, value, caseSensitive, AND, true);
    }

    /**
     * Adds a case-insensitive {@code or not like} condition.
     *
     * @param column the column name, never {@code null}
     * @param value the pattern
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orWhereNotLike(String column, Object value) {
        return addWhereLike(column, value, false, OR, true);
    }

    /**
     * Adds an {@code or not like} condition.
     *
     * @param column the column name, never {@code null}
     * @param value the pattern
     * @param caseSensitive whether the match respects case
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orWhereNotLike(String column, Object value, boolean caseSensitive) {
        return addWhereLike(column, value, caseSensitive, OR, true);
    }

    /**
     * Adds a comparison that treats two nulls as equal.
     *
     * @param column the column name, never {@code null}
     * @param value the value, may be {@code null}, or an {@link Expression}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder whereNullSafeEquals(String column, Object value) {
        return addNullSafeEquals(column, value, AND);
    }

    /**
     * Adds an {@code or} comparison that treats two nulls as equal.
     *
     * @param column the column name, never {@code null}
     * @param value the value, as for {@link #whereNullSafeEquals}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhereNullSafeEquals(String column, Object value) {
        return addNullSafeEquals(column, value, OR);
    }

    // ---- in, null, between ----

    /**
     * Adds a condition that a column is in a list; an empty list matches no row.
     *
     * @param column the column name, never {@code null}
     * @param values the values, never {@code null}, copied; an {@link Expression} is written as
     *     given and not bound
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder whereIn(String column, Collection<?> values) {
        return addWhereIn(column, values, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column is in a list.
     *
     * @param column the column name, never {@code null}
     * @param values the values, as for {@link #whereIn}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder orWhereIn(String column, Collection<?> values) {
        return addWhereIn(column, values, OR, false);
    }

    /**
     * Adds a condition that a column is not in a list; an empty list matches every row.
     *
     * @param column the column name, never {@code null}
     * @param values the values, as for {@link #whereIn}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder whereNotIn(String column, Collection<?> values) {
        return addWhereIn(column, values, AND, true);
    }

    /**
     * Adds an {@code or} condition that a column is not in a list.
     *
     * @param column the column name, never {@code null}
     * @param values the values, as for {@link #whereIn}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder orWhereNotIn(String column, Collection<?> values) {
        return addWhereIn(column, values, OR, true);
    }

    /**
     * Adds a condition that each column is null.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public Builder whereNull(String... columns) {
        for (String column : columns) {
            wheres.add(new Where.Null(AND, Objects.requireNonNull(column, "column")));
        }
        return this;
    }

    /**
     * Adds an {@code or} condition that a column is null.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orWhereNull(String column) {
        wheres.add(new Where.Null(OR, Objects.requireNonNull(column, "column")));
        return this;
    }

    /**
     * Adds a condition that each column is not null.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public Builder whereNotNull(String... columns) {
        for (String column : columns) {
            wheres.add(new Where.NotNull(AND, Objects.requireNonNull(column, "column")));
        }
        return this;
    }

    /**
     * Adds an {@code or} condition that a column is not null.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orWhereNotNull(String column) {
        wheres.add(new Where.NotNull(OR, Objects.requireNonNull(column, "column")));
        return this;
    }

    /**
     * Adds a condition that a column is between two values, both included.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, never {@code null}, exactly two
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public Builder whereBetween(String column, List<?> values) {
        return addWhereBetween(column, values, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column is between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link #whereBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public Builder orWhereBetween(String column, List<?> values) {
        return addWhereBetween(column, values, OR, false);
    }

    /**
     * Adds a condition that a column is not between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link #whereBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public Builder whereNotBetween(String column, List<?> values) {
        return addWhereBetween(column, values, AND, true);
    }

    /**
     * Adds an {@code or} condition that a column is not between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link #whereBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public Builder orWhereNotBetween(String column, List<?> values) {
        return addWhereBetween(column, values, OR, true);
    }

    /**
     * Adds a condition that a column is between two other columns.
     *
     * @param column the column name, never {@code null}
     * @param columns the low and high column names, never {@code null}, exactly two
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns
     */
    public Builder whereBetweenColumns(String column, List<String> columns) {
        return addBetweenColumns(column, columns, AND, false);
    }

    /**
     * Adds an {@code or} condition that a column is between two other columns.
     *
     * @param column the column name, never {@code null}
     * @param columns the low and high column names, as for {@link #whereBetweenColumns}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns
     */
    public Builder orWhereBetweenColumns(String column, List<String> columns) {
        return addBetweenColumns(column, columns, OR, false);
    }

    /**
     * Adds a condition that a column is not between two other columns.
     *
     * @param column the column name, never {@code null}
     * @param columns the low and high column names, as for {@link #whereBetweenColumns}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns
     */
    public Builder whereNotBetweenColumns(String column, List<String> columns) {
        return addBetweenColumns(column, columns, AND, true);
    }

    /**
     * Adds an {@code or} condition that a column is not between two other columns.
     *
     * @param column the column name, never {@code null}
     * @param columns the low and high column names, as for {@link #whereBetweenColumns}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns
     */
    public Builder orWhereNotBetweenColumns(String column, List<String> columns) {
        return addBetweenColumns(column, columns, OR, true);
    }

    /**
     * Adds a condition that a value is between two columns.
     *
     * @param value the value, or an {@link Expression}
     * @param columns the low and high column names, never {@code null}, exactly two
     * @return this builder
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException if there are not exactly two columns, or the value is a
     *     list, map, array or builder
     */
    public Builder whereValueBetween(Object value, List<String> columns) {
        return addValueBetween(value, columns, AND, false);
    }

    /**
     * Adds an {@code or} condition that a value is between two columns.
     *
     * @param value the value, or an {@link Expression}
     * @param columns the low and high column names, as for {@link #whereValueBetween}
     * @return this builder
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereValueBetween}
     */
    public Builder orWhereValueBetween(Object value, List<String> columns) {
        return addValueBetween(value, columns, OR, false);
    }

    /**
     * Adds a condition that a value is not between two columns.
     *
     * @param value the value, or an {@link Expression}
     * @param columns the low and high column names, as for {@link #whereValueBetween}
     * @return this builder
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereValueBetween}
     */
    public Builder whereValueNotBetween(Object value, List<String> columns) {
        return addValueBetween(value, columns, AND, true);
    }

    /**
     * Adds an {@code or} condition that a value is not between two columns.
     *
     * @param value the value, or an {@link Expression}
     * @param columns the low and high column names, as for {@link #whereValueBetween}
     * @return this builder
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereValueBetween}
     */
    public Builder orWhereValueNotBetween(Object value, List<String> columns) {
        return addValueBetween(value, columns, OR, true);
    }

    /**
     * Adds a condition that a moment column lies in {@code [from, to)}: at or after {@code from}
     * and before {@code to}, in parentheses.
     *
     * <p>This is the way to filter an {@code instant} column. Laravel's date wheres compare the
     * stored text of a date and do not work on a column that stores a number of microseconds.
     * The grammar converts each {@code Instant} to what its database stores, and a plain range
     * can use an index on the column.
     *
     * @param column the column name, never {@code null}
     * @param from the first moment included, never {@code null}
     * @param to the first moment excluded, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder whereInstantBetween(String column, Instant from, Instant to) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return whereNested(query -> query.where(column, ">=", from).where(column, "<", to), AND);
    }

    // ---- date-based ----

    /**
     * Adds a condition on the date part of a text date column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the date; a {@code LocalDate} or other date is written as {@code yyyy-MM-dd}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder whereDate(String column, Object value) {
        return addDateBased(Where.DatePart.DATE, column, "=", value, AND);
    }

    /**
     * Adds a condition on the date part of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the date, as for {@link #whereDate(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder whereDate(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.DATE, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the date part of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param value the date, as for {@link #whereDate(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhereDate(String column, Object value) {
        return addDateBased(Where.DatePart.DATE, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the date part of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the date, as for {@link #whereDate(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orWhereDate(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.DATE, column, operator, value, OR);
    }

    /**
     * Adds a condition on the time part of a text time column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the time; a {@code LocalTime} or other time is written as {@code HH:mm:ss}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder whereTime(String column, Object value) {
        return addDateBased(Where.DatePart.TIME, column, "=", value, AND);
    }

    /**
     * Adds a condition on the time part of a text time column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the time, as for {@link #whereTime(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder whereTime(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.TIME, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the time part of a text time column.
     *
     * @param column the column name, never {@code null}
     * @param value the time, as for {@link #whereTime(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhereTime(String column, Object value) {
        return addDateBased(Where.DatePart.TIME, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the time part of a text time column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the time, as for {@link #whereTime(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orWhereTime(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.TIME, column, operator, value, OR);
    }

    /**
     * Adds a condition on the day of the month of a text date column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the day; a number or date is written as two digits
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is not a whole number or date
     */
    public Builder whereDay(String column, Object value) {
        return addDateBased(Where.DatePart.DAY, column, "=", value, AND);
    }

    /**
     * Adds a condition on the day of the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the day, as for {@link #whereDay(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereDay(String, Object)}
     */
    public Builder whereDay(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.DAY, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the day of the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param value the day, as for {@link #whereDay(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereDay(String, Object)}
     */
    public Builder orWhereDay(String column, Object value) {
        return addDateBased(Where.DatePart.DAY, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the day of the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the day, as for {@link #whereDay(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereDay(String, Object)}
     */
    public Builder orWhereDay(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.DAY, column, operator, value, OR);
    }

    /**
     * Adds a condition on the month of a text date column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the month; a number or date is written as two digits
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is not a whole number or date
     */
    public Builder whereMonth(String column, Object value) {
        return addDateBased(Where.DatePart.MONTH, column, "=", value, AND);
    }

    /**
     * Adds a condition on the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the month, as for {@link #whereMonth(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereMonth(String, Object)}
     */
    public Builder whereMonth(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.MONTH, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param value the month, as for {@link #whereMonth(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereMonth(String, Object)}
     */
    public Builder orWhereMonth(String column, Object value) {
        return addDateBased(Where.DatePart.MONTH, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the month of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the month, as for {@link #whereMonth(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #whereMonth(String, Object)}
     */
    public Builder orWhereMonth(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.MONTH, column, operator, value, OR);
    }

    /**
     * Adds a condition on the year of a text date column, compared with {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the year; a date is written as its year
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder whereYear(String column, Object value) {
        return addDateBased(Where.DatePart.YEAR, column, "=", value, AND);
    }

    /**
     * Adds a condition on the year of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the year, as for {@link #whereYear(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder whereYear(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.YEAR, column, operator, value, AND);
    }

    /**
     * Adds an {@code or} condition on the year of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param value the year, as for {@link #whereYear(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhereYear(String column, Object value) {
        return addDateBased(Where.DatePart.YEAR, column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition on the year of a text date column.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the year, as for {@link #whereYear(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orWhereYear(String column, String operator, Object value) {
        return addDateBased(Where.DatePart.YEAR, column, operator, value, OR);
    }

    // ---- all, any, none ----

    /**
     * Adds a group requiring every column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder whereAll(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, AND, AND);
    }

    /**
     * Adds a group requiring every column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder whereAll(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, AND, AND);
    }

    /**
     * Adds an {@code or} group requiring every column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhereAll(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, OR, AND);
    }

    /**
     * Adds an {@code or} group requiring every column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orWhereAll(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, OR, AND);
    }

    /**
     * Adds a group requiring at least one column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder whereAny(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, AND, OR);
    }

    /**
     * Adds a group requiring at least one column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder whereAny(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, AND, OR);
    }

    /**
     * Adds an {@code or} group requiring at least one column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhereAny(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, OR, OR);
    }

    /**
     * Adds an {@code or} group requiring at least one column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orWhereAny(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, OR, OR);
    }

    /**
     * Adds a group requiring no column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder whereNone(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, AND + NOT, OR);
    }

    /**
     * Adds a group requiring no column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder whereNone(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, AND + NOT, OR);
    }

    /**
     * Adds an {@code or} group requiring no column to equal the value.
     *
     * @param columns the column names, never {@code null}
     * @param value the value, as for {@link #where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orWhereNone(List<String> columns, Object value) {
        return addMultiColumn(columns, "=", value, OR + NOT, OR);
    }

    /**
     * Adds an {@code or} group requiring no column to match the operator and value.
     *
     * @param columns the column names, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if an argument other than {@code value} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orWhereNone(List<String> columns, String operator, Object value) {
        return addMultiColumn(columns, operator, value, OR + NOT, OR);
    }

    // ---- group by and having ----

    /**
     * Adds columns to the group by clause.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public Builder groupBy(String... columns) {
        for (String column : columns) {
            groups.add(Objects.requireNonNull(column, "column"));
        }
        return this;
    }

    /**
     * Adds trusted SQL to the group by clause.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public Builder groupByRaw(String sql) {
        return groupByRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings to the group by clause.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public Builder groupByRaw(String sql, List<?> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        groups.add(Expression.raw(sql));
        return addBindings(bindings, BindingType.GROUP_BY);
    }

    /**
     * Adds a having condition comparing a column with a value using {@code =}.
     *
     * @param column the column name or alias, never {@code null}
     * @param value the value, may be {@code null} (bound as is), or an {@link Expression}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder having(String column, Object value) {
        return addHaving(column, "=", value, AND);
    }

    /**
     * Adds a having condition comparing a column with a value.
     *
     * @param column the column name or alias, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder having(String column, String operator, Object value) {
        return addHaving(column, operator, value, AND);
    }

    /**
     * Adds an {@code or} having condition comparing a column with a value using {@code =}.
     *
     * @param column the column name or alias, never {@code null}
     * @param value the value, as for {@link #having(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public Builder orHaving(String column, Object value) {
        return addHaving(column, "=", value, OR);
    }

    /**
     * Adds an {@code or} having condition comparing a column with a value.
     *
     * @param column the column name or alias, never {@code null}
     * @param operator the operator, as for {@link #where(String, String, Object)}
     * @param value the value, as for {@link #where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link #where(String, String, Object)}
     */
    public Builder orHaving(String column, String operator, Object value) {
        return addHaving(column, operator, value, OR);
    }

    /**
     * Adds a group of having conditions in parentheses; an empty group adds nothing.
     *
     * @param group fills a fresh builder with the group's having conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public Builder havingNested(Consumer<Builder> group) {
        return addHavingNested(group, AND);
    }

    /**
     * Adds an {@code or} group of having conditions in parentheses.
     *
     * @param group fills a fresh builder with the group's having conditions, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public Builder orHavingNested(Consumer<Builder> group) {
        return addHavingNested(group, OR);
    }

    /**
     * Adds a having condition that each column is null.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public Builder havingNull(String... columns) {
        for (String column : columns) {
            havings.add(new Having.Null(AND, Objects.requireNonNull(column, "column")));
        }
        return this;
    }

    /**
     * Adds an {@code or} having condition that a column is null.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orHavingNull(String column) {
        havings.add(new Having.Null(OR, Objects.requireNonNull(column, "column")));
        return this;
    }

    /**
     * Adds a having condition that each column is not null.
     *
     * @param columns the column names, none {@code null}
     * @return this builder
     * @throws NullPointerException if a column is {@code null}
     */
    public Builder havingNotNull(String... columns) {
        for (String column : columns) {
            havings.add(new Having.NotNull(AND, Objects.requireNonNull(column, "column")));
        }
        return this;
    }

    /**
     * Adds an {@code or} having condition that a column is not null.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orHavingNotNull(String column) {
        havings.add(new Having.NotNull(OR, Objects.requireNonNull(column, "column")));
        return this;
    }

    /**
     * Adds a having condition that a column is between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, never {@code null}, exactly two
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public Builder havingBetween(String column, List<?> values) {
        return addHavingBetween(column, values, AND, false);
    }

    /**
     * Adds a having condition that a column is not between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link #havingBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public Builder havingNotBetween(String column, List<?> values) {
        return addHavingBetween(column, values, AND, true);
    }

    /**
     * Adds an {@code or} having condition that a column is between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link #havingBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public Builder orHavingBetween(String column, List<?> values) {
        return addHavingBetween(column, values, OR, false);
    }

    /**
     * Adds an {@code or} having condition that a column is not between two values.
     *
     * @param column the column name, never {@code null}
     * @param values the low and high value, as for {@link #havingBetween}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if there are not exactly two values
     */
    public Builder orHavingNotBetween(String column, List<?> values) {
        return addHavingBetween(column, values, OR, true);
    }

    /**
     * Adds trusted SQL as a having condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public Builder havingRaw(String sql) {
        return havingRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings as a having condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder havingRaw(String sql, List<?> bindings) {
        return addHavingRaw(sql, bindings, AND);
    }

    /**
     * Adds trusted SQL as an {@code or} having condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public Builder orHavingRaw(String sql) {
        return orHavingRaw(sql, Collections.emptyList());
    }

    /**
     * Adds trusted SQL with bindings as an {@code or} having condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder orHavingRaw(String sql, List<?> bindings) {
        return addHavingRaw(sql, bindings, OR);
    }

    // ---- order ----

    /**
     * Orders by a column, ascending.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orderBy(String column) {
        return orderBy(column, ASC);
    }

    /**
     * Orders by a column.
     *
     * @param column the column name, never {@code null}
     * @param direction {@code asc} or {@code desc} in any case, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the direction is neither {@code asc} nor {@code desc}
     */
    public Builder orderBy(String column, String direction) {
        Objects.requireNonNull(column, "column");
        String lower = Objects.requireNonNull(direction, "direction").toLowerCase(Locale.ROOT);
        if (!ASC.equals(lower) && !DESC.equals(lower)) {
            throw new IllegalArgumentException("Order direction must be \"asc\" or \"desc\"");
        }
        orders.add(new Order.Column(column, lower));
        return this;
    }

    /**
     * Orders by a column, descending.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder orderByDesc(String column) {
        return orderBy(column, DESC);
    }

    /**
     * Orders by {@code created_at}, newest first.
     *
     * @return this builder
     */
    public Builder latest() {
        return latest(DEFAULT_TIMESTAMP);
    }

    /**
     * Orders by a column, newest first.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder latest(String column) {
        return orderBy(column, DESC);
    }

    /**
     * Orders by {@code created_at}, oldest first.
     *
     * @return this builder
     */
    public Builder oldest() {
        return oldest(DEFAULT_TIMESTAMP);
    }

    /**
     * Orders by a column, oldest first.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder oldest(String column) {
        return orderBy(column, ASC);
    }

    /**
     * Orders randomly.
     *
     * @return this builder
     */
    public Builder inRandomOrder() {
        return inRandomOrder("");
    }

    /**
     * Orders randomly with a seed, where the grammar's database supports one.
     *
     * @param seed the seed, never {@code null}, empty for none; never user input
     * @return this builder
     * @throws NullPointerException if {@code seed} is {@code null}
     */
    public Builder inRandomOrder(String seed) {
        return orderByRaw(grammar.compileRandom(Objects.requireNonNull(seed, "seed")));
    }

    /**
     * Orders by trusted SQL.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public Builder orderByRaw(String sql) {
        return orderByRaw(sql, Collections.emptyList());
    }

    /**
     * Orders by trusted SQL with bindings.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @param bindings the values for the SQL's {@code ?} marks, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder orderByRaw(String sql, List<?> bindings) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(bindings, "bindings");
        orders.add(new Order.Raw(sql));
        return addBindings(bindings, BindingType.ORDER);
    }

    /**
     * Removes every order and its bindings.
     *
     * @return this builder
     */
    public Builder reorder() {
        orders.clear();
        bindings.get(BindingType.ORDER).clear();
        bindings.get(BindingType.UNION_ORDER).clear();
        return this;
    }

    /**
     * Replaces every order with one column, ascending.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder reorder(String column) {
        return reorder(column, ASC);
    }

    /**
     * Replaces every order with one column.
     *
     * @param column the column name, never {@code null}
     * @param direction {@code asc} or {@code desc}, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the direction is neither {@code asc} nor {@code desc}
     */
    public Builder reorder(String column, String direction) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(direction, "direction");
        return reorder().orderBy(column, direction);
    }

    /**
     * Replaces every order with one column, descending.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public Builder reorderDesc(String column) {
        return reorder(column, DESC);
    }

    // ---- limit and offset ----

    /**
     * Limits the number of rows; a negative value is ignored.
     *
     * @param value the maximum number of rows
     * @return this builder
     */
    public Builder limit(int value) {
        if (value >= 0) {
            this.limit = value;
        }
        return this;
    }

    /**
     * Limits the number of rows; an alias of {@link #limit(int)}.
     *
     * @param value the maximum number of rows
     * @return this builder
     */
    public Builder take(int value) {
        return limit(value);
    }

    /**
     * Skips rows; a negative value means zero.
     *
     * @param value the number of rows to skip
     * @return this builder
     */
    public Builder offset(int value) {
        this.offset = Math.max(0, value);
        return this;
    }

    /**
     * Skips rows; an alias of {@link #offset(int)}.
     *
     * @param value the number of rows to skip
     * @return this builder
     */
    public Builder skip(int value) {
        return offset(value);
    }

    /**
     * Selects one page of 15 rows.
     *
     * @param page the page number, at least 1
     * @return this builder
     * @throws IllegalArgumentException if {@code page} is below 1
     */
    public Builder forPage(int page) {
        return forPage(page, DEFAULT_PER_PAGE);
    }

    /**
     * Selects one page of rows. A query without a page or limit returns every row.
     *
     * @param page the page number, at least 1
     * @param perPage the rows per page, at least 1
     * @return this builder
     * @throws IllegalArgumentException if {@code page} or {@code perPage} is below 1
     * @throws ArithmeticException if the page's offset does not fit in an {@code int}
     */
    public Builder forPage(int page, int perPage) {
        if (page < 1 || perPage < 1) {
            throw new IllegalArgumentException("The page and the rows per page must be at least 1");
        }
        return offset(Math.multiplyExact(page - 1, perPage)).limit(perPage);
    }

    /**
     * Selects a page of rows whose {@code id} is below 0, highest first; Laravel's default last id.
     *
     * @param perPage the rows per page
     * @return this builder
     */
    public Builder forPageBeforeId(int perPage) {
        return forPageBeforeId(perPage, 0, DEFAULT_KEY);
    }

    /**
     * Selects a page of rows whose {@code id} is below the last one seen, highest first.
     *
     * @param perPage the rows per page
     * @param lastId the last id seen, or {@code null} for the first page
     * @return this builder
     */
    public Builder forPageBeforeId(int perPage, Object lastId) {
        return forPageBeforeId(perPage, lastId, DEFAULT_KEY);
    }

    /**
     * Selects a page of rows whose key is below the last one seen, highest first. Any existing
     * order on the key is replaced.
     *
     * @param perPage the rows per page
     * @param lastId the last key seen, or {@code null} for the first page
     * @param column the key column, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code lastId} is a list, map, array or builder
     */
    public Builder forPageBeforeId(int perPage, Object lastId, String column) {
        return forPageById(perPage, lastId, column, "<", DESC);
    }

    /**
     * Selects a page of rows whose {@code id} is above 0, lowest first; Laravel's default last id.
     *
     * @param perPage the rows per page
     * @return this builder
     */
    public Builder forPageAfterId(int perPage) {
        return forPageAfterId(perPage, 0, DEFAULT_KEY);
    }

    /**
     * Selects a page of rows whose {@code id} is above the last one seen, lowest first.
     *
     * @param perPage the rows per page
     * @param lastId the last id seen, or {@code null} for the first page
     * @return this builder
     */
    public Builder forPageAfterId(int perPage, Object lastId) {
        return forPageAfterId(perPage, lastId, DEFAULT_KEY);
    }

    /**
     * Selects a page of rows whose key is above the last one seen, lowest first. Any existing
     * order on the key is replaced.
     *
     * @param perPage the rows per page
     * @param lastId the last key seen, or {@code null} for the first page
     * @param column the key column, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code lastId} is a list, map, array or builder
     */
    public Builder forPageAfterId(int perPage, Object lastId, String column) {
        return forPageById(perPage, lastId, column, ">", ASC);
    }

    // ---- bindings and SQL ----

    /**
     * Adds a value to a binding bucket. An enum is bound by its name.
     *
     * @param value the value, may be {@code null}
     * @param type the bucket, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public Builder addBinding(Object value, BindingType type) {
        bindings.get(Objects.requireNonNull(type, "type")).add(Clauses.castBinding(value));
        return this;
    }

    /**
     * Adds values to a binding bucket, in order.
     *
     * @param values the values, never {@code null}, may contain {@code null}
     * @param type the bucket, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder addBindings(List<?> values, BindingType type) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(type, "type");
        for (Object value : values) {
            addBinding(value, type);
        }
        return this;
    }

    /**
     * Replaces the values of one binding bucket.
     *
     * @param values the values, never {@code null}, may contain {@code null}
     * @param type the bucket, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder setBindings(List<?> values, BindingType type) {
        Objects.requireNonNull(values, "values");
        bindings.get(Objects.requireNonNull(type, "type")).clear();
        return addBindings(values, type);
    }

    /**
     * Returns every binding, flattened in {@link BindingType} order, which is the order of the
     * {@code ?} marks in {@link #toSql()}. Values are as given, before the grammar prepares them.
     *
     * @return the bindings, unmodifiable, never {@code null}, may contain {@code null}
     */
    public List<Object> getBindings() {
        List<Object> flat = new ArrayList<>();
        for (List<Object> bucket : bindings.values()) {
            flat.addAll(bucket);
        }
        return Collections.unmodifiableList(flat);
    }

    /**
     * Returns the bindings by bucket.
     *
     * @return a snapshot holding every bucket in {@link BindingType} order, unmodifiable, never
     *     {@code null}
     */
    public Map<BindingType, List<Object>> getRawBindings() {
        EnumMap<BindingType, List<Object>> copy = new EnumMap<>(BindingType.class);
        for (Map.Entry<BindingType, List<Object>> entry : bindings.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Compiles the query to a select statement.
     *
     * @return the SQL with {@code ?} marks, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support a clause
     */
    public String toSql() {
        return grammar.compileSelect(this);
    }

    /**
     * Compiles the query with its bindings written in as literals, for display and logging only.
     *
     * <p>Never run the result: escaping a literal is weaker than binding it. Literals assume
     * standard SQL strings, where a backslash is an ordinary character, as on SQLite and on
     * PostgreSQL with {@code standard_conforming_strings} on.
     *
     * @return the SQL with values inlined, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support a clause or cannot
     *     write a binding as a literal
     * @throws IllegalArgumentException if a string binding holds a NUL character
     */
    public String toRawSql() {
        return grammar.substituteBindingsIntoRawSql(toSql(), grammar.prepareBindings(getBindings()));
    }

    /**
     * Wraps trusted SQL so it is written as given; a shortcut for {@link Expression#raw}.
     *
     * @param sql the SQL, never {@code null} or blank, never user input
     * @return the expression, never {@code null}
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public Expression raw(String sql) {
        return Expression.raw(sql);
    }

    /**
     * Returns an independent copy: changing either builder, including a nested group, never
     * changes the other.
     *
     * @return the copy, never {@code null}
     */
    @Override
    public Builder clone() {
        Builder copy = new Builder(connection, grammar, processor);
        for (BindingType type : BindingType.values()) {
            copy.bindings.get(type).addAll(bindings.get(type));
        }
        copy.columns = new ArrayList<>(columns);
        copy.distinct = distinct;
        copy.from = from;
        copy.wheres = new ArrayList<>(wheres.size());
        for (Where where : wheres) {
            copy.wheres.add(where instanceof Where.Nested
                    ? new Where.Nested(where.conjunction(), ((Where.Nested) where).builder().clone()) : where);
        }
        copy.groups = new ArrayList<>(groups);
        copy.havings = new ArrayList<>(havings.size());
        for (Having having : havings) {
            copy.havings.add(having instanceof Having.Nested
                    ? new Having.Nested(having.conjunction(), ((Having.Nested) having).builder().clone()) : having);
        }
        copy.orders = new ArrayList<>(orders);
        copy.limit = limit;
        copy.offset = offset;
        copy.aggregate = aggregate;
        return copy;
    }

    /**
     * Returns a new, empty builder on the same connection, grammar and processor.
     *
     * @return the new builder, never {@code null}
     */
    public Builder newQuery() {
        return new Builder(connection, grammar, processor);
    }

    // ---- reads ----

    /**
     * Runs the query and returns its rows.
     *
     * @return the rows as {@link Processor#processSelect} returns them, never {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     * @throws UnsupportedOperationException if the grammar does not support a clause
     */
    public List<Map<String, Object>> get() {
        return processor.processSelect(this, runSelect());
    }

    /**
     * Runs the query limited to one row, on a copy, and returns that row. Laravel sets the limit on
     * the builder itself; here the builder is left as it was.
     *
     * @return the first row, empty if there is none
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Optional<Map<String, Object>> first() {
        List<Map<String, Object>> rows = clone().limit(1).get();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Finds the row whose {@code id} equals the value, on a copy.
     *
     * @param id the key, never {@code null}
     * @return the row, empty if there is none
     * @throws NullPointerException if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} is a list, map, array or builder
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Optional<Map<String, Object>> find(Object id) {
        Objects.requireNonNull(id, "id");
        return clone().where(DEFAULT_KEY, "=", id).first();
    }

    /**
     * Returns one column of the first row. If the query selects no columns, only this one is
     * selected.
     *
     * @param column the column name, never {@code null}
     * @return the value, empty if there is no row or the value is SQL {@code NULL}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Optional<Object> value(String column) {
        Objects.requireNonNull(column, "column");
        Builder copy = clone();
        if (copy.columns.isEmpty()) {
            copy.columns.add(column);
        }
        return copy.first().map(row -> row.get(Results.resultName(column)));
    }

    /**
     * Returns one column of every row. If the query selects no columns, only this one is selected.
     *
     * @param column the column name, never {@code null}; a table prefix or alias is allowed
     * @return the values in row order, unmodifiable, never {@code null}, may contain {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public List<Object> pluck(String column) {
        Objects.requireNonNull(column, "column");
        String name = Results.resultName(column);
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> row : selectFor(column, null)) {
            values.add(row.get(name));
        }
        return Collections.unmodifiableList(values);
    }

    /**
     * Returns one column of every row, keyed by another column. A repeated key keeps the last value.
     *
     * @param column the value column, never {@code null}
     * @param key the key column, never {@code null}
     * @return the values by key in row order, unmodifiable, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Map<Object, Object> pluck(String column, String key) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(key, "key");
        String name = Results.resultName(column);
        String keyName = Results.resultName(key);
        Map<Object, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> row : selectFor(column, key)) {
            values.put(row.get(keyName), row.get(name));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * Returns whether any row matches.
     *
     * @return {@code true} if at least one row matches
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public boolean exists() {
        List<Map<String, Object>> rows = connection.select(grammar.compileExists(this),
                grammar.prepareBindings(getBindings()));
        if (rows.isEmpty()) {
            return false;
        }
        Object value = rows.get(0).get("exists");
        return value instanceof Boolean ? (Boolean) value : value instanceof Number && ((Number) value).intValue() != 0;
    }

    /**
     * Returns whether no row matches.
     *
     * @return {@code true} if no row matches
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public boolean doesntExist() {
        return !exists();
    }

    // ---- aggregates ----

    /**
     * Counts the matching rows. Runs on a copy without the selected columns, orders, limit and
     * offset, so an ordered or paged query still counts every match and the builder is unchanged.
     * A query with having clauses is counted whole, as a subquery, so its limit and offset apply.
     *
     * @return the count
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public long count() {
        return count("*");
    }

    /**
     * Counts the matching rows where a column is not null.
     *
     * @param column the column name, never {@code null}
     * @return the count
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public long count(String column) {
        return aggregate("count", Objects.requireNonNull(column, "column"))
                .map(value -> Results.number(value).longValue()).orElse(0L);
    }

    /**
     * Returns the smallest value of a column.
     *
     * @param column the column name, never {@code null}
     * @return the value as the database returned it, empty if no row matches
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Optional<Object> min(String column) {
        return aggregate("min", Objects.requireNonNull(column, "column"));
    }

    /**
     * Returns the largest value of a column.
     *
     * @param column the column name, never {@code null}
     * @return the value as the database returned it, empty if no row matches
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Optional<Object> max(String column) {
        return aggregate("max", Objects.requireNonNull(column, "column"));
    }

    /**
     * Returns the sum of a column; {@code 0} when no row matches, as Laravel does.
     *
     * @param column the column name, never {@code null}
     * @return the sum: a number as the driver returned it; text without a dot as a {@code Long},
     *     with one as a {@code BigDecimal}; {@code 0L} when no row matches
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the database returns text that is not a number
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Number sum(String column) {
        return aggregate("sum", Objects.requireNonNull(column, "column")).map(Results::number).orElse(0L);
    }

    /**
     * Returns the average of a column.
     *
     * @param column the column name, never {@code null}
     * @return the average, converted as for {@link #sum(String)}; empty if no row matches
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the database returns text that is not a number
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Optional<Number> avg(String column) {
        return aggregate("avg", Objects.requireNonNull(column, "column")).map(Results::number);
    }

    /**
     * Returns the average of a column; an alias of {@link #avg(String)}.
     *
     * @param column the column name, never {@code null}
     * @return the average, empty if no row matches
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the database returns text that is not a number
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public Optional<Number> average(String column) {
        return avg(column);
    }

    // ---- writes ----

    /**
     * Inserts one row.
     *
     * @param row the columns and values, never {@code null}; an {@link Expression} is written as
     *     given; an empty map inserts the defaults
     * @return what the connection returns for the insert
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public boolean insert(Map<String, ?> row) {
        return insert(Collections.singletonList(Objects.requireNonNull(row, "row")));
    }

    /**
     * Inserts several rows in one statement; no rows runs nothing and returns {@code true}.
     *
     * @param rows the rows, never {@code null}, all with the same columns
     * @return {@code true} for no rows, else what the connection returns for the insert
     * @throws NullPointerException if {@code rows} or a row is {@code null}
     * @throws IllegalArgumentException if the rows have different columns
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public boolean insert(List<? extends Map<String, ?>> rows) {
        Objects.requireNonNull(rows, "rows");
        Optional<String> sql = grammar.compileInsert(this, rows);
        if (!sql.isPresent()) {
            return true;
        }
        List<Object> values = new ArrayList<>();
        if (!rows.get(0).isEmpty()) {
            List<String> keys = grammar.insertColumns(rows);
            for (Map<String, ?> row : rows) {
                for (String key : keys) {
                    addInsertValue(values, row.get(key));
                }
            }
        }
        return connection.insert(sql.get(), grammar.prepareBindings(values));
    }

    /**
     * Inserts one row and returns its {@code id}.
     *
     * @param row the columns and values, never {@code null}
     * @return the new key, as for {@link #insertGetId(Map, String)}
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws UnsupportedOperationException if the processor cannot return a key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public Object insertGetId(Map<String, ?> row) {
        return insertGetId(row, DEFAULT_KEY);
    }

    /**
     * Inserts one row and returns the value of its key column, through the processor.
     *
     * @param row the columns and values, never {@code null}
     * @param sequence the key column, never {@code null}
     * @return the new key: a {@code Long} when numeric, else as the database returned it
     * @throws NullPointerException if an argument is {@code null}
     * @throws UnsupportedOperationException if the processor cannot return a key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public Object insertGetId(Map<String, ?> row, String sequence) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(sequence, "sequence");
        String sql = grammar.compileInsertGetId(this, row, sequence);
        List<Object> values = new ArrayList<>();
        for (Object value : row.values()) {
            addInsertValue(values, value);
        }
        return processor.processInsertGetId(this, sql, grammar.prepareBindings(values), sequence);
    }

    /**
     * Updates the matching rows.
     *
     * @param values the columns and new values, never {@code null} or empty; an
     *     {@link Expression} is written as given
     * @return the number of rows changed
     * @throws NullPointerException if {@code values} is {@code null}
     * @throws IllegalArgumentException if {@code values} is empty or a value is a list, map, array
     *     or builder
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int update(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        Map<String, Object> checked = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            checked.put(entry.getKey(), Clauses.checkValue(entry.getValue()));
        }
        String sql = grammar.compileUpdate(this, checked);
        return connection.update(sql,
                grammar.prepareBindings(grammar.prepareBindingsForUpdate(getRawBindings(), checked)));
    }

    /**
     * Adds 1 to a column of the matching rows.
     *
     * @param column the column name, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int increment(String column) {
        return increment(column, 1);
    }

    /**
     * Adds an amount to a column of the matching rows.
     *
     * @param column the column name, never {@code null}
     * @param amount the amount, a finite standard number type, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the amount is not finite or not a standard number type
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int increment(String column, Number amount) {
        return increment(column, amount, Collections.emptyMap());
    }

    /**
     * Adds an amount to a column of the matching rows and sets other columns too.
     *
     * @param column the column name, never {@code null}
     * @param amount the amount, as for {@link #increment(String, Number)}
     * @param extra other columns and values to set, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the amount is not finite or not a standard number type
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int increment(String column, Number amount, Map<String, ?> extra) {
        return incrementEach(singleAmount(column, amount), extra);
    }

    /**
     * Subtracts 1 from a column of the matching rows.
     *
     * @param column the column name, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int decrement(String column) {
        return decrement(column, 1);
    }

    /**
     * Subtracts an amount from a column of the matching rows.
     *
     * @param column the column name, never {@code null}
     * @param amount the amount, as for {@link #increment(String, Number)}
     * @return the number of rows changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the amount is not finite or not a standard number type
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int decrement(String column, Number amount) {
        return decrement(column, amount, Collections.emptyMap());
    }

    /**
     * Subtracts an amount from a column of the matching rows and sets other columns too.
     *
     * @param column the column name, never {@code null}
     * @param amount the amount, as for {@link #increment(String, Number)}
     * @param extra other columns and values to set, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the amount is not finite or not a standard number type
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int decrement(String column, Number amount, Map<String, ?> extra) {
        return decrementEach(singleAmount(column, amount), extra);
    }

    /**
     * Adds an amount to each of several columns of the matching rows.
     *
     * @param amounts the columns and amounts, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code amounts}, a column or an amount is {@code null}
     * @throws IllegalArgumentException if an amount is not finite or not a standard number type
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int incrementEach(Map<String, ? extends Number> amounts) {
        return incrementEach(amounts, Collections.emptyMap());
    }

    /**
     * Adds an amount to each of several columns and sets other columns too; an extra value for a
     * column also in {@code amounts} wins, as in Laravel.
     *
     * @param amounts the columns and amounts, never {@code null}
     * @param extra other columns and values to set, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if an argument, a column or an amount is {@code null}
     * @throws IllegalArgumentException if an amount is not finite or not a standard number type
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int incrementEach(Map<String, ? extends Number> amounts, Map<String, ?> extra) {
        return step(amounts, extra, " + ");
    }

    /**
     * Subtracts an amount from each of several columns of the matching rows.
     *
     * @param amounts the columns and amounts, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code amounts}, a column or an amount is {@code null}
     * @throws IllegalArgumentException if an amount is not finite or not a standard number type
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int decrementEach(Map<String, ? extends Number> amounts) {
        return decrementEach(amounts, Collections.emptyMap());
    }

    /**
     * Subtracts an amount from each of several columns and sets other columns too.
     *
     * @param amounts the columns and amounts, never {@code null}
     * @param extra other columns and values to set, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if an argument, a column or an amount is {@code null}
     * @throws IllegalArgumentException if an amount is not finite or not a standard number type
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public int decrementEach(Map<String, ? extends Number> amounts, Map<String, ?> extra) {
        return step(amounts, extra, " - ");
    }

    /**
     * Deletes the matching rows.
     *
     * @return the number of rows deleted
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the delete
     */
    public int delete() {
        return connection.delete(grammar.compileDelete(this),
                grammar.prepareBindings(grammar.prepareBindingsForDelete(getRawBindings())));
    }

    /**
     * Deletes the row whose {@code id} equals the value. The key is qualified with the table's
     * alias when it has one; Laravel qualifies it with the whole {@code "table as alias"} text,
     * which quotes to an invalid name.
     *
     * @param id the key, never {@code null}
     * @return the number of rows deleted
     * @throws NullPointerException if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} is a list, map, array or builder
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the delete
     */
    public int delete(Object id) {
        Objects.requireNonNull(id, "id");
        String table = from == null ? null : Results.resultName(from);
        return where(table == null ? DEFAULT_KEY : table + "." + DEFAULT_KEY, "=", id).delete();
    }

    /**
     * Empties the table. When the grammar needs several statements, they run in one transaction,
     * unless the connection is already inside one.
     *
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement;
     *     inside the transaction, every statement is then undone
     */
    public void truncate() {
        Map<String, List<Object>> statements = grammar.compileTruncate(this);
        if (statements.size() > 1 && connection.transactionLevel() == 0) {
            connection.transaction(tx -> {
                runAll(tx, statements);
                return null;
            });
        } else {
            runAll(connection, statements);
        }
    }

    private void runAll(Connection target, Map<String, List<Object>> statements) {
        for (Map.Entry<String, List<Object>> statement : statements.entrySet()) {
            target.statement(statement.getKey(), grammar.prepareBindings(statement.getValue()));
        }
    }

    private List<Map<String, Object>> runSelect() {
        return connection.select(toSql(), grammar.prepareBindings(getBindings()));
    }

    /** Runs a copy selecting only the plucked columns when the query selects none, as Laravel does. */
    private List<Map<String, Object>> selectFor(String column, String key) {
        Builder copy = clone();
        if (copy.columns.isEmpty()) {
            copy.columns.add(column);
            if (key != null && !key.equals(column)) {
                copy.columns.add(key);
            }
        }
        return copy.get();
    }

    /**
     * Runs an aggregate on a copy only, so the builder is untouched even when the query fails.
     * Laravel drops the columns and select bindings, and the orders when there are no groups. With
     * no havings the limit and offset are dropped too, since Laravel's kept offset makes
     * {@code count()} on a later page return nothing. With havings everything is kept: the query
     * becomes a subquery, where the limit and offset apply to the groups before they are counted.
     */
    private Optional<Object> aggregate(String function, String column) {
        Builder copy = clone();
        if (havings.isEmpty()) {
            copy.columns.clear();
            copy.bindings.get(BindingType.SELECT).clear();
        }
        if (groups.isEmpty()) {
            copy.orders.clear();
            copy.bindings.get(BindingType.ORDER).clear();
        }
        if (havings.isEmpty()) {
            copy.limit = null;
            copy.offset = null;
        }
        copy.setAggregate(function, Collections.<Object>singletonList(column));
        return Results.aggregate(copy.get());
    }

    private static Map<String, Number> singleAmount(String column, Number amount) {
        Map<String, Number> amounts = new LinkedHashMap<>();
        amounts.put(Objects.requireNonNull(column, "column"), Objects.requireNonNull(amount, "amount"));
        return amounts;
    }

    private int step(Map<String, ? extends Number> amounts, Map<String, ?> extra, String operator) {
        Objects.requireNonNull(amounts, "amounts");
        Objects.requireNonNull(extra, "extra");
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Number> entry : amounts.entrySet()) {
            String column = Objects.requireNonNull(entry.getKey(), "column");
            String amount = Results.amount(Objects.requireNonNull(entry.getValue(), "amount"));
            // The amount is written into the SQL; Results.amount only lets a plain number through.
            values.put(column, Expression.raw(grammar.wrap(column) + operator + amount));
        }
        values.putAll(extra);
        return update(values);
    }

    private static void addInsertValue(List<Object> values, Object value) {
        Object checked = Clauses.checkValue(value);
        if (!(checked instanceof Expression)) {
            values.add(checked);
        }
    }

    // ---- state for grammars ----

    /**
     * Returns the connection.
     *
     * @return the connection, never {@code null}
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * Returns the grammar.
     *
     * @return the grammar, never {@code null}
     */
    public QueryGrammar getGrammar() {
        return grammar;
    }

    /**
     * Returns the processor.
     *
     * @return the processor, never {@code null}
     */
    public Processor getProcessor() {
        return processor;
    }

    /**
     * Returns the selected columns.
     *
     * @return the columns, each a {@code String} or {@link Expression}, unmodifiable, never
     *     {@code null}; empty when none were selected, which compiles to {@code *}
     */
    public List<Object> getColumns() {
        return Collections.unmodifiableList(new ArrayList<>(columns));
    }

    /**
     * Returns whether the query returns only distinct rows.
     *
     * @return {@code true} if {@link #distinct()} was called
     */
    public boolean isDistinct() {
        return distinct;
    }

    /**
     * Returns the table.
     *
     * @return the table as given, possibly with {@code " as alias"}, or {@code null} if none
     */
    public String getFrom() {
        return from;
    }

    /**
     * Returns the where conditions.
     *
     * @return the conditions in the order added, unmodifiable, never {@code null}
     */
    public List<Where> getWheres() {
        return Collections.unmodifiableList(new ArrayList<>(wheres));
    }

    /**
     * Returns the group by entries.
     *
     * @return the entries, each a {@code String} or {@link Expression}, unmodifiable, never
     *     {@code null}
     */
    public List<Object> getGroups() {
        return Collections.unmodifiableList(new ArrayList<>(groups));
    }

    /**
     * Returns the having conditions.
     *
     * @return the conditions in the order added, unmodifiable, never {@code null}
     */
    public List<Having> getHavings() {
        return Collections.unmodifiableList(new ArrayList<>(havings));
    }

    /**
     * Returns the orders.
     *
     * @return the orders in the order added, unmodifiable, never {@code null}
     */
    public List<Order> getOrders() {
        return Collections.unmodifiableList(new ArrayList<>(orders));
    }

    /**
     * Returns the row limit.
     *
     * @return the limit, empty if none
     */
    public OptionalInt getLimit() {
        return limit == null ? OptionalInt.empty() : OptionalInt.of(limit);
    }

    /**
     * Returns the number of rows skipped.
     *
     * @return the offset, empty if none
     */
    public OptionalInt getOffset() {
        return offset == null ? OptionalInt.empty() : OptionalInt.of(offset);
    }

    /**
     * Returns the aggregate the query computes instead of its columns.
     *
     * @return the aggregate, empty for a plain select
     */
    public Optional<Aggregate> getAggregate() {
        return Optional.ofNullable(aggregate);
    }

    /** Sets the aggregate; the execution methods set it on a clone and never on the caller's builder. */
    void setAggregate(String function, List<Object> aggregateColumns) {
        this.aggregate = new Aggregate(function, aggregateColumns);
    }

    /** Returns a clone that computes its columns instead of the aggregate. */
    Builder withoutAggregate() {
        Builder copy = clone();
        copy.aggregate = null;
        return copy;
    }

    // ---- internals ----

    private Builder addWhere(String column, String operator, Object value, String conjunction) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        Clauses.checkNullOperator(operator, value);
        String checkedOperator = operator;
        Object checkedValue = value;
        // An operator nobody knows is the caller's value, compared with "=": Laravel's shortcut,
        // and the reason an operator can never inject SQL. A known one is written as the
        // allowlist spells it, never as the caller typed it.
        Optional<String> canonical = Clauses.canonicalOperator(operator, grammar);
        if (canonical.isPresent()) {
            checkedOperator = canonical.get();
        } else {
            checkedValue = operator;
            checkedOperator = "=";
        }
        checkedValue = Clauses.checkValue(checkedValue);
        if (checkedValue == null) {
            boolean isEquality = "=".equals(checkedOperator) || "<=>".equals(checkedOperator);
            wheres.add(isEquality ? new Where.Null(conjunction, column) : new Where.NotNull(conjunction, column));
            return this;
        }
        wheres.add("<=>".equals(checkedOperator)
                ? new Where.NullSafeEquals(conjunction, column, checkedValue)
                : new Where.Basic(conjunction, column, checkedOperator, checkedValue));
        return bindUnlessExpression(checkedValue, BindingType.WHERE);
    }

    private Builder addArrayOfWheres(Map<String, ?> conditions, String conjunction) {
        Objects.requireNonNull(conditions, "conditions");
        return whereNested(query -> {
            for (Map.Entry<String, ?> entry : conditions.entrySet()) {
                query.addWhere(entry.getKey(), "=", entry.getValue(), conjunction);
            }
        }, conjunction);
    }

    private Builder whereNested(Consumer<Builder> group, String conjunction) {
        Objects.requireNonNull(group, "group");
        Builder query = forNestedWhere();
        group.accept(query);
        if (!query.wheres.isEmpty()) {
            wheres.add(new Where.Nested(conjunction, query));
            bindings.get(BindingType.WHERE).addAll(query.bindings.get(BindingType.WHERE));
        }
        return this;
    }

    private Builder forNestedWhere() {
        Builder query = newQuery();
        query.from = from;
        return query;
    }

    private Builder addWhereColumn(String first, String operator, String second, String conjunction) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(second, "second");
        Optional<String> canonical = Clauses.canonicalOperator(operator, grammar);
        wheres.add(canonical.isPresent() ? new Where.Column(conjunction, first, canonical.get(), second)
                : new Where.Column(conjunction, first, "=", operator));
        return this;
    }

    private Builder addWhereRaw(String sql, List<?> values, String conjunction) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(values, "bindings");
        wheres.add(new Where.Raw(conjunction, sql));
        return addBindings(values, BindingType.WHERE);
    }

    private Builder addWhereLike(String column, Object value, boolean caseSensitive, String conjunction,
            boolean not) {
        Objects.requireNonNull(column, "column");
        Object checked = Clauses.checkValue(value);
        wheres.add(new Where.Like(conjunction, column, checked, caseSensitive, not));
        return bindUnlessExpression(checked, BindingType.WHERE);
    }

    private Builder addNullSafeEquals(String column, Object value, String conjunction) {
        Objects.requireNonNull(column, "column");
        Object checked = Clauses.checkValue(value);
        wheres.add(new Where.NullSafeEquals(conjunction, column, checked));
        return bindUnlessExpression(checked, BindingType.WHERE);
    }

    private Builder addWhereIn(String column, Collection<?> values, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(values, "values");
        List<Object> copy = new ArrayList<>(values.size());
        for (Object value : values) {
            copy.add(Clauses.checkValue(value));
        }
        wheres.add(not ? new Where.NotIn(conjunction, column, copy) : new Where.In(conjunction, column, copy));
        for (Object value : copy) {
            bindUnlessExpression(value, BindingType.WHERE);
        }
        return this;
    }

    private Builder addWhereBetween(String column, List<?> values, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        List<Object> copy = checkedPair(values);
        wheres.add(new Where.Between(conjunction, column, copy, not));
        for (Object value : copy) {
            bindUnlessExpression(value, BindingType.WHERE);
        }
        return this;
    }

    private Builder addBetweenColumns(String column, List<String> columns, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        Clauses.requireTwo(Objects.requireNonNull(columns, "columns"), "A between");
        wheres.add(new Where.BetweenColumns(conjunction, column, columns, not));
        return this;
    }

    private Builder addValueBetween(Object value, List<String> columns, String conjunction, boolean not) {
        Clauses.requireTwo(Objects.requireNonNull(columns, "columns"), "A between");
        Object checked = Clauses.checkValue(value);
        wheres.add(new Where.ValueBetween(conjunction, checked, columns, not));
        return bindUnlessExpression(checked, BindingType.WHERE);
    }

    private Builder addDateBased(Where.DatePart part, String column, String operator, Object value,
            String conjunction) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        Clauses.checkNullOperator(operator, value);
        String checkedOperator = operator;
        Object checkedValue = value;
        Optional<String> canonical = Clauses.canonicalOperator(operator, grammar);
        if (canonical.isPresent()) {
            checkedOperator = canonical.get();
        } else {
            checkedValue = operator;
            checkedOperator = "=";
        }
        Object formatted = Clauses.datePart(part, Clauses.checkValue(checkedValue));
        wheres.add(new Where.DateBased(conjunction, part, column, checkedOperator, formatted));
        return bindUnlessExpression(formatted, BindingType.WHERE);
    }

    private Builder addMultiColumn(List<String> columns, String operator, Object value, String conjunction,
            String inner) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(operator, "operator");
        Clauses.checkNullOperator(operator, value);
        return whereNested(query -> {
            for (String column : columns) {
                query.addWhere(column, operator, value, inner);
            }
        }, conjunction);
    }

    private Builder addHaving(String column, String operator, Object value, String conjunction) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        Clauses.checkNullOperator(operator, value);
        String checkedOperator = operator;
        Object checkedValue = value;
        Optional<String> canonical = Clauses.canonicalOperator(operator, grammar);
        if (canonical.isPresent()) {
            checkedOperator = canonical.get();
        } else {
            checkedValue = operator;
            checkedOperator = "=";
        }
        checkedValue = Clauses.checkValue(checkedValue);
        havings.add(new Having.Basic(conjunction, column, checkedOperator, checkedValue));
        return bindUnlessExpression(checkedValue, BindingType.HAVING);
    }

    private Builder addHavingNested(Consumer<Builder> group, String conjunction) {
        Objects.requireNonNull(group, "group");
        Builder query = forNestedWhere();
        group.accept(query);
        if (!query.havings.isEmpty()) {
            havings.add(new Having.Nested(conjunction, query));
            bindings.get(BindingType.HAVING).addAll(query.bindings.get(BindingType.HAVING));
        }
        return this;
    }

    private Builder addHavingBetween(String column, List<?> values, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        List<Object> copy = checkedPair(values);
        havings.add(new Having.Between(conjunction, column, copy, not));
        for (Object value : copy) {
            bindUnlessExpression(value, BindingType.HAVING);
        }
        return this;
    }

    private Builder addHavingRaw(String sql, List<?> values, String conjunction) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(values, "bindings");
        havings.add(new Having.Raw(conjunction, sql));
        return addBindings(values, BindingType.HAVING);
    }

    private Builder forPageById(int perPage, Object lastId, String column, String operator, String direction) {
        Objects.requireNonNull(column, "column");
        orders.removeIf(order -> order instanceof Order.Column && ((Order.Column) order).column().equals(column));
        if (lastId == null) {
            whereNotNull(column);
        } else {
            where(column, operator, lastId);
        }
        return orderBy(column, direction).limit(perPage);
    }

    private Builder bindUnlessExpression(Object value, BindingType type) {
        if (!(value instanceof Expression)) {
            bindings.get(type).add(value);
        }
        return this;
    }

    private static List<Object> checkedPair(List<?> values) {
        Clauses.requireTwo(Objects.requireNonNull(values, "values"), "A between");
        List<Object> copy = new ArrayList<>(2);
        for (Object value : values) {
            copy.add(Clauses.checkValue(value));
        }
        return copy;
    }

    private static List<Object> checkColumns(Object[] columns) {
        List<Object> checked = new ArrayList<>(columns.length);
        for (Object column : columns) {
            Objects.requireNonNull(column, "column");
            if (!(column instanceof String) && !(column instanceof Expression)) {
                throw new IllegalArgumentException("A column is a String or an Expression, not "
                        + column.getClass().getSimpleName());
            }
            checked.add(column);
        }
        return checked;
    }
}
