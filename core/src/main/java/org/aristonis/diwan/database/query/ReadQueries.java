package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;

/**
 * The read layer of {@link Builder}: running the query and reading rows, single values and
 * whether rows exist.
 */
abstract class ReadQueries extends SubqueryClauses {

    ReadQueries(Connection connection, QueryGrammar grammar, Processor processor) {
        super(connection, grammar, processor);
    }
    /**
     * Runs the query and returns its rows.
     *
     * @return the rows as {@link Processor#processSelect} returns them, never {@code null}
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
     * @throws UnsupportedOperationException if the grammar does not support a clause
     */
    public final List<Map<String, Object>> get() {
        return processor.processSelect(self(), runSelect());
    }

    /**
     * Runs the query limited to one row, on a copy, and returns that row. Laravel sets the limit on
     * the builder itself; here the builder is left as it was.
     *
     * @return the first row, empty if there is none
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
     */
    public final Optional<Map<String, Object>> first() {
        List<Map<String, Object>> rows = self().clone().limit(1).get();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Finds the row whose {@code id} equals the value, on a copy.
     *
     * @param id the key, never {@code null}
     * @return the row, empty if there is none
     * @throws NullPointerException if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} is a list, map, array or builder
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
     */
    public final Optional<Map<String, Object>> find(Object id) {
        Objects.requireNonNull(id, "id");
        return self().clone().where(DEFAULT_KEY, "=", id).first();
    }

    /**
     * Returns one column of the first row. If the query selects no columns, only this one is
     * selected.
     *
     * @param column the column name, never {@code null}
     * @return the value, empty if there is no row or the value is SQL {@code NULL}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
     */
    public final Optional<Object> value(String column) {
        Objects.requireNonNull(column, "column");
        Builder copy = self().clone();
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
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
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
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
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
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
     */
    public final boolean exists() {
        List<Map<String, Object>> rows = connection.select(grammar.compileExists(withoutLock()),
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
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the query
     */
    public final boolean doesntExist() {
        return !exists();
    }

    /**
     * Runs a read of this query after the lock checks. Refuses a row lock outside a transaction on a
     * database where the lock ends with the statement; not tied to the connection's strict
     * transactions flag, because such a lock never holds anything. A lock with a timeout runs the
     * read under that limit, which applies to that read only.
     */
    final <T> T runLocked(Supplier<T> statement) {
        if (lock != null && lock.timeoutMillis().isPresent()) {
            return runWithLockTimeout(lock.timeoutMillis().getAsLong(), statement);
        }
        if (lock != null && grammar.locksNeedTransaction() && connection.transactionLevel() == 0) {
            throw new IllegalStateException(
                    "A row lock outside a transaction is released at once; run the query inside transaction(...)");
        }
        return statement.get();
    }

    /** Limits the lock wait of the read; the grammar refuses a timed lock first if it must. */
    private <T> T runWithLockTimeout(long millis, Supplier<T> statement) {
        toSql();
        if (connection.transactionLevel() == 0) {
            throw new IllegalStateException(
                    "A lock wait timeout on a statement needs an open transaction");
        }
        return connection.withStatementLockWait(millis, statement);
    }

    private List<Map<String, Object>> runSelect() {
        return runLocked(() -> connection.select(toSql(), grammar.prepareBindings(getBindings())));
    }

    /** Runs a copy selecting only the plucked columns when the query selects none, as Laravel does. */
    private List<Map<String, Object>> selectFor(String column, String key) {
        Builder copy = self().clone();
        if (copy.columns.isEmpty()) {
            copy.columns.add(column);
            if (key != null && !key.equals(column)) {
                copy.columns.add(key);
            }
        }
        return copy.get();
    }
}
