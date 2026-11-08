package org.aristonis.diwan.database.eloquent;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.LockStrength;
import org.aristonis.diwan.database.query.LockWait;

/**
 * The order, select, join and limit forwarders of {@link EloquentBuilder}, and the ways to reach
 * the wrapped query builder directly.
 *
 * @param <T> the model class
 */
abstract class EloquentClauses<T extends Model> extends EloquentScopes<T> {

    EloquentClauses(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
    }

    /**
     * Orders by the model's creation column descending, {@code created_at} unless the model
     * names another, as Laravel's Eloquent {@code latest()}.
     *
     * @return this builder
     */
    public final EloquentBuilder<T> latest() {
        return latest(createdAtOrDefault());
    }

    /**
     * Orders by a column descending.
     *
     * @param column the column, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final EloquentBuilder<T> latest(String column) {
        query.latest(column);
        return self();
    }

    /**
     * Orders by the model's creation column ascending, {@code created_at} unless the model names
     * another.
     *
     * @return this builder
     */
    public final EloquentBuilder<T> oldest() {
        return oldest(createdAtOrDefault());
    }

    /**
     * Orders by a column ascending.
     *
     * @param column the column, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final EloquentBuilder<T> oldest(String column) {
        query.oldest(column);
        return self();
    }

    private String createdAtOrDefault() {
        String column = model.getCreatedAtColumn();
        return column != null ? column : "created_at";
    }

    /**
     * Changes the wrapped query builder directly, for clauses this builder does not forward.
     *
     * @param change changes the builder, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code change} is {@code null}
     */
    public final EloquentBuilder<T> query(Consumer<Builder> change) {
        change.accept(query);
        return self();
    }

    /**
     * Returns a copy of the wrapped query builder with the global scopes applied; changing the
     * copy does not change this builder.
     *
     * @return the copy, never {@code null}
     */
    public final Builder toBase() {
        Builder scoped = scoped();
        return scoped == query ? query.clone() : scoped;
    }

    /**
     * Returns the SQL this builder would run for a select.
     *
     * @return the SQL, never {@code null}
     */
    public final String toSql() {
        return scoped().toSql();
    }

    /**
     * Returns the bindings of the select, in order.
     *
     * @return the bindings, unmodifiable, never {@code null}
     */
    public final List<Object> getBindings() {
        return scoped().getBindings();
    }

    /**
     * Returns the model instance this builder reads its table, key and timestamp settings from.
     *
     * @return the model, never {@code null}
     */
    public final T getModel() {
        return model;
    }

    /**
     * Orders by a column ascending, as {@link Builder#orderBy(String)}.
     *
     * @param column the column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orderBy(String column) {
        query.orderBy(column);
        return self();
    }

    /**
     * Orders by a column, as {@link Builder#orderBy(String, String)}.
     *
     * @param column the column
     * @param direction {@code asc} or {@code desc}
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orderBy(String column, String direction) {
        query.orderBy(column, direction);
        return self();
    }

    /**
     * Orders by a column descending, as {@link Builder#orderByDesc(String)}.
     *
     * @param column the column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orderByDesc(String column) {
        query.orderByDesc(column);
        return self();
    }

    /**
     * Orders by raw SQL, as {@link Builder#orderByRaw(String)}.
     *
     * @param sql the SQL
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> orderByRaw(String sql) {
        query.orderByRaw(sql);
        return self();
    }

    /**
     * Orders randomly, as {@link Builder#inRandomOrder()}.
     *
     * @return this builder
     */
    public final EloquentBuilder<T> inRandomOrder() {
        query.inRandomOrder();
        return self();
    }

    /**
     * Removes every order, as {@link Builder#reorder()}.
     *
     * @return this builder
     */
    public final EloquentBuilder<T> reorder() {
        query.reorder();
        return self();
    }

