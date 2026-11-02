package org.aristonis.diwan.database.eloquent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.query.Builder;

/**
 * The where-clause forwarders of {@link EloquentBuilder}: each adds its clause to the wrapped
 * query builder and returns the Eloquent builder. Clauses not forwarded are reachable through
 * {@link EloquentBuilder#query(Consumer)}.
 *
 * @param <T> the model class
 */
abstract class EloquentWheres<T extends Model> {

    final ModelQuery<T> source;
    final T model;
    final Builder query;

    EloquentWheres(ModelQuery<T> source, T model, Builder query) {
        this.source = Objects.requireNonNull(source, "source");
        this.model = Objects.requireNonNull(model, "model");
        this.query = Objects.requireNonNull(query, "query");
    }

    @SuppressWarnings("unchecked")
    final EloquentBuilder<T> self() {
        return (EloquentBuilder<T>) this;
    }

    /**
     * Adds a {@code column = value} clause, as {@link Builder#where(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> where(String column, Object value) {
        query.where(column, value);
        return self();
    }

    /**
     * Adds a {@code column operator value} clause, as {@link Builder#where(String, String, Object)}.
     *
     * @param column the column
     * @param operator the operator
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> where(String column, String operator, Object value) {
        query.where(column, operator, value);
        return self();
    }

    /**
     * Adds a nested group of {@code column = value} clauses joined by {@code and}, as {@link Builder#where(Map)}.
     *
     * @param values the values
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> where(Map<String, ?> values) {
        query.where(values);
        return self();
    }

    /**
     * Adds a nested group of clauses built on the base builder, as {@link Builder#where(Consumer)}.
     *
     * @param nested builds the nested clauses
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> where(Consumer<Builder> nested) {
        query.where(nested);
        return self();
    }

    /**
     * Adds an {@code or column = value} clause, as {@link Builder#orWhere(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhere(String column, Object value) {
        query.orWhere(column, value);
        return self();
    }

    /**
     * Adds an {@code or column operator value} clause, as {@link Builder#orWhere(String, String, Object)}.
     *
     * @param column the column
     * @param operator the operator
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhere(String column, String operator, Object value) {
        query.orWhere(column, operator, value);
        return self();
    }

    /**
     * Adds an {@code or} nested group of {@code column = value} clauses, as {@link Builder#orWhere(Map)}.
     *
     * @param values the values
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhere(Map<String, ?> values) {
        query.orWhere(values);
        return self();
    }

    /**
     * Adds an {@code or} nested group of clauses, as {@link Builder#orWhere(Consumer)}.
     *
     * @param nested builds the nested clauses
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhere(Consumer<Builder> nested) {
        query.orWhere(nested);
        return self();
    }

    /**
     * Adds a {@code not column = value} clause, as {@link Builder#whereNot(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNot(String column, Object value) {
        query.whereNot(column, value);
        return self();
    }

    /**
     * Adds a negated nested group of clauses, as {@link Builder#whereNot(Consumer)}.
     *
     * @param nested builds the nested clauses
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNot(Consumer<Builder> nested) {
        query.whereNot(nested);
        return self();
    }

    /**
     * Adds a clause comparing two columns for equality, as {@link Builder#whereColumn(String, String)}.
     *
     * @param first the first column
     * @param second the second column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereColumn(String first, String second) {
        query.whereColumn(first, second);
        return self();
    }

    /**
     * Adds a clause comparing two columns, as {@link Builder#whereColumn(String, String, String)}.
     *
     * @param first the first column
     * @param operator the operator
     * @param second the second column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereColumn(String first, String operator, String second) {
        query.whereColumn(first, operator, second);
        return self();
    }

    /**
     * Adds a raw SQL clause, as {@link Builder#whereRaw(String)}.
     *
     * @param sql the SQL
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereRaw(String sql) {
        query.whereRaw(sql);
        return self();
    }

    /**
     * Adds a raw SQL clause with bindings, as {@link Builder#whereRaw(String, List)}.
     *
     * @param sql the SQL
     * @param bindings the bindings
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereRaw(String sql, List<?> bindings) {
        query.whereRaw(sql, bindings);
        return self();
    }

    /**
     * Adds an {@code or} raw SQL clause, as {@link Builder#orWhereRaw(String)}.
     *
     * @param sql the SQL
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereRaw(String sql) {
        query.orWhereRaw(sql);
        return self();
    }

    /**
     * Adds an {@code or} raw SQL clause with bindings, as {@link Builder#orWhereRaw(String, List)}.
     *
     * @param sql the SQL
     * @param bindings the bindings
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereRaw(String sql, List<?> bindings) {
        query.orWhereRaw(sql, bindings);
        return self();
    }

    /**
     * Adds a {@code column in (...)} clause, as {@link Builder#whereIn(String, Collection)}.
     *
     * @param column the column
     * @param values the values
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereIn(String column, Collection<?> values) {
        query.whereIn(column, values);
        return self();
    }

    /**
     * Adds a {@code column not in (...)} clause, as {@link Builder#whereNotIn(String, Collection)}.
     *
     * @param column the column
     * @param values the values
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNotIn(String column, Collection<?> values) {
        query.whereNotIn(column, values);
        return self();
    }

    /**
     * Adds an {@code or column in (...)} clause, as {@link Builder#orWhereIn(String, Collection)}.
     *
     * @param column the column
     * @param values the values
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereIn(String column, Collection<?> values) {
        query.orWhereIn(column, values);
        return self();
    }

    /**
     * Adds an {@code or column not in (...)} clause, as {@link Builder#orWhereNotIn(String, Collection)}.
     *
     * @param column the column
     * @param values the values
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereNotIn(String column, Collection<?> values) {
        query.orWhereNotIn(column, values);
        return self();
    }

    /**
     * Adds a {@code column is null} clause for each column, as {@link Builder#whereNull(String...)}.
     *
     * @param columns the columns
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNull(String... columns) {
        query.whereNull(columns);
        return self();
    }

    /**
     * Adds a {@code column is not null} clause for each column, as {@link Builder#whereNotNull(String...)}.
     *
     * @param columns the columns
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNotNull(String... columns) {
        query.whereNotNull(columns);
        return self();
    }

    /**
     * Adds an {@code or column is null} clause, as {@link Builder#orWhereNull(String)}.
     *
     * @param column the column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereNull(String column) {
        query.orWhereNull(column);
        return self();
    }

    /**
     * Adds an {@code or column is not null} clause, as {@link Builder#orWhereNotNull(String)}.
     *
     * @param column the column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereNotNull(String column) {
        query.orWhereNotNull(column);
        return self();
    }

    /**
     * Adds a {@code column between ? and ?} clause, as {@link Builder#whereBetween(String, List)}.
     *
     * @param column the column
     * @param range the two bounds
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereBetween(String column, List<?> range) {
        query.whereBetween(column, range);
        return self();
    }

    /**
     * Adds a {@code column not between ? and ?} clause, as {@link Builder#whereNotBetween(String, List)}.
     *
     * @param column the column
     * @param range the two bounds
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNotBetween(String column, List<?> range) {
        query.whereNotBetween(column, range);
        return self();
    }

    /**
     * Adds an {@code or column between ? and ?} clause, as {@link Builder#orWhereBetween(String, List)}.
     *
     * @param column the column
     * @param range the two bounds
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereBetween(String column, List<?> range) {
        query.orWhereBetween(column, range);
        return self();
    }

    /**
     * Adds a case-insensitive {@code like} clause, as {@link Builder#whereLike(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereLike(String column, Object value) {
        query.whereLike(column, value);
        return self();
    }

    /**
     * Adds a case-insensitive {@code not like} clause, as {@link Builder#whereNotLike(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNotLike(String column, Object value) {
        query.whereNotLike(column, value);
        return self();
    }

    /**
     * Adds an {@code or} case-insensitive {@code like} clause, as {@link Builder#orWhereLike(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereLike(String column, Object value) {
        query.orWhereLike(column, value);
        return self();
    }

    /**
     * Adds a clause on the date part of a column, as {@link Builder#whereDate(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereDate(String column, Object value) {
        query.whereDate(column, value);
        return self();
    }

    /**
     * Adds a comparison on the date part of a column, as {@link Builder#whereDate(String, String, Object)}.
     *
     * @param column the column
     * @param operator the operator
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereDate(String column, String operator, Object value) {
        query.whereDate(column, operator, value);
        return self();
    }

    /**
     * Adds a comparison on the time part of a column, as {@link Builder#whereTime(String, String, Object)}.
     *
     * @param column the column
     * @param operator the operator
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereTime(String column, String operator, Object value) {
        query.whereTime(column, operator, value);
        return self();
    }

    /**
     * Adds a clause on the day of the month of a column, as {@link Builder#whereDay(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereDay(String column, Object value) {
        query.whereDay(column, value);
        return self();
    }

    /**
     * Adds a clause on the month of a column, as {@link Builder#whereMonth(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereMonth(String column, Object value) {
        query.whereMonth(column, value);
        return self();
    }

    /**
     * Adds a clause on the year of a column, as {@link Builder#whereYear(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereYear(String column, Object value) {
        query.whereYear(column, value);
        return self();
    }

    /**
     * Adds a {@code where exists} clause, as {@link Builder#whereExists(Consumer)}.
     *
     * @param subquery builds the subquery
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereExists(Consumer<Builder> subquery) {
        query.whereExists(subquery);
        return self();
    }

    /**
     * Adds a {@code where not exists} clause, as {@link Builder#whereNotExists(Consumer)}.
     *
     * @param subquery builds the subquery
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNotExists(Consumer<Builder> subquery) {
        query.whereNotExists(subquery);
        return self();
    }

    /**
     * Adds a clause for moments in a half-open range, as {@link Builder#whereInstantBetween(String, Instant, Instant)}.
     *
     * @param column the column
     * @param from the inclusive start
     * @param to the exclusive end
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereInstantBetween(String column, Instant from, Instant to) {
        query.whereInstantBetween(column, from, to);
        return self();
    }

    /**
     * Adds an {@code or not column = value} clause, as {@link Builder#orWhereNot(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereNot(String column, Object value) {
        query.orWhereNot(column, value);
        return self();
    }

    /**
     * Adds an {@code or} clause comparing two columns, as {@link Builder#orWhereColumn(String, String, String)}.
     *
     * @param first the first column
     * @param operator the operator
     * @param second the second column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orWhereColumn(String first, String operator, String second) {
        query.orWhereColumn(first, operator, second);
        return self();
    }

    /**
     * Adds a clause on the time part of a column, as {@link Builder#whereTime(String, Object)}.
     *
     * @param column the column
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereTime(String column, Object value) {
        query.whereTime(column, value);
        return self();
    }

    /**
     * Adds a {@code column in (subquery)} clause, as {@link Builder#whereIn(String, Consumer)}.
     *
     * @param column the column
     * @param subquery builds the subquery
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereIn(String column, Consumer<Builder> subquery) {
        query.whereIn(column, subquery);
        return self();
    }

    /**
     * Adds a {@code column not in (subquery)} clause, as {@link Builder#whereNotIn(String, Consumer)}.
     *
     * @param column the column
     * @param subquery builds the subquery
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> whereNotIn(String column, Consumer<Builder> subquery) {
        query.whereNotIn(column, subquery);
        return self();
    }
}
