package org.aristonis.photisma.database;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aristonis.photisma.database.driver.DriverInfo;

/**
 * Sends SQL to one database and manages transactions on it.
 *
 * <p>Every SQL method comes in two forms: one taking varargs bindings and one taking a
 * {@code List<?>}. Binding values are checked before any session is opened; the accepted types are
 * {@code null}, {@link Boolean}, {@link Byte}, {@link Short}, {@link Integer}, {@link Long},
 * {@link Float}, {@link Double}, {@link java.math.BigDecimal}, {@link java.math.BigInteger},
 * {@link String}, {@code byte[]}, {@link java.time.LocalDate}, {@link java.time.LocalTime},
 * {@link java.time.LocalDateTime}, {@link java.time.OffsetDateTime} and {@link java.time.Instant}.
 *
 * <p>A connection obtained from {@link #builder()} holds no session between calls and is safe to
 * share between threads. A connection handed to a {@link TransactionCallback} or
 * {@link PretendCallback}, and a {@link TransactionScope}, belong to the thread that received them.
 *
 * @apiNote This interface is not meant to be implemented outside this library. Methods added in
 *     later versions arrive as {@code default} methods, so code that calls it keeps compiling.
 */
public interface Connection {

    /**
     * Returns a builder for a new root connection.
     *
     * @return a new builder, never {@code null}
     */
    static ConnectionBuilder builder() {
        return new ConnectionBuilder();
    }

    /**
     * Runs any statement.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, elements may be {@code null}; a lone
     *     {@code String[]} or {@code Integer[]} spreads into separate bindings, one per element,
     *     because Java treats it as the varargs array itself
     * @return {@code true} if the statement produced a result set
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    boolean statement(String sql, Object... bindings);

    /**
     * Runs any statement.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be {@code
     *     null}; the list is copied
     * @return {@code true} if the statement produced a result set
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    boolean statement(String sql, List<?> bindings);

    /**
     * Runs a statement and returns the number of rows it affected.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, elements may be {@code null}; a lone
     *     {@code String[]} or {@code Integer[]} spreads into separate bindings, one per element,
     *     because Java treats it as the varargs array itself
     * @return the number of affected rows
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    int affectingStatement(String sql, Object... bindings);

    /**
     * Runs a statement and returns the number of rows it affected.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be {@code
     *     null}; the list is copied
     * @return the number of affected rows
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    int affectingStatement(String sql, List<?> bindings);

    /**
     * Runs an insert statement.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, elements may be {@code null}; a lone
     *     {@code String[]} or {@code Integer[]} spreads into separate bindings, one per element,
     *     because Java treats it as the varargs array itself
     * @return {@code true} once the statement ran
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    boolean insert(String sql, Object... bindings);

    /**
     * Runs an insert statement.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be {@code
     *     null}; the list is copied
     * @return {@code true} once the statement ran
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    boolean insert(String sql, List<?> bindings);

    /**
     * Runs an update statement and returns the number of rows it changed.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, elements may be {@code null}; a lone
     *     {@code String[]} or {@code Integer[]} spreads into separate bindings, one per element,
     *     because Java treats it as the varargs array itself
     * @return the number of affected rows
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    int update(String sql, Object... bindings);

    /**
     * Runs an update statement and returns the number of rows it changed.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be {@code
     *     null}; the list is copied
     * @return the number of affected rows
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    int update(String sql, List<?> bindings);

    /**
     * Runs a delete statement and returns the number of rows it removed.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, elements may be {@code null}; a lone
     *     {@code String[]} or {@code Integer[]} spreads into separate bindings, one per element,
     *     because Java treats it as the varargs array itself
     * @return the number of affected rows
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    int delete(String sql, Object... bindings);

    /**
     * Runs a delete statement and returns the number of rows it removed.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be {@code
     *     null}; the list is copied
     * @return the number of affected rows
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    int delete(String sql, List<?> bindings);

    /**
     * Runs a read and returns its rows.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, elements may be {@code null}; a lone
     *     {@code String[]} or {@code Integer[]} spreads into separate bindings, one per element,
     *     because Java treats it as the varargs array itself
     * @return an unmodifiable list of the rows in result order, each an unmodifiable map whose keys
     *     are the column labels in select order, exactly as the database reports them; never {@code
     *     null}, possibly empty
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    List<Map<String, Object>> select(String sql, Object... bindings);

    /**
     * Runs a read and returns its rows.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be {@code
     *     null}; the list is copied
     * @return an unmodifiable list of the rows in result order, each an unmodifiable map whose keys
     *     are the column labels in select order, exactly as the database reports them; never {@code
     *     null}, possibly empty
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    List<Map<String, Object>> select(String sql, List<?> bindings);

    /**
     * Runs a read and returns its first row.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, elements may be {@code null}; a lone
     *     {@code String[]} or {@code Integer[]} spreads into separate bindings, one per element,
     *     because Java treats it as the varargs array itself
     * @return the first row as an unmodifiable map in select order, or an empty optional when there
     *     is no row
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    Optional<Map<String, Object>> selectOne(String sql, Object... bindings);

    /**
     * Runs a read and returns its first row.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be {@code
     *     null}; the list is copied
     * @return the first row as an unmodifiable map in select order, or an empty optional when there
     *     is no row
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     */
    Optional<Map<String, Object>> selectOne(String sql, List<?> bindings);