    /**
     * Replaces every order with one column, as {@link Builder#reorder(String, String)}.
     *
     * @param column the column
     * @param direction {@code asc} or {@code desc}
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> reorder(String column, String direction) {
        query.reorder(column, direction);
        return self();
    }

    /**
     * Sets the selected columns, as {@link Builder#select(Object...)}.
     *
     * @param columns the columns
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> select(Object... columns) {
        query.select(columns);
        return self();
    }

    /**
     * Adds selected columns, as {@link Builder#addSelect(Object...)}.
     *
     * @param columns the columns
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> addSelect(Object... columns) {
        query.addSelect(columns);
        return self();
    }

    /**
     * Adds a raw select expression, as {@link Builder#selectRaw(String)}.
     *
     * @param sql the SQL
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> selectRaw(String sql) {
        query.selectRaw(sql);
        return self();
    }

    /**
     * Selects distinct rows, as {@link Builder#distinct()}.
     *
     * @return this builder
     */
    public final EloquentBuilder<T> distinct() {
        query.distinct();
        return self();
    }

    /**
     * Groups by columns, as {@link Builder#groupBy(String...)}.
     *
     * @param columns the columns
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> groupBy(String... columns) {
        query.groupBy(columns);
        return self();
    }

    /**
     * Adds a {@code having} clause, as {@link Builder#having(String, String, Object)}.
     *
     * @param column the column
     * @param operator the operator
     * @param value the value, as the base builder accepts it
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> having(String column, String operator, Object value) {
        query.having(column, operator, value);
        return self();
    }

    /**
     * Adds an inner join, as {@link Builder#join(String, String, String, String)}.
     *
     * @param table the table
     * @param first the first column
     * @param operator the operator
     * @param second the second column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> join(String table, String first, String operator, String second) {
        query.join(table, first, operator, second);
        return self();
    }

    /**
     * Adds a left join, as {@link Builder#leftJoin(String, String, String, String)}.
     *
     * @param table the table
     * @param first the first column
     * @param operator the operator
     * @param second the second column
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> leftJoin(String table, String first, String operator, String second) {
        query.leftJoin(table, first, operator, second);
        return self();
    }

    /**
     * Limits the number of rows, as {@link Builder#limit(int)}.
     *
     * @param value the count
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> limit(int value) {
        query.limit(value);
        return self();
    }

    /**
     * Limits the number of rows, as {@code limit(int)}, as {@link Builder#take(int)}.
     *
     * @param value the count
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> take(int value) {
        query.take(value);
        return self();
    }

    /**
     * Skips rows, as {@link Builder#offset(int)}.
     *
     * @param value the count
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> offset(int value) {
        query.offset(value);
        return self();
    }

    /**
     * Skips rows, as {@code offset(int)}, as {@link Builder#skip(int)}.
     *
     * @param value the count
     * @return this builder
     * @throws NullPointerException if a required argument is {@code null}
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> skip(int value) {
        query.skip(value);
        return self();
    }

    /**
     * Limits the query to one page, as {@link Builder#forPage(int, int)}.
     *
     * @param page the page number, from 1
     * @param perPage the rows per page
     * @return this builder
     * @throws IllegalArgumentException if the base builder refuses an argument
     */
    public final EloquentBuilder<T> forPage(int page, int perPage) {
        query.forPage(page, perPage);
        return self();
    }

    /**
     * Locks the selected rows for update, as {@link Builder#lockForUpdate()}.
     *
     * @return this builder
     */
    public final EloquentBuilder<T> lockForUpdate() {
        query.lockForUpdate();
        return self();
    }

    /**
     * Locks the selected rows for sharing, as {@link Builder#sharedLock()}.
     *
     * @return this builder
     */
    public final EloquentBuilder<T> sharedLock() {
        query.sharedLock();
        return self();
    }

    /**
     * Locks the selected rows, waiting for a conflicting lock.
     *
     * @param strength the strength, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code strength} is {@code null}
     */
    public final EloquentBuilder<T> lock(LockStrength strength) {
        query.lock(strength);
        return self();
    }

    /**
     * Locks the selected rows with a wait limit, as {@link Builder#lock(LockStrength, Duration)}.
     *
     * @param strength the strength, never {@code null}
     * @param timeout the longest wait, never {@code null}, more than zero
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code timeout} is zero, negative or too long
     */
    public final EloquentBuilder<T> lock(LockStrength strength, Duration timeout) {
        query.lock(strength, timeout);
        return self();
    }

    /**
     * Locks the selected rows, as {@link Builder#lock(LockStrength, LockWait)}.
     *
     * @param strength the strength, never {@code null}
     * @param lockWait what to do when the rows are locked, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final EloquentBuilder<T> lock(LockStrength strength, LockWait lockWait) {
        query.lock(strength, lockWait);
        return self();
    }
}
