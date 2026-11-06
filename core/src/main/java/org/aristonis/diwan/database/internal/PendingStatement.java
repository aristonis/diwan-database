package org.aristonis.diwan.database.internal;

import java.util.List;
import org.aristonis.diwan.database.driver.DriverSession;

/**
 * Describes one checked statement on its way to a session: the SQL, its bindings, how to run it,
 * and what a recording connection answers in its place.
 *
 * @param <T> the type of the session's answer
 */
final class PendingStatement<T> {

    /** Runs a statement on a session. */
    @FunctionalInterface
    interface SessionCall<T> {
        T run(DriverSession session, String sql, List<Object> bindings);
    }

    private final String sql;
    private final List<Object> bindings;
    private final SessionCall<T> call;
    private final T pretendResult;

    PendingStatement(String sql, List<Object> bindings, SessionCall<T> call, T pretendResult) {
        this.sql = sql;
        this.bindings = bindings;
        this.call = call;
        this.pretendResult = pretendResult;
    }

    String sql() {
        return sql;
    }

    List<Object> bindings() {
        return bindings;
    }

    T runOn(DriverSession session) {
        return call.run(session, sql, bindings);
    }

    T pretendResult() {
        return pretendResult;
    }
}