    /**
     * Runs a read and returns the single value of its first row.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, elements may be {@code null}; a lone
     *     {@code String[]} or {@code Integer[]} spreads into separate bindings, one per element,
     *     because Java treats it as the varargs array itself
     * @return the value of the only column of the first row, or an empty optional when there is no
     *     row or the value is SQL {@code NULL}
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     * @throws MultipleColumnsSelectedException if the first row has more than one column
     */
    Optional<Object> scalar(String sql, Object... bindings);

    /**
     * Runs a read and returns the single value of its first row.
     *
     * @param sql the statement to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be {@code
     *     null}; the list is copied
     * @return the value of the only column of the first row, or an empty optional when there is no
     *     row or the value is SQL {@code NULL}
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws QueryException if the database refuses the statement or no session can be opened
     * @throws MultipleColumnsSelectedException if the first row has more than one column
     */
    Optional<Object> scalar(String sql, List<?> bindings);

    /**
     * Runs SQL without preparing it or binding parameters.
     *
     * @apiNote This runs raw SQL exactly as given: nothing is bound, quoted or escaped. Never pass
     *     untrusted text, and never build {@code sql} from user input; use a prepared method with
     *     bindings instead.
     * @param sql the SQL to run, never {@code null}
     * @return {@code true} once the SQL ran
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws QueryException if the database refuses the SQL or no session can be opened
     */
    boolean unprepared(String sql);

    /**
     * Runs a callback inside a transaction and returns its result.
     *
     * <p>On the root connection this starts a real transaction, or a savepoint when the session is
     * already inside a transaction Photisma does not own, commits when the callback returns and
     * rolls back when it throws. Inside a transaction it nests through a savepoint.
     *
     * @param <T> the type of the result
     * @param callback the work to run, never {@code null}
     * @return the result of the callback, may be {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws QueryException if the database refuses to start, commit or roll back the transaction
     * @throws RuntimeException any failure of the callback, rethrown after the rollback
     */
    <T> T transaction(TransactionCallback<T> callback);

    /**
     * Runs a callback inside a transaction, retrying the whole transaction after a concurrency
     * failure.
     *
     * <p>The callback runs again from the start on each attempt, including any outside effect it
     * has, such as an HTTP call or a sent message. Only the outermost transaction Photisma owns
     * retries; a nested call rethrows a concurrency failure at once.
     *
     * @param <T> the type of the result
     * @param callback the work to run, never {@code null}
     * @param attempts the most times to run the callback, at least 1
     * @return the result of the successful attempt, may be {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is below 1
     * @throws QueryException if the database refuses to start, commit or roll back the transaction
     * @throws RuntimeException the failure of the last attempt, rethrown after the rollback
     */
    <T> T transaction(TransactionCallback<T> callback, int attempts);

    /**
     * Starts a transaction that stays open until the returned scope is committed or closed.
     *
     * @apiNote Use it with try-with-resources so the transaction always ends.
     * @return the open scope, never {@code null}; the caller must close it
     * @throws QueryException if the database refuses to start the transaction
     */
    TransactionScope beginTransaction();

    /**
     * Runs a callback on a connection that records each statement instead of running it.
     *
     * <p>Reads return no rows, row counts are 0, and {@code statement}, {@code insert} and
     * {@code unprepared} return {@code true}. No session is opened.
     *
     * @param callback the work to record, never {@code null}
     * @return an unmodifiable list of the recorded statements in the order they were issued, never
     *     {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws RuntimeException any failure of the callback
     */
    List<LoggedQuery> pretend(PretendCallback callback);

    /**
     * Returns the depth of the transaction this connection runs in.
     *
     * @return 0 on the root connection, otherwise the current nesting depth
     */
    int transactionLevel();

    /**
     * Returns the name the connection was built with.
     *
     * @return the name, never {@code null}
     */
    String getName();

    /**
     * Returns the prefix added to table names.
     *
     * @return the prefix, never {@code null}, empty when none was configured
     */
    String getTablePrefix();

    /**
     * Describes the database behind this connection.
     *
     * <p>In pretend mode this still describes the real database, so code that picks SQL by
     * database can record the statements it would run. A driver may borrow one database connection
     * the first time it is asked, so with a pool of one connection, call it first outside a
     * transaction.
     *
     * @return the driver's description, never {@code null}
     * @throws QueryException if the driver has to ask the database and that fails
     */
    DriverInfo getDriverInfo();
}
