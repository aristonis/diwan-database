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
 * The order, limit and offset layer of {@link Builder}. After the first union, orders, limit and
 * offset apply to the whole union, as in Laravel.
 */
abstract class OrderClauses extends SelectClauses {

    OrderClauses(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    // ---- order ----

    /**
     * Orders by a column, ascending.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder orderBy(String column) {
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
    public final Builder orderBy(String column, String direction) {
        Objects.requireNonNull(column, "column");
        currentOrders().add(new Order.Column(column, checkedDirection(direction)));
        return self();
    }

    /**
     * Orders by the value a subquery returns, ascending.
     *
     * @param query the subquery, never {@code null}; compiled now, so later changes to it are
     *     not seen
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder orderBy(Builder query) {
        return orderBy(query, ASC);
    }

    /**
     * Orders by the value a subquery returns: {@code order by (select ...) desc}. Its bindings go
     * to the order bucket, or to the union order bucket after a union.
     *
     * @param query the subquery, never {@code null}; compiled now, so later changes to it are
     *     not seen
     * @param direction {@code asc} or {@code desc} in any case, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the direction is neither {@code asc} nor {@code desc}
     */
    public final Builder orderBy(Builder query, String direction) {
        return addOrderSub(SubQuery.of(query), direction);
    }

    /**
     * Orders by the value a subquery built by a consumer returns, ascending.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public final Builder orderBy(Consumer<Builder> query) {
        return orderBy(query, ASC);
    }

    /**
     * Orders by the value a subquery built by a consumer returns.
     *
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @param direction {@code asc} or {@code desc} in any case, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the direction is neither {@code asc} nor {@code desc}
     */
    public final Builder orderBy(Consumer<Builder> query, String direction) {
        return addOrderSub(SubQuery.of(subQuery(query)), direction);
    }

    /**
     * Orders by a column, descending.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder orderByDesc(String column) {
        return orderBy(column, DESC);
    }

    /**
     * Orders by {@code created_at}, newest first.
     *
     * @return this builder
     */
    public final Builder latest() {
        return latest(DEFAULT_TIMESTAMP);
    }

    /**
     * Orders by a column, newest first.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder latest(String column) {
        return orderBy(column, DESC);
    }

    /**
     * Orders by {@code created_at}, oldest first.
     *
     * @return this builder
     */
    public final Builder oldest() {
        return oldest(DEFAULT_TIMESTAMP);
    }

    /**
     * Orders by a column, oldest first.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder oldest(String column) {
        return orderBy(column, ASC);
    }

    /**
     * Orders randomly.
     *
     * @return this builder
     */
    public final Builder inRandomOrder() {
        return inRandomOrder("");
    }

    /**
     * Orders randomly with a seed, where the grammar's database supports one.
     *
     * @param seed the seed, never {@code null}, empty for none; never user input
     * @return this builder
     * @throws NullPointerException if {@code seed} is {@code null}
     */
    public final Builder inRandomOrder(String seed) {
        return orderByRaw(grammar.compileRandom(Objects.requireNonNull(seed, "seed")));
    }

    /**
     * Orders by trusted SQL.
     *
     * @param sql the SQL, never {@code null}, never user input
     * @return this builder
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public final Builder orderByRaw(String sql) {
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
    public final Builder orderByRaw(String sql, List<?> bindings) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(bindings, "bindings");
        currentOrders().add(new Order.Raw(sql));
        return addBindings(bindings, currentOrderBucket());
    }

    /**
     * Removes every order and its bindings, including the orders of a union.
     *
     * @return this builder
     */
    public final Builder reorder() {
        orders.clear();
        unionOrders.clear();
        bindings.get(BindingType.ORDER).clear();
        bindings.get(BindingType.UNION_ORDER).clear();
        return self();
    }

    /**
     * Replaces every order with one column, ascending.
     *
     * @param column the column name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public final Builder reorder(String column) {
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
    public final Builder reorder(String column, String direction) {
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
    public final Builder reorderDesc(String column) {
        return reorder(column, DESC);
    }

    // ---- limit and offset ----

    /**
     * Limits the number of rows; a negative value is ignored. After a union it limits the whole union.
     *
     * @param value the maximum number of rows
     * @return this builder
     */
    public final Builder limit(int value) {
        if (value >= 0 && unions.isEmpty()) {
            this.limit = value;
        } else if (value >= 0) {
            this.unionLimit = value;
        }
        return self();
    }

    /**
     * Limits the number of rows; an alias of {@link Builder#limit(int)}.
     *
     * @param value the maximum number of rows
     * @return this builder
     */
    public final Builder take(int value) {
        return limit(value);
    }

    /**
     * Skips rows; a negative value means zero. After a union it skips rows of the whole union.
     *
     * @param value the number of rows to skip
     * @return this builder
     */
    public final Builder offset(int value) {
        if (unions.isEmpty()) {
            this.offset = Math.max(0, value);
        } else {
            this.unionOffset = Math.max(0, value);
        }
        return self();
    }

    /**
     * Skips rows; an alias of {@link Builder#offset(int)}.
     *
     * @param value the number of rows to skip
     * @return this builder
     */
    public final Builder skip(int value) {
        return offset(value);
    }

    /**
     * Selects one page of 15 rows.
     *
     * @param page the page number, at least 1
     * @return this builder
     * @throws IllegalArgumentException if {@code page} is below 1
     */
    public final Builder forPage(int page) {
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
    public final Builder forPage(int page, int perPage) {
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
    public final Builder forPageBeforeId(int perPage) {
        return forPageBeforeId(perPage, 0, DEFAULT_KEY);
    }

    /**
     * Selects a page of rows whose {@code id} is below the last one seen, highest first.
     *
     * @param perPage the rows per page
     * @param lastId the last id seen, or {@code null} for the first page
     * @return this builder
     */
    public final Builder forPageBeforeId(int perPage, Object lastId) {
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
    public final Builder forPageBeforeId(int perPage, Object lastId, String column) {
        return forPageById(perPage, lastId, column, "<", DESC);
    }

    /**
     * Selects a page of rows whose {@code id} is above 0, lowest first; Laravel's default last id.
     *
     * @param perPage the rows per page
     * @return this builder
     */
    public final Builder forPageAfterId(int perPage) {
        return forPageAfterId(perPage, 0, DEFAULT_KEY);
    }

    /**
     * Selects a page of rows whose {@code id} is above the last one seen, lowest first.
     *
     * @param perPage the rows per page
     * @param lastId the last id seen, or {@code null} for the first page
     * @return this builder
     */
    public final Builder forPageAfterId(int perPage, Object lastId) {
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
    public final Builder forPageAfterId(int perPage, Object lastId, String column) {
        return forPageById(perPage, lastId, column, ">", ASC);
    }

    /** The orders a new entry joins: the union's once there is a union, as in Laravel. */
    List<Order> currentOrders() {
        return unions.isEmpty() ? orders : unionOrders;
    }

    BindingType currentOrderBucket() {
        return unions.isEmpty() ? BindingType.ORDER : BindingType.UNION_ORDER;
    }

    private static String checkedDirection(String direction) {
        String lower = Objects.requireNonNull(direction, "direction").toLowerCase(Locale.ROOT);
        if (!ASC.equals(lower) && !DESC.equals(lower)) {
            throw new IllegalArgumentException("Order direction must be \"asc\" or \"desc\"");
        }
        return lower;
    }

    private Builder addOrderSub(SubQuery sub, String direction) {
        currentOrders().add(new Order.Sub(sub.sql, checkedDirection(direction)));
        return addBindings(sub.bindings, currentOrderBucket());
    }

    Builder forPageById(int perPage, Object lastId, String column, String operator, String direction) {
        Objects.requireNonNull(column, "column");
        orders.removeIf(order -> order instanceof Order.Column && ((Order.Column) order).column().equals(column));
        if (lastId == null) {
            whereNotNull(column);
        } else {
            where(column, operator, lastId);
        }
        return orderBy(column, direction).limit(perPage);
    }
}
