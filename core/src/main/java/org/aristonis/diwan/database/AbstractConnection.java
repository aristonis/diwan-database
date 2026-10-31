package org.aristonis.diwan.database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.driver.DriverException;
import org.aristonis.diwan.database.driver.DriverInfo;

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

    /** Translates a driver failure into the exception callers see, keeping the driver failure as cause. */
    final QueryException queryFailure(String sql, List<Object> bindings, DriverException failure) {
        if (failure.isUniqueViolation()) {
            return new UniqueConstraintViolationException(
                    settings.name(), sql, bindings, failure, settings.showBindingsInErrors());
        }
        return new QueryException(settings.name(), sql, bindings, failure, settings.showBindingsInErrors());
    }

    @Override
    public final boolean statement(String sql, Object... bindings) {
        return execute(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final boolean statement(String sql, List<?> bindings) {
        return execute(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final int affectingStatement(String sql, Object... bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final int affectingStatement(String sql, List<?> bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final boolean insert(String sql, Object... bindings) {
        return execute(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final boolean insert(String sql, List<?> bindings) {
        return execute(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final int update(String sql, Object... bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final int update(String sql, List<?> bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final int delete(String sql, Object... bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final int delete(String sql, List<?> bindings) {
        return affect(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final List<Map<String, Object>> select(String sql, Object... bindings) {
        return query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final List<Map<String, Object>> select(String sql, List<?> bindings) {
        return query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings));
    }

    @Override
    public final Optional<Map<String, Object>> selectOne(String sql, Object... bindings) {
        return first(query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings)));
    }

    @Override
    public final Optional<Map<String, Object>> selectOne(String sql, List<?> bindings) {
        return first(query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings)));
    }

    @Override
    public final Optional<Object> scalar(String sql, Object... bindings) {
        return onlyValue(sql, query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings)));
    }

    @Override
    public final Optional<Object> scalar(String sql, List<?> bindings) {
        return onlyValue(sql, query(Objects.requireNonNull(sql, "sql"), Bindings.check(bindings)));
    }

    @Override
    public final boolean unprepared(String sql) {
        Objects.requireNonNull(sql, "sql");
        return run(new PendingStatement<Boolean>(sql, Collections.emptyList(), (session, text, values) -> {
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

    private boolean execute(String sql, List<Object> bindings) {
        // The session's answer only says whether a result set came back; callers are told that the
        // statement ran.
        return run(new PendingStatement<Boolean>(sql, bindings, (session, text, values) -> {
            session.execute(text, values);
            return Boolean.TRUE;
        }, Boolean.TRUE));
    }

    private int affect(String sql, List<Object> bindings) {
        return run(new PendingStatement<Integer>(sql, bindings, (session, text, values) ->
                session.update(text, values), 0));
    }

    private List<Map<String, Object>> query(String sql, List<Object> bindings) {
        return run(new PendingStatement<List<Map<String, Object>>>(sql, bindings, (session, text, values) ->
                frozenRows(session.query(text, values)), Collections.<Map<String, Object>>emptyList()));
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
