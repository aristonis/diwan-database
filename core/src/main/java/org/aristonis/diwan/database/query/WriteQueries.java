package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;

/**
 * The write layer of {@link Builder}: insert, update, increment, decrement, delete and truncate.
 */
abstract class WriteQueries extends AggregateQueries {

    WriteQueries(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }
    /**
     * Inserts one row.
     *
     * @param row the columns and values, never {@code null}; an {@link Expression} is written as
     *     given; an empty map inserts the defaults
     * @return what the connection returns for the insert
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final boolean insert(List<? extends Map<String, ?>> rows) {
        Objects.requireNonNull(rows, "rows");
        requireTable();
        Optional<String> sql = grammar.compileInsert(self(), rows);
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
     * @return the new key, as for {@link Builder#insertGetId(Map, String)}
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws UnsupportedOperationException if the processor cannot return a key
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final Object insertGetId(Map<String, ?> row, String sequence) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(sequence, "sequence");
        requireTable();
        String sql = grammar.compileInsertGetId(self(), row, sequence);
        List<Object> values = new ArrayList<>();
        for (Object value : row.values()) {
            Clauses.addInsertValue(values, value);
        }
        return processor.processInsertGetId(self(), sql, grammar.prepareBindings(values), sequence);
    }

    /**
     * Inserts the rows a subquery returns: {@code insert into "t" ("a", "b") select ...}.
     *
     * @param columns the target columns, never {@code null}; empty or {@code ["*"]} leaves the
     *     column list out
     * @param query the subquery, never {@code null}; compiled now
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final int insertUsing(List<String> columns, Builder query) {
        Objects.requireNonNull(columns, "columns");
        requireTable();
        SubQuery sub = SubQuery.of(query);
        return connection.affectingStatement(grammar.compileInsertUsing(self(), columns, sub.sql),
                grammar.prepareBindings(sub.bindings));
    }

    /**
     * Inserts the rows a subquery built by a consumer returns.
     *
     * @param columns the target columns, as for {@link Builder#insertUsing(List, Builder)}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @param columns the target columns, as for {@link Builder#insertUsing(List, Builder)}
     * @param query the subquery, never {@code null}; compiled now
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if an argument is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot ignore errors, as the base
     *     grammar cannot; nothing runs then
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final int insertOrIgnoreUsing(List<String> columns, Builder query) {
        Objects.requireNonNull(columns, "columns");
        requireTable();
        SubQuery sub = SubQuery.of(query);
        return connection.affectingStatement(grammar.compileInsertOrIgnoreUsing(self(), columns, sub.sql),
                grammar.prepareBindings(sub.bindings));
    }

    /**
     * Inserts the rows a subquery built by a consumer returns, skipping rows that would break a
     * unique or primary key constraint.
     *
     * @param columns the target columns, as for {@link Builder#insertUsing(List, Builder)}
     * @param query fills a fresh builder with the subquery, never {@code null}
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if an argument is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot ignore errors
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
        String sql = grammar.compileUpdate(self(), checked);
        return connection.update(sql,
                grammar.prepareBindings(grammar.prepareBindingsForUpdate(self(), checked)));
    }

    /**
     * Adds 1 to a column of the matching rows.
     *
     * @param column the column name, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int increment(String column, Number amount) {
        return increment(column, amount, Collections.emptyMap());
    }

    /**
     * Adds an amount to a column of the matching rows and sets other columns too.
     *
     * @param column the column name, never {@code null}
     * @param amount the amount, as for {@link Builder#increment(String, Number)}
     * @param extra other columns and values to set, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the amount is not finite or not a standard number type
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int decrement(String column) {
        return decrement(column, 1);
    }

    /**
     * Subtracts an amount from a column of the matching rows.
     *
     * @param column the column name, never {@code null}
     * @param amount the amount, as for {@link Builder#increment(String, Number)}
     * @return the number of rows changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the amount is not finite or not a standard number type
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int decrement(String column, Number amount) {
        return decrement(column, amount, Collections.emptyMap());
    }

    /**
     * Subtracts an amount from a column of the matching rows and sets other columns too.
     *
     * @param column the column name, never {@code null}
     * @param amount the amount, as for {@link Builder#increment(String, Number)}
     * @param extra other columns and values to set, never {@code null}
     * @return the number of rows changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the amount is not finite or not a standard number type
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int decrementEach(Map<String, ? extends Number> amounts, Map<String, ?> extra) {
        return step(amounts, extra, " - ");
    }

    /**
     * Deletes the matching rows. A query with joins needs a database grammar that rewrites it, as
     * for {@link Builder#update(Map)}.
     *
     * @return the number of rows deleted
     * @throws UnsupportedOperationException if the query has joins and the grammar cannot
     *     delete with joins
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the delete
     */
    public final int delete() {
        requireTable();
        return connection.delete(grammar.compileDelete(self()),
                grammar.prepareBindings(grammar.prepareBindingsForDelete(self())));
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
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
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement;
     *     inside the transaction, every statement is then undone
     */
    public final void truncate() {
        requireTable();
        Map<String, List<Object>> statements = grammar.compileTruncate(self());
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
