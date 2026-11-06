package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collection;
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
        return connection.insert(sql.get(), grammar.prepareBindings(rowValues(rows)));
    }

    // The values of the rows in the column order the grammar wrote them, Expressions left out.
    private List<Object> rowValues(List<? extends Map<String, ?>> rows) {
        List<Object> values = new ArrayList<>();
        if (!rows.get(0).isEmpty()) {
            List<String> keys = grammar.insertColumns(rows);
            for (Map<String, ?> row : rows) {
                for (String key : keys) {
                    Clauses.addInsertValue(values, row.get(key));
                }
            }
        }
        return values;
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
     * Inserts one row, skipping it when it would break a unique or primary key constraint.
     *
     * @param row the columns and values, never {@code null}; an {@link Expression} is written as
     *     given
     * @return the number of rows inserted, 0 or 1
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws UnsupportedOperationException if the grammar cannot ignore errors, as the base
     *     grammar cannot; nothing runs then
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final int insertOrIgnore(Map<String, ?> row) {
        return insertOrIgnore(Collections.singletonList(Objects.requireNonNull(row, "row")));
    }

    /**
     * Inserts several rows in one statement, skipping each that would break a unique or primary
     * key constraint: {@code insert or ignore} on SQLite, {@code on conflict do nothing} on
     * PostgreSQL. SQLite also ignores a failed {@code not null} or {@code check} constraint this
     * way. No rows runs nothing and returns 0.
     *
     * <p>The statement is one round trip and is never split. Its bindings are the row values, so
     * the batch must stay under the driver's limit: 999 bindings on SQLite before 3.32 and 32766
     * after, 65535 on PostgreSQL. Beyond that the database refuses the statement; chunk the rows
     * yourself, inside a transaction when the chunks must succeed together.
     *
     * @param rows the rows, never {@code null}, all with the same columns
     * @return the number of rows inserted, as the connection reports it
     * @throws NullPointerException if {@code rows} or a row is {@code null}
     * @throws IllegalArgumentException if the rows have different columns
     * @throws UnsupportedOperationException if the grammar cannot ignore errors, as the base
     *     grammar cannot; nothing runs then
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the insert
     */
    public final int insertOrIgnore(List<? extends Map<String, ?>> rows) {
        Objects.requireNonNull(rows, "rows");
        requireTable();
        if (rows.isEmpty()) {
            return 0;
        }
        // Compiling first applies the insert rules, so rows with different columns are refused.
        grammar.compileInsert(self(), rows);
        String sql = grammar.compileInsertOrIgnore(self(), rows);
        return connection.affectingStatement(sql, grammar.prepareBindings(rowValues(rows)));
    }

    /**
     * Inserts the rows, and where a row conflicts on the unique columns, sets every column of the
     * first row from the row that was refused. SQLite writes {@code on conflict ... do update set
     * "c" = "excluded"."c"}, as does PostgreSQL.
     *
     * <p>The statement is one round trip and is never split. Its bindings are the row values, so
     * the batch must stay under the driver's limit: 999 bindings on SQLite before 3.32 and 32766
     * after, 65535 on PostgreSQL. Beyond that the database refuses the statement; chunk the rows
     * yourself, inside a transaction when the chunks must succeed together.
     *
     * <p>Two rows of one batch with the same unique key behave differently: SQLite applies them in
     * order, so the last one wins; PostgreSQL refuses the statement ("cannot affect row a second
     * time"). Remove duplicate keys from the batch first.
     *
     * @param rows the rows, never {@code null}, all with the same columns, none empty
     * @param uniqueBy the columns of the unique index that decides a conflict, never {@code null}
     *     or empty; the database refuses the statement if no such unique index exists
     * @return the number of rows written, as the connection reports it; 0 for no rows
     * @throws NullPointerException if an argument, a row or a name in {@code uniqueBy} is {@code null}
     * @throws IllegalArgumentException if {@code uniqueBy} is empty, a row has no columns or the
     *     rows have different columns
     * @throws UnsupportedOperationException if the grammar or the database version cannot upsert;
     *     SQLite older than 3.24, as in Android before API 30, refuses it; nothing runs then
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the statement
     */
    public final int upsert(List<? extends Map<String, ?>> rows, List<String> uniqueBy) {
        return upsert(rows, uniqueBy, (List<String>) null);
    }

    /**
     * Inserts the rows, and where a row conflicts on the unique columns, sets the named columns
     * from the row that was refused.
     *
     * @param rows the rows, as for {@link Builder#upsert(List, List)}
     * @param uniqueBy the columns of the unique index that decides a conflict, never {@code null}
     *     or empty
     * @param update the columns to take from the refused row; {@code null} for every column of
     *     the first row; empty to leave a conflicting row as it is ({@code on conflict do
     *     nothing}), where Laravel would run a plain insert that fails on the conflict
     * @return the number of rows written, as the connection reports it; 0 for no rows
     * @throws NullPointerException if {@code rows}, {@code uniqueBy}, a row or a name in
     *     {@code uniqueBy} or {@code update} is {@code null}
     * @throws IllegalArgumentException if {@code uniqueBy} is empty, a row has no columns or the
     *     rows have different columns
     * @throws UnsupportedOperationException if the grammar or the database version cannot upsert
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the statement
     */
    public final int upsert(List<? extends Map<String, ?>> rows, List<String> uniqueBy, List<String> update) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(uniqueBy, "uniqueBy");
        List<String> excluded = update != null || rows.isEmpty() ? update
                : new ArrayList<>(Objects.requireNonNull(rows.get(0), "row").keySet());
        return runUpsert(rows, uniqueBy, excluded == null ? Collections.emptyList() : excluded,
                Collections.emptyMap());
    }

    /**
     * Inserts the rows, and where a row conflicts on the unique columns, sets the given columns
     * to the given values, bound after the row values.
     *
     * @param rows the rows, as for {@link Builder#upsert(List, List)}
     * @param uniqueBy the columns of the unique index that decides a conflict, never {@code null}
     *     or empty
     * @param update the columns and the values to set, never {@code null}; an {@link Expression}
     *     is written as given; empty leaves a conflicting row as it is
     * @return the number of rows written, as the connection reports it; 0 for no rows
     * @throws NullPointerException if an argument, a row, or a name in {@code uniqueBy} or a key of
     *     {@code update} is {@code null}
     * @throws IllegalArgumentException if {@code uniqueBy} is empty, a row has no columns, the
     *     rows have different columns or a value is a list, map or array
     * @throws UnsupportedOperationException if the grammar or the database version cannot upsert
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the statement
     */
    public final int upsert(List<? extends Map<String, ?>> rows, List<String> uniqueBy, Map<String, ?> update) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(uniqueBy, "uniqueBy");
        return runUpsert(rows, uniqueBy, Collections.emptyList(), Objects.requireNonNull(update, "update"));
    }

    private int runUpsert(List<? extends Map<String, ?>> rows, List<String> uniqueBy, List<String> excluded,
            Map<String, ?> values) {
        if (uniqueBy.isEmpty()) {
            throw new IllegalArgumentException("An upsert needs at least one unique column");
        }
        requireNoNullName(uniqueBy, "uniqueBy");
        requireNoNullName(excluded, "update");
        requireNoNullName(values.keySet(), "update");
        requireTable();
        if (rows.isEmpty()) {
            return 0;
        }
        if (Objects.requireNonNull(rows.get(0), "row").isEmpty()) {
            throw new IllegalArgumentException("An upsert row needs at least one column");
        }
        grammar.checkUpsert(connection.getDriverInfo().databaseVersion());
        // Compiling first applies the insert rules, so rows with different columns are refused.
        grammar.compileInsert(self(), rows);
        String sql = grammar.compileUpsert(self(), rows, uniqueBy, excluded, values);
        List<Object> bindings = rowValues(rows);
        for (Object value : values.values()) {
            Clauses.addInsertValue(bindings, value);
        }
        return connection.affectingStatement(sql, grammar.prepareBindings(bindings));
    }

    private static void requireNoNullName(Collection<String> names, String argument) {
        for (String name : names) {
            Objects.requireNonNull(name, argument + " column name");
        }
    }

    /**
     * Updates the first row matching the attributes, or inserts the attributes. It reads, then
     * writes, in two statements, so it is not atomic: two callers can both find no row and both
     * insert, or one insert can fail on a unique index the other just filled. Wrap it in a
     * transaction with a lock, or use {@link Builder#upsert(List, List)} on a unique index, which is
     * one atomic statement, when that matters.
     *
     * @param attributes the columns to match and to insert, never {@code null}
     * @return {@code true}
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final boolean updateOrInsert(Map<String, ?> attributes) {
        return updateOrInsert(attributes, Collections.emptyMap());
    }

    /**
     * Updates the first row matching the attributes with the values, or inserts the attributes
     * merged with the values. It reads, then writes, in two statements, so it is not atomic; see
     * {@link Builder#updateOrInsert(Map)}. The query's own conditions apply to the read and the
     * update, and this builder is left unchanged.
     *
     * @param attributes the columns to match and to insert, never {@code null}
     * @param values the columns to set, never {@code null}; the value wins over an attribute of
     *     the same name on insert; empty leaves a found row as it is
     * @return {@code true} when a row was inserted, or found and left as it is or changed;
     *     {@code false} when the update changed no row
     * @throws NullPointerException if an argument is {@code null}
     * @throws UnsupportedOperationException if the query has joins and the grammar cannot update
     *     with joins
     * @throws IllegalStateException if the query reads no table, as after {@link Builder#fromSub}
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final boolean updateOrInsert(Map<String, ?> attributes, Map<String, ?> values) {
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(values, "values");
        requireTable();
        if (!self().clone().where(attributes).exists()) {
            Map<String, Object> merged = new LinkedHashMap<>(attributes);
            merged.putAll(values);
            return insert(merged);
        }
        if (values.isEmpty()) {
            return true;
        }
        return self().clone().where(attributes).limit(1).update(values) > 0;
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
