package org.aristonis.diwan.database;

import java.util.Objects;

/**
 * Reports that {@link Connection#scalar(String, Object...)} read a row with more than one column.
 *
 * <p>The SQL in the message has its line breaks escaped and is cut after 1000 characters.
 *
 * <p>Instances are immutable after construction and safe to share between threads.
 */
public final class MultipleColumnsSelectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure for a statement.
     *
     * @param sql the statement that selected several columns, never {@code null}
     * @throws NullPointerException if {@code sql} is {@code null}
     */
    public MultipleColumnsSelectedException(String sql) {
        super("Scalar query selected more than one column: " + MessageText.sql(Objects.requireNonNull(sql, "sql")));
    }
}
