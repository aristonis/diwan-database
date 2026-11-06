package org.aristonis.diwan.database;

import org.aristonis.diwan.database.internal.Bindings;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Records one statement and its bindings, as captured by {@link Connection#pretend(PretendCallback)}.
 *
 * <p>Instances are immutable, have value semantics and are safe to share between threads, provided
 * the binding values themselves are not changed.
 */
public final class LoggedQuery {

    private final String sql;
    private final List<Object> bindings;

    /**
     * Creates a record of a statement.
     *
     * @param sql the statement, never {@code null}
     * @param bindings the positional binding values, never {@code null}, elements may be
     *     {@code null}; the list and every {@code byte[]} in it are copied, so later changes to
     *     them have no effect
     * @throws NullPointerException if {@code sql} or {@code bindings} is {@code null}
     */
    public LoggedQuery(String sql, List<?> bindings) {
        this.sql = Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(bindings, "bindings");
        this.bindings = Collections.unmodifiableList(Bindings.copyArrays(bindings));
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
     * Compares this record with another object.
     *
     * @param other the object to compare with, may be {@code null}
     * @return {@code true} if {@code other} is a {@code LoggedQuery} with equal SQL and bindings,
     *     {@code byte[]} values compared by content
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LoggedQuery)) {
            return false;
        }
        LoggedQuery that = (LoggedQuery) other;
        return sql.equals(that.sql) && Arrays.deepEquals(bindings.toArray(), that.bindings.toArray());
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * sql.hashCode() + Arrays.deepHashCode(bindings.toArray());
    }

    /**
     * Returns the SQL followed by the number of bindings, such as {@code select ? [1 binding]}.
     *
     * @apiNote The values are left out because they can hold personal data and this text tends to
     *     end up in logs; use {@link #bindings()} to read them.
     *
     * @return the description, never {@code null}
     */
    @Override
    public String toString() {
        int count = bindings.size();
        return sql + " [" + count + (count == 1 ? " binding]" : " bindings]");
    }
}
