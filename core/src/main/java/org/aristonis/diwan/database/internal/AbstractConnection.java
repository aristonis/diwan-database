package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryKind;
import org.aristonis.diwan.database.event.ConnectionEvents;
import org.aristonis.diwan.database.event.QueryExecuted;
import org.aristonis.diwan.database.event.QueryFailed;
import org.aristonis.diwan.database.exception.ForeignKeyViolationException;
import org.aristonis.diwan.database.exception.MultipleColumnsSelectedException;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.RowCursor;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.query.internal.MappingCursor;

/**
 * Holds the SQL methods every connection shares: argument checks, the mapping of each method to a
 * session call, and the shaping of results. Subclasses decide only where a checked statement runs,
 * through {@link #run(PendingStatement)}, which keeps one execution path for every statement.
 */
abstract class AbstractConnection implements Connection {

    /** Stands in for the SQL in errors raised while reading the database description. */
    private static final String DRIVER_INFO = "(read database description)";

    private final ConnectionSettings settings;

    AbstractConnection(ConnectionSettings settings) {
        this.settings = settings;
    }

    final ConnectionSettings settings() {
        return settings;
    }

    /** Runs a statement whose SQL and bindings were already checked. */
    abstract <T> T run(PendingStatement<T> statement);

    /**
     * Opens a cursor for a read whose SQL and bindings were already checked, on the session this
     * connection runs on.
     */
    abstract RowCursor openCursor(String sql, List<Object> bindings);

    /** Translates a driver failure into the exception callers see, keeping the driver failure as cause. */
    final QueryException queryFailure(String sql, List<Object> bindings, DriverException failure) {
        if (failure.isForeignKeyViolation()) {
            return new ForeignKeyViolationException(
                    settings.name(), sql, bindings, failure, settings.showBindingsInErrors());
        }
        if (failure.isUniqueViolation()) {
            return new UniqueConstraintViolationException(
                    settings.name(), sql, bindings, failure, settings.showBindingsInErrors());
        }
        return new QueryException(settings.name(), sql, bindings, failure, settings.showBindingsInErrors());
    }

    @Override
    public final boolean statement(String sql, Object... bindings) {
        return execute(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.STATEMENT);
    }

    @Override
    public final boolean statement(String sql, List<?> bindings) {
        return execute(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.STATEMENT);
    }

