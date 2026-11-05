package org.aristonis.diwan.database;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes a statement that ran successfully.
 *
 * <p>Instances are immutable and safe to share between threads, provided the binding values
 * themselves are not changed.
 */
public final class QueryExecuted {

    private final String connectionName;
    private final QueryKind kind;
    private final String sql;
    private final List<Object> bindings;
    private final Duration time;
    private final int transactionLevel;

    /** Creates an event; the binding list and every {@code byte[]} in it are copied. */
    QueryExecuted(String connectionName, QueryKind kind, String sql, List<?> bindings, Duration time,
            int transactionLevel) {
        this.connectionName = Objects.requireNonNull(connectionName, "connectionName");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sql = Objects.requireNonNull(sql, "sql");
        this.bindings = Collections.unmodifiableList(Bindings.copyArrays(Objects.requireNonNull(bindings, "bindings")));
        this.time = Objects.requireNonNull(time, "time");
        if (time.isNegative()) {
            throw new IllegalArgumentException("time must not be negative");
        }
        if (transactionLevel < 0) {
            throw new IllegalArgumentException("transactionLevel must not be negative");
        }
        this.transactionLevel = transactionLevel;
    }

    /**
     * Returns the name of the connection that ran the statement.
     *
     * @return the name, never {@code null}
     */
    public String connectionName() {
        return connectionName;
    }

    /**
     * Returns which kind of call ran the statement.
     *
     * @return the kind, never {@code null}
     */
    public QueryKind kind() {
        return kind;
    }

    /**
     * Returns the statement.
     *
     * @return the SQL, never {@code null}
     */
    public String sql() {
        return sql;
    }

    /**
     * Returns the binding values.
     *
     * @return an unmodifiable list in positional order, never {@code null}, elements may be
     *     {@code null}; {@code byte[]} values are copies
     */
    public List<Object> bindings() {
        return Collections.unmodifiableList(Bindings.copyArrays(bindings));
    }

    /**
     * Returns how long the statement took.
     *
     * @apiNote A cursor reports the time to open it, not to read it. A pretended statement reports
     *     {@link Duration#ZERO}.
     * @return the time, never {@code null} and never negative
     */
    public Duration time() {
        return time;
    }

    /**
     * Returns the transaction nesting level the statement ran at.
     *
     * @return the level, where 0 means outside any transaction
     */
    public int transactionLevel() {
        return transactionLevel;
    }

    /**
     * Returns a copy whose binding values are replaced by {@code "?"}.
     *
     * @apiNote Pass this to code that must not see personal data or secrets.
     * @return a new event with the same fields and one {@code "?"} per binding, never {@code null}
     */
    public QueryExecuted redacted() {
        List<Object> masked = new ArrayList<Object>(Collections.nCopies(bindings.size(), "?"));
        return new QueryExecuted(connectionName, kind, sql, masked, time, transactionLevel);
    }

    /**
     * Describes the event without the binding values, which can hold personal data.
     *
     * @return a short text with the kind, connection name, SQL and binding count, never {@code null}
     */
    @Override
    public String toString() {
        return "QueryExecuted[" + kind + " on " + connectionName + ": " + sql + " [" + bindings.size()
                + " bindings], " + time.toMillis() + " ms]";
    }
}
