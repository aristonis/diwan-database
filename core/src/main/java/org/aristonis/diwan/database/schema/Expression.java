package org.aristonis.diwan.database.schema;

import java.util.Objects;

/**
 * A piece of SQL written into a statement exactly as given, for example a default value computed by
 * the database.
 *
 * <p>The text is never escaped. Build expressions only from constants in your code, never from
 * user input.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class Expression {

    /** The database's current date. */
    public static final Expression CURRENT_DATE = new Expression("CURRENT_DATE");

    /** The database's current date and time. */
    public static final Expression CURRENT_TIMESTAMP = new Expression("CURRENT_TIMESTAMP");

    /** SQL {@code NULL}, the default that {@code defaultValue(null)} sets. */
    public static final Expression NULL = new Expression("null");

    private final String sql;

    private Expression(String sql) {
        this.sql = sql;
    }

    /**
     * Wraps trusted SQL text.
     *
     * @param sql the SQL, never {@code null} or empty; written as given, so it must not come from
     *     user input
     * @return the expression, never {@code null}
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is empty
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