    @Override
    public final int affectingStatement(String sql, Object... bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.AFFECTING);
    }

    @Override
    public final int affectingStatement(String sql, List<?> bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.AFFECTING);
    }

    @Override
    public final boolean insert(String sql, Object... bindings) {
        return execute(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.INSERT);
    }

    @Override
    public final boolean insert(String sql, List<?> bindings) {
        return execute(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.INSERT);
    }

    @Override
    public final int update(String sql, Object... bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.UPDATE);
    }

    @Override
    public final int update(String sql, List<?> bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.UPDATE);
    }

    @Override
    public final int delete(String sql, Object... bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.DELETE);
    }

    @Override
    public final int delete(String sql, List<?> bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.DELETE);
    }

    @Override
    public final List<Map<String, Object>> select(String sql, Object... bindings) {
        return query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.SELECT);
    }

    @Override
    public final List<Map<String, Object>> select(String sql, List<?> bindings) {
        return query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.SELECT);
    }

    @Override
    public final Optional<Map<String, Object>> selectOne(String sql, Object... bindings) {
        return first(query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.SELECT_ONE));
    }

    @Override
    public final Optional<Map<String, Object>> selectOne(String sql, List<?> bindings) {
        return first(query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.SELECT_ONE));
    }

    @Override
    public final Optional<Object> scalar(String sql, Object... bindings) {
        return onlyValue(sql, query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.SCALAR));
    }

    @Override
    public final Optional<Object> scalar(String sql, List<?> bindings) {
        return onlyValue(sql, query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings), QueryKind.SCALAR));
    }

    @Override
    public final QueryCursor<Map<String, Object>> cursor(String sql, Object... bindings) {
        return wrap(timedCursor(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings)));
    }

    @Override
    public final QueryCursor<Map<String, Object>> cursor(String sql, List<?> bindings) {
        return wrap(timedCursor(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings)));
    }

    @Override
    public final boolean unprepared(String sql) {
        Objects.requireNonNull(sql, "sql");
        return timed(QueryKind.STATEMENT, new PendingStatement<Boolean>(sql, Collections.emptyList(),
                (session, text, values) -> {
                    session.executeUnprepared(text);
                    return Boolean.TRUE;
                }, Boolean.TRUE));
    }

    @Override
    public final String getName() {
        return settings.name();
    }

    @Override
    public final String getTablePrefix() {
        return settings.tablePrefix();
    }

    @Override
    public final DriverInfo getDriverInfo() {
        try {
            return settings.driverInfo();
        } catch (DriverException failure) {
            throw queryFailure(DRIVER_INFO, Collections.<Object>emptyList(), failure);
        }
    }

    private boolean execute(String sql, List<Object> bindings, QueryKind kind) {
        // The session's answer only says whether a result set came back; callers are told that the
        // statement ran.
        return timed(kind, new PendingStatement<Boolean>(sql, bindings, (session, text, values) -> {
            session.execute(text, values);
            return Boolean.TRUE;
        }, Boolean.TRUE));
    }

    private int affect(String sql, List<Object> bindings, QueryKind kind) {
        return timed(kind, new PendingStatement<Integer>(sql, bindings, (session, text, values) ->
                session.update(text, values), 0));
    }

    private List<Map<String, Object>> query(String sql, List<Object> bindings, QueryKind kind) {
        return timed(kind, new PendingStatement<List<Map<String, Object>>>(sql, bindings,
                (session, text, values) -> frozenRows(session.query(text, values)),
                Collections.<Map<String, Object>>emptyList()));
    }

    /** Returns the time since {@code startNanos}; a recorder that sends nothing reports zero. */
    Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    // Nothing is timed or allocated while no listener, log or slow-query handler is active, and a
    // statement run by a listener itself fires nothing.
    private <T> T timed(QueryKind kind, PendingStatement<T> statement) {
        ConnectionEvents events = events();
        if (!events.isActive() || events.isDispatching()) {
            return run(statement);
        }
        long start = System.nanoTime();
        T result;
        try {
            result = run(statement);
        } catch (QueryException failure) {
            if (events.hasListeners()) {
                events.failed(new QueryFailed(settings.name(), kind, statement.sql(), statement.bindings(),
                        elapsedSince(start), transactionLevel(), failure));
            }
            throw failure;
        }
        events.executed(this, new QueryExecuted(settings.name(), kind, statement.sql(), statement.bindings(),
                elapsedSince(start), transactionLevel()));
        return result;
    }

    private static QueryCursor<Map<String, Object>> wrap(RowCursor rows) {
        return new MappingCursor<Map<String, Object>>(rows, Function.<Map<String, Object>>identity());
    }

    // The event covers opening the cursor only, not reading its rows.
    private RowCursor timedCursor(String sql, List<Object> bindings) {
        ConnectionEvents events = events();
        if (!events.isActive() || events.isDispatching()) {
            return openCursor(sql, bindings);
        }
        long start = System.nanoTime();
        RowCursor cursor;
        try {
            cursor = openCursor(sql, bindings);
        } catch (QueryException failure) {
            if (events.hasListeners()) {
                events.failed(new QueryFailed(settings.name(), QueryKind.CURSOR, sql, bindings,
                        elapsedSince(start), transactionLevel(), failure));
            }
            throw failure;
        }
        try {
            events.executed(this, new QueryExecuted(settings.name(), QueryKind.CURSOR, sql, bindings,
                    elapsedSince(start), transactionLevel()));
        } catch (RuntimeException | Error listenerFailure) {
            closeAfterListenerFailure(cursor, listenerFailure);
            throw listenerFailure;
        }
        return cursor;
    }

    // A cursor the caller never received could not be closed by anyone else.
    private static void closeAfterListenerFailure(RowCursor cursor, Throwable listenerFailure) {
        try {
            cursor.close();
        } catch (Throwable closeFailure) {
            Sessions.suppress(listenerFailure, closeFailure);
        }
    }

    @Override
    public final ConnectionEvents events() {
        return settings.events();
    }

    @Override
    public void afterCommit(Runnable callback) {
        Objects.requireNonNull(callback, "callback").run();
    }

    @Override
    public void afterRollback(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
    }

    // Copied because the driver contract lets drivers return mutable lists and maps.
    private static List<Map<String, Object>> frozenRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Optional<Map<String, Object>> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Optional.<Map<String, Object>>empty() : Optional.of(rows.get(0));
    }

    private static Optional<Object> onlyValue(String sql, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        if (row.size() > 1) {
            throw new MultipleColumnsSelectedException(sql);
        }
        return row.isEmpty() ? Optional.empty() : Optional.ofNullable(row.values().iterator().next());
    }
}
