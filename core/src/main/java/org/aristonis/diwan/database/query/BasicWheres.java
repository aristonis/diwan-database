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
 * The basic where layer of {@link Builder}: plain, nested, column, raw, like and null-safe conditions.
 */
abstract class BasicWheres extends BuilderState {

    BasicWheres(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- basic where ----

    /**
     * Adds a condition comparing a column with a value using {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the value, may be {@code null} (compiles to {@code is null}), an
     *     {@link Expression}, or a {@link Builder} compared as a subquery: {@code "a" = (select ...)},
     *     a private copy taken now
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map or array
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
     *     {@code <=>}, an {@link Expression}, or a {@link Builder} compared as a subquery, as for
     *     {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the value is {@code null} with another known operator,
     *     or is a list, map or array
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
     * @throws IllegalArgumentException if a value is a list, map or array
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
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map or array
     */
    public Builder orWhere(String column, Object value) {
        return addWhere(column, "=", value, OR);
    }

    /**
     * Adds an {@code or} condition comparing a column with a value.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
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
     * @throws IllegalArgumentException if a value is a list, map or array
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
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map or array
     */
    public final Builder whereNot(String column, Object value) {
        return addWhere(column, "=", value, AND + NOT);
    }

    /**
     * Adds a negated condition comparing a column with a value.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder whereNot(String column, String operator, Object value) {
        return addWhere(column, operator, value, AND + NOT);
    }

    /**
     * Adds a negated group of {@code column = value} conditions.
     *
     * @param conditions the columns and values, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code conditions} or a key is {@code null}
     * @throws IllegalArgumentException if a value is a list, map or array
     */
    public final Builder whereNot(Map<String, ?> conditions) {
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
    public final Builder whereNot(Consumer<Builder> group) {
        return whereNested(group, AND + NOT);
    }

    /**
     * Adds an {@code or not} condition comparing a column with a value using {@code =}.
     *
     * @param column the column name, never {@code null}
     * @param value the value, as for {@link Builder#where(String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map or array
     */
    public final Builder orWhereNot(String column, Object value) {
        return addWhere(column, "=", value, OR + NOT);
    }

    /**
     * Adds an {@code or not} condition comparing a column with a value.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, as for {@link Builder#where(String, String, Object)}
     * @param value the value, as for {@link Builder#where(String, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException as for {@link Builder#where(String, String, Object)}
     */
    public final Builder orWhereNot(String column, String operator, Object value) {
        return addWhere(column, operator, value, OR + NOT);
    }

    /**
     * Adds an {@code or not} group of {@code column = value} conditions.
     *
     * @param conditions the columns and values, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code conditions} or a key is {@code null}
     * @throws IllegalArgumentException if a value is a list, map or array
     */
    public final Builder orWhereNot(Map<String, ?> conditions) {
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
    public final Builder orWhereNot(Consumer<Builder> group) {
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
    public final Builder whereColumn(String first, String second) {
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
    public final Builder whereColumn(String first, String operator, String second) {
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
    public final Builder orWhereColumn(String first, String second) {
        return addWhereColumn(first, "=", second, OR);
    }

    /**
     * Adds an {@code or} condition comparing two columns.
     *
     * @param first the first column name, never {@code null}
     * @param operator the operator, as for {@link Builder#whereColumn(String, String, String)}
     * @param second the second column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder orWhereColumn(String first, String operator, String second) {
        return addWhereColumn(first, operator, second, OR);
    }

    /**
     * Adds trusted SQL as a condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public final Builder whereRaw(String sql) {
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
    public final Builder whereRaw(String sql, List<?> bindings) {
        return addWhereRaw(sql, bindings, AND);
    }

    /**
     * Adds trusted SQL as an {@code or} condition.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public final Builder orWhereRaw(String sql) {
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
    public final Builder orWhereRaw(String sql, List<?> bindings) {
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
    public final Builder whereLike(String column, Object value) {
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
    public final Builder whereLike(String column, Object value, boolean caseSensitive) {
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
    public final Builder orWhereLike(String column, Object value) {
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
    public final Builder orWhereLike(String column, Object value, boolean caseSensitive) {
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
    public final Builder whereNotLike(String column, Object value) {
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
    public final Builder whereNotLike(String column, Object value, boolean caseSensitive) {
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
    public final Builder orWhereNotLike(String column, Object value) {
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
    public final Builder orWhereNotLike(String column, Object value, boolean caseSensitive) {
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
    public final Builder whereNullSafeEquals(String column, Object value) {
        return addNullSafeEquals(column, value, AND);
    }

    /**
     * Adds an {@code or} comparison that treats two nulls as equal.
     *
     * @param column the column name, never {@code null}
     * @param value the value, as for {@link Builder#whereNullSafeEquals}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the value is a list, map, array or builder
     */
    public final Builder orWhereNullSafeEquals(String column, Object value) {
        return addNullSafeEquals(column, value, OR);
    }

    Builder addWhere(String column, String operator, Object value, String conjunction) {
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
        if (checkedValue instanceof Builder) {
            return addWhereSub(column, checkedOperator, frozen((Builder) checkedValue), conjunction);
        }
        checkedValue = Clauses.checkValue(checkedValue);
        if (checkedValue == null) {
            boolean isEquality = "=".equals(checkedOperator) || "<=>".equals(checkedOperator);
            wheres.add(isEquality ? new Where.Null(conjunction, column) : new Where.NotNull(conjunction, column));
            return self();
        }
        wheres.add("<=>".equals(checkedOperator)
                ? new Where.NullSafeEquals(conjunction, column, checkedValue)
                : new Where.Basic(conjunction, column, checkedOperator, checkedValue));
        return bindUnlessExpression(checkedValue, BindingType.WHERE);
    }

    /** Adds {@code column operator (subquery)}; the subquery's bindings go to the where bucket now. */
    Builder addWhereSub(String column, String operator, Builder query, String conjunction) {
        wheres.add(new WhereSub(conjunction, column, operator, query));
        bindings.get(BindingType.WHERE).addAll(query.getBindings());
        return self();
    }

    Builder addArrayOfWheres(Map<String, ?> conditions, String conjunction) {
        Objects.requireNonNull(conditions, "conditions");
        return whereNested(query -> {
            for (Map.Entry<String, ?> entry : conditions.entrySet()) {
                query.addWhere(entry.getKey(), "=", entry.getValue(), conjunction);
            }
        }, conjunction);
    }

    Builder whereNested(Consumer<Builder> group, String conjunction) {
        Objects.requireNonNull(group, "group");
        Builder query = forNestedWhere();
        group.accept(query);
        if (!query.wheres.isEmpty()) {
            wheres.add(new Where.Nested(conjunction, query));
            bindings.get(BindingType.WHERE).addAll(query.bindings.get(BindingType.WHERE));
        }
        return self();
    }

    Builder forNestedWhere() {
        Builder query = self().newQuery();
        query.from = from;
        return query;
    }

    Builder addWhereColumn(String first, String operator, String second, String conjunction) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(second, "second");
        Optional<String> canonical = Clauses.canonicalOperator(operator, grammar);
        wheres.add(canonical.isPresent() ? new Where.Column(conjunction, first, canonical.get(), second)
                : new Where.Column(conjunction, first, "=", operator));
        return self();
    }

    Builder addWhereRaw(String sql, List<?> values, String conjunction) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(values, "bindings");
        wheres.add(new Where.Raw(conjunction, sql));
        return addBindings(values, BindingType.WHERE);
    }

    Builder addWhereLike(String column, Object value, boolean caseSensitive, String conjunction,
            boolean not) {
        Objects.requireNonNull(column, "column");
        Object checked = Clauses.checkValue(value);
        wheres.add(new Where.Like(conjunction, column, checked, caseSensitive, not));
        return bindUnlessExpression(checked, BindingType.WHERE);
    }

    Builder addNullSafeEquals(String column, Object value, String conjunction) {
        Objects.requireNonNull(column, "column");
        Object checked = Clauses.checkValue(value);
        wheres.add(new Where.NullSafeEquals(conjunction, column, checked));
        return bindUnlessExpression(checked, BindingType.WHERE);
    }
}
