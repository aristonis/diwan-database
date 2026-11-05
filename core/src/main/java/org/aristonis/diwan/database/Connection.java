package org.aristonis.diwan.database;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.RowCursor;

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
     * <p>On SQLite a script of several statements runs whole, one statement after another. Outside a
     * transaction each statement commits on its own, so a failure partway leaves the earlier ones
     * applied; run the script in a transaction when it must be all or nothing.
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
     * Runs a read and streams its rows through a cursor, one row at a time.
     *
     * <p>The cursor must be closed, best with try-with-resources; it also closes itself when its
     * last row is read. On the root connection it holds a session of its own until then, so a cursor
     * that is neither read to the end nor closed keeps a pooled connection out of the pool. Inside a
     * transaction, a session callback or a transaction scope, it borrows that session: until it is
     * closed, every other statement, cursor, savepoint, commit or rollback on that session is
     * refused, and ending the transaction level or scope that opened it closes it first.
     *
     * <p>Rows are unmodifiable, with columns in select order. A failure while reading closes the
     * cursor and is thrown as a {@link QueryException}. The cursor belongs to the calling thread.
     *
     * @implSpec The default throws {@link UnsupportedOperationException}; every connection of this
     *     library overrides it.
     * @param sql the read to run, never {@code null}
     * @param bindings the positional binding values, as for {@link #select(String, Object...)}
     * @return the open cursor, never {@code null}
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is a {@code null} array or holds a value
     *     of an unsupported type
     * @throws IllegalStateException if a cursor is already open on the borrowed session, or on the
     *     root connection when this thread is inside a transaction it opened (strict mode)
     * @throws UnsupportedOperationException in pretend mode, or if the driver cannot stream
     * @throws QueryException if the database refuses the read or no session can be opened
     */
    default RowCursor cursor(String sql, Object... bindings) {
        throw new UnsupportedOperationException("cursor");
    }

    /**
     * Runs a read and streams its rows through a cursor, one row at a time.
     *
     * <p>The cursor must be closed, best with try-with-resources; it also closes itself when its
     * last row is read. On the root connection it holds a session of its own until then, so a cursor
     * that is neither read to the end nor closed keeps a pooled connection out of the pool. Inside a
     * transaction, a session callback or a transaction scope, it borrows that session: until it is
     * closed, every other statement, cursor, savepoint, commit or rollback on that session is
     * refused, and ending the transaction level or scope that opened it closes it first.
     *
     * <p>Rows are unmodifiable, with columns in select order. A failure while reading closes the
     * cursor and is thrown as a {@link QueryException}. The cursor belongs to the calling thread.
     *
     * @implSpec The default throws {@link UnsupportedOperationException}; every connection of this
     *     library overrides it.
     * @param sql the read to run, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be
     *     {@code null}; the list is copied
     * @return the open cursor, never {@code null}
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code bindings} is {@code null} or holds a value of an
     *     unsupported type
     * @throws IllegalStateException if a cursor is already open on the borrowed session, or on the
     *     root connection when this thread is inside a transaction it opened (strict mode)
     * @throws UnsupportedOperationException in pretend mode, or if the driver cannot stream
     * @throws QueryException if the database refuses the read or no session can be opened
     */
    default RowCursor cursor(String sql, List<?> bindings) {
        throw new UnsupportedOperationException("cursor");
    }

    /**
     * Runs a callback inside a transaction and returns its result.
     *
     * <p>On the root connection this starts a real transaction, or a savepoint when the session is
     * already inside a transaction Diwan Database does not own, commits when the callback returns and
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
     * has, such as an HTTP call or a sent message. Only the outermost transaction Diwan Database owns
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
     * Runs a callback on a connection held to one physical database connection.
     *
     * <p>Every statement and transaction the callback runs on the connection it is given uses the
     * same physical connection, so a session setting such as SQLite's {@code PRAGMA foreign_keys}
     * stays in force for all of it, even when this connection borrows from a pool. No transaction
     * is started; start one inside with {@link #transaction(TransactionCallback)}.
     *
     * <p>When the callback throws, the session is discarded rather than closed
     * ({@code DriverSession.discard}), so a pool that the driver can tell drops the connection, and a
     * setting the callback could not restore does not reach the next user. A callback must not leave
     * a transaction open through raw SQL; use {@link #transaction(TransactionCallback)}.
     *
     * @implSpec The default runs the callback on this connection, which suits a connection already
     *     held to one physical connection, such as one inside a transaction or in pretend mode.
     * @param <T> the type of the result
     * @param callback the work to run, never {@code null}
     * @return the result of the callback, may be {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws QueryException if no physical connection can be opened
     * @throws RuntimeException any failure of the callback
     */
    default <T> T session(TransactionCallback<T> callback) {
        return Objects.requireNonNull(callback, "callback").run(this);
    }

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
     * Returns the registry of listeners, the query log and the slow-query handlers of this connection.
     *
     * <p>Every connection derived from one root, such as a session, a transaction callback connection
     * or a transaction scope, shares the same registry, so a listener added on the root sees the
     * statements and transactions of all of them.
     *
     * @return the shared registry, never {@code null}
     */
    ConnectionEvents events();

    /**
     * Runs a callback once the current transaction has committed.
     *
     * <p>Outside a transaction there is nothing to wait for, so the callback runs at once on the
     * calling thread. Inside a transaction it runs in registration order after the outermost
     * transaction has committed and finished, which means the transaction's connection is already
     * closed: the callback must use the root connection, not the connection it was registered on.
     * A callback registered inside a nested transaction that rolls back is dropped. A callback that
     * throws never causes a rollback; the first failure is rethrown to the caller of the outermost
     * transaction with later ones attached as suppressed failures.
     *
     * <p>Inside a transaction that the caller opened outside this library the outermost level is only
     * a savepoint, so the library cannot know when the caller commits and registration is refused.
     *
     * @param callback the work to run after the commit, never {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalStateException if this connection runs inside a transaction opened outside this
     *     library, or its transaction or scope already ended
     * @throws RuntimeException any failure of the callback when it runs at once outside a transaction
     */
    void afterCommit(Runnable callback);

    /**
     * Runs a callback once the current transaction has rolled back.
     *
     * <p>Outside a transaction there is nothing to roll back, so the callback is dropped and never
     * runs. Inside a transaction it runs in registration order after the outermost transaction has
     * finished, under the same rules as for {@link #afterCommit(Runnable)}, whether its own
     * savepoint or the whole transaction was rolled back. It also runs when the commit itself fails
     * or when the rollback command fails; it is dropped when the outermost transaction
     * commits and its own savepoint was not rolled back.
     *
     * <p>When a transaction is retried, the callbacks of each failed attempt run once, in order, when
     * the whole transaction call ends, on the root connection and on a session connection alike.
     *
     * <p>The callback may run even though the commit landed: when the connection dropped during the
     * COMMIT, its outcome is unknown and the library reports a failed commit. Undo logic must check
     * the database before it compensates, for example before it refunds an order. A callback that throws never
     * changes the transaction's outcome; the first failure is attached to the failure that caused
     * the rollback as a suppressed failure, or rethrown when the transaction itself succeeded.
     *
     * @param callback the work to run after the rollback, never {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     * @throws IllegalStateException if this connection's transaction or scope already ended
     */
    void afterRollback(Runnable callback);

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
