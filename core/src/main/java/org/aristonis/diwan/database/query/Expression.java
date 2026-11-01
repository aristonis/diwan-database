package org.aristonis.diwan.database.query;

import java.util.Objects;

/**
 * A piece of SQL written into a query exactly as given and never bound, for example
 * {@code count(*)} in a select list or {@code now()} as a value.
 *
 * <p>The text is never escaped or quoted. Build expressions only from constants in your code,
 * never from user input; pass user values as bindings instead.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class Expression {

    private final String sql;

    private Expression(String sql) {
        this.sql = sql;
    }

    /**
     * Wraps trusted SQL text.
     *
     * @param sql the SQL, never {@code null} or blank; written as given, so it must not come from
     *     user input
     * @return the expression, never {@code null}
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     */
    public static Expression raw(String sql) {
        Objects.requireNonNull(sql, "sql");
        if (sql.trim().isEmpty()) {
            throw new IllegalArgumentException("Expression SQL is empty");
        }
        return new Expression(sql);
    }

    /**
     * Returns the SQL text.
     *
     * @return the text, never {@code null}
     */
    public String sql() {
        return sql;
    }

    /**
     * Compares this expression with another object.
     *
     * @param other the object to compare with, may be {@code null}
     * @return {@code true} if {@code other} is an expression with the same text
     */
    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Expression && sql.equals(((Expression) other).sql));
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return sql.hashCode();
    }

    /**
     * Returns the SQL text.
     *
     * @return the text, never {@code null}
     */
    @Override
    public String toString() {
        return sql;
    }
}
