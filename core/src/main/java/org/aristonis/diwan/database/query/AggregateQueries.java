package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;

/**
 * The aggregate layer of {@link Builder}: count, min, max, sum and average, each run on a copy so
 * the builder is unchanged.
 */
abstract class AggregateQueries extends ReadQueries {

    AggregateQueries(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }
    /**
     * Counts the matching rows. Runs on a copy without the selected columns, orders, limit and
     * offset, so an ordered or paged query still counts every match and the builder is unchanged.
     * A query with having clauses or unions is counted whole, as a subquery, so its limit and
     * offset apply.
     *
     * @return the count
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final long count() {
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
    public final long count(String column) {
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
    public final Optional<Object> min(String column) {
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
    public final Optional<Object> max(String column) {
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
    public final Number sum(String column) {
        return aggregate("sum", Objects.requireNonNull(column, "column")).map(Results::number).orElse(0L);
    }

    /**
     * Returns the average of a column.
     *
     * @param column the column name, never {@code null}
     * @return the average, converted as for {@link Builder#sum(String)}; empty if no row matches
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the database returns text that is not a number
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final Optional<Number> avg(String column) {
        return aggregate("avg", Objects.requireNonNull(column, "column")).map(Results::number);
    }

    /**
     * Returns the average of a column; an alias of {@link Builder#avg(String)}.
     *
     * @param column the column name, never {@code null}
     * @return the average, empty if no row matches
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the database returns text that is not a number
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final Optional<Number> average(String column) {
        return avg(column);
    }

    /**
     * Counts the rows every page of {@link Builder#paginate(int, int)} would show together, as
     * Laravel's {@code getCountForPagination} does, on a copy so the builder is unchanged.
     *
     * <p>A query with groups or having clauses is counted as a subquery, without its orders, limit
     * and offset: {@code select count(*) as "aggregate" from (...) as "aggregate_table"}; a grouped
     * join that selects nothing selects its table's columns there. A query with unions is counted
     * whole, without the union's own orders, limit and offset; each part keeps its own. Any other
     * query is counted without its columns, orders, limit and offset.
     *
     * <p>A {@link Builder#distinct()} query without unions is counted as a subquery too, like a
     * grouped one, so the total is the number of distinct rows. This departs from Laravel, which
     * drops the columns and counts every matching row, giving a total that disagrees with the pages.
     *
     * @return the count, never negative
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final long getCountForPagination() {
        return getCountForPagination(Collections.singletonList("*"));
    }

    /**
     * Counts the rows for pagination, as {@link Builder#getCountForPagination()} does, counting the
     * given columns; an alias ({@code "name as n"}) is dropped from each.
     *
     * @param columns the columns to count, never {@code null} or empty; {@code ["*"]} counts rows
     * @return the count, never negative
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if {@code columns} is empty
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final long getCountForPagination(List<String> columns) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        List<Object> counted = new ArrayList<>();
        for (String column : columns) {
            counted.add(withoutAlias(Objects.requireNonNull(column, "column")));
        }
        Builder count = countQueryForPagination();
        count.setAggregate("count", counted);
        return Results.aggregate(count.get()).map(value -> Results.number(value).longValue()).orElse(0L);
    }

    private Builder countQueryForPagination() {
        if (!groups.isEmpty() || !havings.isEmpty() || (distinct && unions.isEmpty())) {
            Builder inner = withoutPaging(self().clone());
            if (inner.columns.isEmpty() && !joins.isEmpty() && from != null) {
                // Laravel appends ".*" to the whole from text; the alias, when there is one, is
                // what the select can name. A distinct join needs it too: "select distinct *"
                // would compare the joined table's columns as well.
                inner.columns.add(Results.resultName(from) + ".*");
            }
            return newInstance().fromRaw("(" + inner.toSql() + ") as " + grammar.wrap("aggregate_table"),
                    inner.getBindings());
        }
        Builder copy = self().clone();
        if (!unions.isEmpty()) {
            copy.unionOrders.clear();
            copy.unionLimit = null;
            copy.unionOffset = null;
            copy.bindings.get(BindingType.UNION_ORDER).clear();
            return copy;
        }
        copy.columns.clear();
        copy.orders.clear();
        copy.limit = null;
        copy.offset = null;
        copy.bindings.get(BindingType.SELECT).clear();
        copy.bindings.get(BindingType.ORDER).clear();
        return copy;
    }

    private static Builder withoutPaging(Builder copy) {
        copy.orders.clear();
        copy.limit = null;
        copy.offset = null;
        copy.unionOrders.clear();
        copy.unionLimit = null;
        copy.unionOffset = null;
        copy.bindings.get(BindingType.ORDER).clear();
        copy.bindings.get(BindingType.UNION_ORDER).clear();
        return copy;
    }

    // Laravel's withoutSelectAliases cuts at the first " as ", in any case.
    private static String withoutAlias(String column) {
        int position = column.toLowerCase(Locale.ROOT).indexOf(" as ");
        return position < 0 ? column : column.substring(0, position);
    }

    /**
     * Runs an aggregate on a copy only, so the builder is untouched even when the query fails.
     * Laravel drops the columns and select bindings, and the orders when there are no groups. With
     * no havings or unions the limit and offset are dropped too, since Laravel's kept offset makes
     * {@code count()} on a later page return nothing. With havings or unions everything else is
     * kept: the query becomes a subquery, where the limit and offset apply before rows are counted.
     */
    private Optional<Object> aggregate(String function, String column) {
        Builder copy = self().clone();
        boolean isWrapped = !havings.isEmpty() || !unions.isEmpty();
        if (!isWrapped) {
            copy.columns.clear();
            copy.bindings.get(BindingType.SELECT).clear();
            copy.limit = null;
            copy.offset = null;
        }
        if (groups.isEmpty()) {
            copy.orders.clear();
            copy.bindings.get(BindingType.ORDER).clear();
        }
        copy.setAggregate(function, Collections.<Object>singletonList(column));
        return Results.aggregate(copy.get());
    }
}
