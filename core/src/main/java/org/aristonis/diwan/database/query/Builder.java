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
 * <p>A subquery is a {@code Builder}, a {@code Consumer<Builder>} that fills a fresh one, or, where
 * a method says so, trusted SQL text. It is copied or compiled when it is attached, with its
 * bindings in the bucket of the clause that holds it, so changing it afterwards changes nothing
 * here. Joins are {@link JoinClause}s; after the first {@link #union(Builder)}, orders, limit and
 * offset apply to the whole union.
 *
 * <p>A builder is mutable and not safe to share between threads. Every method that returns
 * {@code Builder} changes this builder and returns it. {@link #clone()} gives an independent copy.
 * Lists returned by the getters are unmodifiable views of a snapshot.
 */
public class Builder extends SubqueryClauses {


    /**
     * Creates an empty builder. Applications start from a {@link QueryFactory} instead.
     *
     * @param connection the connection, never {@code null}
     * @param grammar the grammar, never {@code null}
     * @param processor the processor, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public Builder(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }

    @Override
    final Builder self() {
        return this;
    }

    /**
     * Creates the empty builder that {@link #clone()} and {@link #newQuery()} fill; a subclass
     * returns its own type.
     *
     * @return a new, empty builder on the same connection, grammar and processor, never {@code null}
     */
    @Override
    protected Builder newInstance() {
        return new Builder(getConnection(), getGrammar(), getProcessor());
    }

    /**
     * Returns an independent copy: changing either builder, including a nested group, a join or
     * a union, never changes the other. Joins, unions and subqueries are private copies that no
     * builder ever changes, so the copy shares them and only the lists are copied.
     *
     * @return the copy, never {@code null}
     */
    @Override
    public Builder clone() {
        Builder copy = newInstance();
        for (BindingType type : BindingType.values()) {
            copy.bindings.get(type).addAll(bindings.get(type));
        }
        copy.columns = new ArrayList<>(columns);
        copy.distinct = distinct;
        copy.from = from;
        copy.fromExpression = fromExpression;
        copy.joins = new ArrayList<>(joins);
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
        copy.unions = new ArrayList<>(unions);
        copy.unionOrders = new ArrayList<>(unionOrders);
        copy.unionLimit = unionLimit;
        copy.unionOffset = unionOffset;
        return copy;
    }

    /**
     * Returns a new, empty builder on the same connection, grammar and processor.
     *
     * @return the new builder, never {@code null}
     */
    public Builder newQuery() {
        return newInstance();
    }

    // ---- reads ----

    /**
     * Runs the query and returns its rows.
     *
     * @return the rows as {@link Processor#processSelect} returns them, never {@code null}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     * @throws UnsupportedOperationException if the grammar does not support a clause
     */
    public final List<Map<String, Object>> get() {
        return processor.processSelect(this, runSelect());
    }

    /**
     * Runs the query limited to one row, on a copy, and returns that row. Laravel sets the limit on
     * the builder itself; here the builder is left as it was.
     *
     * @return the first row, empty if there is none
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final Optional<Map<String, Object>> first() {
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
    public final Optional<Map<String, Object>> find(Object id) {
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
    public final Optional<Object> value(String column) {
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
    public final List<Object> pluck(String column) {
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
    public final Map<Object, Object> pluck(String column, String key) {
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
    public final boolean exists() {
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
    public final boolean doesntExist() {
        return !exists();
    }

    // ---- aggregates ----

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
     * @return the average, converted as for {@link #sum(String)}; empty if no row matches
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the database returns text that is not a number
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the query
     */
    public final Optional<Number> avg(String column) {
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
    public final Optional<Number> average(String column) {
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final boolean insert(Map<String, ?> row) {
        return insert(Collections.singletonList(Objects.requireNonNull(row, "row")));
    }

    /**
     * Inserts several rows in one statement; no rows runs nothing and returns {@code true}.
     *
     * @param rows the rows, never {@code null}, all with the same columns
     * @return {@code true} for no rows, else what the connection returns for the insert
     * @throws NullPointerException if {@code rows} or a row is {@code null}
     * @throws IllegalArgumentException if the rows have different columns
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final boolean insert(List<? extends Map<String, ?>> rows) {
        Objects.requireNonNull(rows, "rows");
        requireTable();
        Optional<String> sql = grammar.compileInsert(this, rows);
        if (!sql.isPresent()) {
            return true;
        }
        List<Object> values = new ArrayList<>();
        if (!rows.get(0).isEmpty()) {
            List<String> keys = grammar.insertColumns(rows);
            for (Map<String, ?> row : rows) {
                for (String key : keys) {
                    Clauses.addInsertValue(values, row.get(key));
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final Object insertGetId(Map<String, ?> row) {
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final Object insertGetId(Map<String, ?> row, String sequence) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(sequence, "sequence");
        requireTable();
        String sql = grammar.compileInsertGetId(this, row, sequence);
        List<Object> values = new ArrayList<>();
        for (Object value : row.values()) {
            Clauses.addInsertValue(values, value);
        }
        return processor.processInsertGetId(this, sql, grammar.prepareBindings(values), sequence);
    }

    /**
     * Inserts the rows a subquery returns: {@code insert into "t" ("a", "b") select ...}.
     *
     * @param columns the target columns, never {@code null}; empty or {@code ["*"]} leaves the
     *     column list out
     * @param query the subquery, never {@code null}; compiled now
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final int insertUsing(List<String> columns, Builder query) {
        Objects.requireNonNull(columns, "columns");
        requireTable();
        SubQuery sub = SubQuery.of(query);
        return connection.affectingStatement(grammar.compileInsertUsing(this, columns, sub.sql),
                grammar.prepareBindings(sub.bindings));
    }

    /**
     * Inserts the rows a subquery built by a consumer returns.
     *
     * @param columns the target columns, as for {@link #insertUsing(List, Builder)}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final int insertUsing(List<String> columns, Consumer<Builder> query) {
        return insertUsing(columns, subQuery(query));
    }

    /**
     * Inserts the rows a subquery returns, skipping rows that would break a unique or primary key
     * constraint; the SQL is the grammar's ({@code insert or ignore} on SQLite,
     * {@code on conflict do nothing} on PostgreSQL).
     *
     * @param columns the target columns, as for {@link #insertUsing(List, Builder)}
     * @param query the subquery, never {@code null}; compiled now
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if an argument is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot ignore errors, as the base
     *     grammar cannot; nothing runs then
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final int insertOrIgnoreUsing(List<String> columns, Builder query) {
        Objects.requireNonNull(columns, "columns");
        requireTable();
        SubQuery sub = SubQuery.of(query);
        return connection.affectingStatement(grammar.compileInsertOrIgnoreUsing(this, columns, sub.sql),
                grammar.prepareBindings(sub.bindings));
    }

    /**
     * Inserts the rows a subquery built by a consumer returns, skipping rows that would break a
     * unique or primary key constraint.
     *
     * @param columns the target columns, as for {@link #insertUsing(List, Builder)}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if an argument is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot ignore errors
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final int insertOrIgnoreUsing(List<String> columns, Consumer<Builder> query) {
        return insertOrIgnoreUsing(columns, subQuery(query));
    }

    /**
     * Updates the matching rows.
     *
     * <p>A query with joins needs a database grammar that rewrites it; the base grammar refuses
     * it. The rewrite runs on a copy, so this builder is unchanged either way.
     *
     * @param values the columns and new values, never {@code null} or empty; an
     *     {@link Expression} is written as given; a {@link Builder} is written as
     *     {@code (select ...)}, from a private copy taken now, with its bindings in column order
     * @return the number of rows changed
     * @throws NullPointerException if {@code values} is {@code null}
     * @throws IllegalArgumentException if {@code values} is empty or a value is a list, map or
     *     array
     * @throws UnsupportedOperationException if the query has joins and the grammar cannot
     *     update with joins
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int update(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        requireTable();
        Map<String, Object> checked = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            checked.put(entry.getKey(), value instanceof Builder ? frozen((Builder) value) : Clauses.checkValue(value));
        }
        String sql = grammar.compileUpdate(this, checked);
        return connection.update(sql,
                grammar.prepareBindings(grammar.prepareBindingsForUpdate(this, checked)));
    }

    /**
     * Adds 1 to a column of the matching rows.
     *
     * @param column the column name, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int increment(String column) {
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int increment(String column, Number amount) {
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int increment(String column, Number amount, Map<String, ?> extra) {
        return incrementEach(singleAmount(column, amount), extra);
    }

    /**
     * Subtracts 1 from a column of the matching rows.
     *
     * @param column the column name, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int decrement(String column) {
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int decrement(String column, Number amount) {
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int decrement(String column, Number amount, Map<String, ?> extra) {
        return decrementEach(singleAmount(column, amount), extra);
    }

    /**
     * Adds an amount to each of several columns of the matching rows.
     *
     * @param amounts the columns and amounts, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code amounts}, a column or an amount is {@code null}
     * @throws IllegalArgumentException if an amount is not finite or not a standard number type
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int incrementEach(Map<String, ? extends Number> amounts) {
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int incrementEach(Map<String, ? extends Number> amounts, Map<String, ?> extra) {
        return step(amounts, extra, " + ");
    }

    /**
     * Subtracts an amount from each of several columns of the matching rows.
     *
     * @param amounts the columns and amounts, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code amounts}, a column or an amount is {@code null}
     * @throws IllegalArgumentException if an amount is not finite or not a standard number type
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int decrementEach(Map<String, ? extends Number> amounts) {
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int decrementEach(Map<String, ? extends Number> amounts, Map<String, ?> extra) {
        return step(amounts, extra, " - ");
    }

    /**
     * Deletes the matching rows. A query with joins needs a database grammar that rewrites it, as
     * for {@link #update(Map)}.
     *
     * @return the number of rows deleted
     * @throws UnsupportedOperationException if the query has joins and the grammar cannot
     *     delete with joins
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the delete
     */
    public final int delete() {
        requireTable();
        return connection.delete(grammar.compileDelete(this),
                grammar.prepareBindings(grammar.prepareBindingsForDelete(this)));
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
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the delete
     */
    public final int delete(Object id) {
        Objects.requireNonNull(id, "id");
        String table = from == null ? null : Results.resultName(from);
        return where(table == null ? DEFAULT_KEY : table + "." + DEFAULT_KEY, "=", id).delete();
    }

    /**
     * Empties the table. When the grammar needs several statements, they run in one transaction,
     * unless the connection is already inside one.
     *
     * @throws IllegalStateException if the query reads no table, as after {@link #fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement;
     *     inside the transaction, every statement is then undone
     */
    public final void truncate() {
        requireTable();
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
     * no havings or unions the limit and offset are dropped too, since Laravel's kept offset makes
     * {@code count()} on a later page return nothing. With havings or unions everything else is
     * kept: the query becomes a subquery, where the limit and offset apply before rows are counted.
     */
    private Optional<Object> aggregate(String function, String column) {
        Builder copy = clone();
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
}
