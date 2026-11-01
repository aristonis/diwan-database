package org.aristonis.diwan.database.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns the raw results of a query into what the builder returns.
 *
 * <p>The base processor passes rows through. A database module extends it where its database
 * reports results its own way, for example the id of an inserted row.
 *
 * <p>Instances hold no state and are safe to share between threads, unless a subclass says
 * otherwise.
 */
public class Processor {

    /** Creates a processor. */
    public Processor() {
    }

    /**
     * Processes the rows of a select.
     *
     * @implSpec The base returns {@code results} unchanged.
     *
     * @param query the query that produced the rows, never {@code null}
     * @param results the rows as the connection returned them, never {@code null}
     * @return the rows, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public List<Map<String, Object>> processSelect(Builder query, List<Map<String, Object>> results) {
        Objects.requireNonNull(query, "query");
        return Objects.requireNonNull(results, "results");
    }

    /**
     * Runs an insert of one row and returns the new key.
     *
     * <p>Laravel's base processor runs the insert and asks PDO for the last insert id. JDBC has no
     * portable equivalent, and the right way differs per database ({@code returning}, or
     * {@code last_insert_rowid()} on the same session), so this is the seam a database module's
     * processor fills.
     *
     * @implSpec The base refuses. An override runs {@code sql} with {@code values} through
     *     {@code query.getConnection()}, or a connection pinned to one session when the key is read
     *     by a second statement, and returns the key through {@link #toId(Object)}.
     *
     * @param query the query, never {@code null}
     * @param sql the compiled insert, never {@code null}
     * @param values the prepared bindings, never {@code null}
     * @param sequence the key column, never {@code null}
     * @return the new key: a {@code Long} when it is numeric, else as the database returned it
     * @throws UnsupportedOperationException always, in the base
     */
    public Object processInsertGetId(Builder query, String sql, List<Object> values, String sequence) {
        throw new UnsupportedOperationException("Returning an inserted key is not supported by "
                + getClass().getSimpleName());
    }

    /**
     * Normalizes a returned key: a whole number becomes a {@code Long}, as Laravel casts a numeric
     * id to an integer; anything else is returned as is.
     *
     * @param raw the key as the database returned it, may be {@code null}
     * @return the key, {@code null} only if {@code raw} is {@code null}
     */
    protected Object toId(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String && ((String) raw).matches("-?\\d{1,18}")) {
            return Long.valueOf((String) raw);
        }
        return raw;
    }
}
