package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;

/**
 * The subquery where layer of {@link Builder}: a column against a subquery, a subquery against a
 * value, {@code in} and {@code exists} subqueries, and row values.
 *
 * <p>Every subquery is taken as a private copy when it is attached, and its bindings go to the
 * where bucket at that moment, so changing the caller's builder later changes nothing here and
 * the SQL always matches its bindings.
 * A {@code Consumer<Builder>} fills a fresh plain builder on the same connection.
 */
abstract class SubqueryWheres extends DateWheres {

    SubqueryWheres(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- column compared with a subquery ----

    /**
     * Adds a condition comparing a column with the value a subquery returns:
     * {@code "a" = (select ...)}.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, never {@code null}; an unknown one is taken as the value
     * @param query fills a fresh builder with the subquery; {@code null} compares with
     *     {@code null}, as {@link Builder#where(String, String, Object)} does
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if {@code query} is {@code null} with an operator that
     *     cannot compare with {@code null}
     */
    public final Builder where(String column, String operator, Consumer<Builder> query) {
        return query == null ? addWhere(column, operator, null, AND) : addWhere(column, operator, subQuery(query), AND);
    }

    /**
     * Adds an {@code or} condition comparing a column with the value a subquery returns.
     *
     * @param column the column name, never {@code null}
     * @param operator the operator, never {@code null}
     * @param query fills a fresh builder with the subquery, as for
     *     {@link Builder#where(String, String, Consumer)}
     * @return this builder
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if {@code query} is {@code null} with an operator that
     *     cannot compare with {@code null}
     */
    public final Builder orWhere(String column, String operator, Consumer<Builder> query) {
        return query == null ? addWhere(column, operator, null, OR) : addWhere(column, operator, subQuery(query), OR);
    }

    // ---- subquery compared with a value ----

    /**
     * Adds a condition comparing the value a subquery returns with a value:
     * {@code (select ...) > ?}. The subquery's bindings come before the value's.
     *
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @param operator the operator, never {@code null}; an unknown one is taken as the value
     * @param value the value, may be {@code null} with {@code =} or {@code !=} (compiles to
     *     {@code is null} or {@code is not null}), or an {@link Expression}
     * @return this builder
     * @throws NullPointerException if {@code query} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the value is {@code null} with another known operator,
     *     or is a list, map, array or builder
     */
    public final Builder where(Builder query, String operator, Object value) {
        return addWhereSubValue(frozen(query), operator, value, AND);
    }

    /**
     * Adds a condition comparing the value of a subquery built by a consumer with a value.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param operator the operator, never {@code null}
     * @param value the value, as for {@link Builder#where(Builder, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code query} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the value is {@code null} with another known operator,
     *     or is a list, map, array or builder
     */
    public final Builder where(Consumer<Builder> query, String operator, Object value) {
        return addWhereSubValue(subQuery(query), operator, value, AND);
    }

    /**
     * Adds an {@code or} condition comparing the value a subquery returns with a value.
     *
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @param operator the operator, never {@code null}
     * @param value the value, as for {@link Builder#where(Builder, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code query} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the value is {@code null} with another known operator,
     *     or is a list, map, array or builder
     */
    public final Builder orWhere(Builder query, String operator, Object value) {
        return addWhereSubValue(frozen(query), operator, value, OR);
    }

    /**
     * Adds an {@code or} condition comparing the value of a subquery built by a consumer with a
     * value.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param operator the operator, never {@code null}
     * @param value the value, as for {@link Builder#where(Builder, String, Object)}
     * @return this builder
     * @throws NullPointerException if {@code query} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the value is {@code null} with another known operator,
     *     or is a list, map, array or builder
     */
    public final Builder orWhere(Consumer<Builder> query, String operator, Object value) {
        return addWhereSubValue(subQuery(query), operator, value, OR);
    }

    // ---- in a subquery ----

    /**
     * Adds a condition that a column is in the rows of a subquery: {@code "a" in (select ...)}.
     *
     * @param column the column name, never {@code null}
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder whereIn(String column, Builder query) {
        return addWhereInSub(column, frozen(query), AND, false);
    }

    /**
     * Adds a condition that a column is in the rows of a subquery built by a consumer.
     *
     * @param column the column name, never {@code null}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder whereIn(String column, Consumer<Builder> query) {
        return addWhereInSub(column, subQuery(query), AND, false);
    }

    /**
     * Adds an {@code or} condition that a column is in the rows of a subquery.
     *
     * @param column the column name, never {@code null}
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder orWhereIn(String column, Builder query) {
        return addWhereInSub(column, frozen(query), OR, false);
    }

    /**
     * Adds an {@code or} condition that a column is in the rows of a subquery built by a consumer.
     *
     * @param column the column name, never {@code null}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder orWhereIn(String column, Consumer<Builder> query) {
        return addWhereInSub(column, subQuery(query), OR, false);
    }

    /**
     * Adds a condition that a column is not in the rows of a subquery.
     *
     * @param column the column name, never {@code null}
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder whereNotIn(String column, Builder query) {
        return addWhereInSub(column, frozen(query), AND, true);
    }

    /**
     * Adds a condition that a column is not in the rows of a subquery built by a consumer.
     *
     * @param column the column name, never {@code null}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder whereNotIn(String column, Consumer<Builder> query) {
        return addWhereInSub(column, subQuery(query), AND, true);
    }

    /**
     * Adds an {@code or} condition that a column is not in the rows of a subquery.
     *
     * @param column the column name, never {@code null}
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder orWhereNotIn(String column, Builder query) {
        return addWhereInSub(column, frozen(query), OR, true);
    }

    /**
     * Adds an {@code or} condition that a column is not in the rows of a subquery built by a
     * consumer.
     *
     * @param column the column name, never {@code null}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final Builder orWhereNotIn(String column, Consumer<Builder> query) {
        return addWhereInSub(column, subQuery(query), OR, true);
    }

    // ---- exists ----

    /**
     * Adds a condition that a subquery returns at least one row: {@code exists (select ...)}.
     *
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder whereExists(Builder query) {
        return addWhereExists(frozen(query), AND, false);
    }

    /**
     * Adds a condition that a subquery built by a consumer returns at least one row.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder whereExists(Consumer<Builder> query) {
        return addWhereExists(subQuery(query), AND, false);
    }

    /**
     * Adds an {@code or} condition that a subquery returns at least one row.
     *
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder orWhereExists(Builder query) {
        return addWhereExists(frozen(query), OR, false);
    }

    /**
     * Adds an {@code or} condition that a subquery built by a consumer returns at least one row.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder orWhereExists(Consumer<Builder> query) {
        return addWhereExists(subQuery(query), OR, false);
    }

    /**
     * Adds a condition that a subquery returns no row: {@code not exists (select ...)}.
     *
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder whereNotExists(Builder query) {
        return addWhereExists(frozen(query), AND, true);
    }

    /**
     * Adds a condition that a subquery built by a consumer returns no row.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder whereNotExists(Consumer<Builder> query) {
        return addWhereExists(subQuery(query), AND, true);
    }

    /**
     * Adds an {@code or} condition that a subquery returns no row.
     *
     * @param query the subquery, never {@code null}; a private copy is taken now
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder orWhereNotExists(Builder query) {
        return addWhereExists(frozen(query), OR, true);
    }

    /**
     * Adds an {@code or} condition that a subquery built by a consumer returns no row.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder orWhereNotExists(Consumer<Builder> query) {
        return addWhereExists(subQuery(query), OR, true);
    }

    // ---- row values ----

    /**
     * Adds a condition comparing a row of columns with a row of values:
     * {@code ("a", "b") < (?, ?)}. Unlike Laravel, the operator must be one the builder or grammar
     * knows; Laravel writes it into the SQL unchecked.
     *
     * @param columns the column names, never {@code null}, none {@code null}
     * @param operator the operator, never {@code null}
     * @param values the values, never {@code null}, as many as the columns; an
     *     {@link Expression} is written as given
     * @return this builder
     * @throws NullPointerException if an argument or a column is {@code null}
     * @throws IllegalArgumentException if the counts differ, the operator is unknown, or a value
     *     is a list, map, array or builder; nothing is added then
     */
    public final Builder whereRowValues(List<String> columns, String operator, List<?> values) {
        return addWhereRowValues(columns, operator, values, AND);
    }

    /**
     * Adds an {@code or} condition comparing a row of columns with a row of values.
     *
     * @param columns the column names, never {@code null}, none {@code null}
     * @param operator the operator, never {@code null}
     * @param values the values, as for {@link Builder#whereRowValues}
     * @return this builder
     * @throws NullPointerException if an argument or a column is {@code null}
     * @throws IllegalArgumentException if the counts differ, the operator is unknown, or a value
     *     is a list, map, array or builder; nothing is added then
     */
    public final Builder orWhereRowValues(List<String> columns, String operator, List<?> values) {
        return addWhereRowValues(columns, operator, values, OR);
    }

    Builder addWhereSubValue(Builder query, String operator, Object value, String conjunction) {
        Objects.requireNonNull(operator, "operator");
        Clauses.checkNullOperator(operator, value);
        String checkedOperator = "=";
        Object checkedValue = operator;
        // The same shortcut as a plain where: an operator nobody knows is the value.
        Optional<String> canonical = Clauses.canonicalOperator(operator, grammar);
        if (canonical.isPresent()) {
            checkedOperator = canonical.get();
            checkedValue = value;
        }
        checkedValue = Clauses.checkValue(checkedValue);
        bindings.get(BindingType.WHERE).addAll(query.getBindings());
        if (checkedValue == null) {
            boolean isEquality = "=".equals(checkedOperator) || "<=>".equals(checkedOperator);
            wheres.add(new WhereSubValue(conjunction, query, isEquality ? "is" : "is not", Expression.raw("null")));
            return self();
        }
        wheres.add(new WhereSubValue(conjunction, query, checkedOperator, checkedValue));
        return bindUnlessExpression(checkedValue, BindingType.WHERE);
    }

    Builder addWhereInSub(String column, Builder query, String conjunction, boolean not) {
        Objects.requireNonNull(column, "column");
        wheres.add(new WhereInSub(conjunction, column, query, not));
        bindings.get(BindingType.WHERE).addAll(query.getBindings());
        return self();
    }

    Builder addWhereExists(Builder query, String conjunction, boolean not) {
        wheres.add(new WhereExists(conjunction, query, not));
        bindings.get(BindingType.WHERE).addAll(query.getBindings());
        return self();
    }

    Builder addWhereRowValues(List<String> columns, String operator, List<?> values, String conjunction) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(values, "values");
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException("The number of columns must match the number of values");
        }
        String canonical = Clauses.canonicalOperator(operator, grammar)
                .orElseThrow(() -> new IllegalArgumentException("Unknown operator for row values: " + operator));
        for (String column : columns) {
            Objects.requireNonNull(column, "column");
        }
        List<Object> copy = new ArrayList<>(values.size());
        for (Object value : values) {
            copy.add(Clauses.checkValue(value));
        }
        wheres.add(new WhereRowValues(conjunction, columns, canonical, copy));
        for (Object value : copy) {
            bindUnlessExpression(value, BindingType.WHERE);
        }
        return self();
    }
}
