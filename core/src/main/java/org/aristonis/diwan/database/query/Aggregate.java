package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import java.util.List;

/**
 * The aggregate function a query computes instead of its columns, for example {@code count(*)}.
 *
 * <p>Instances are immutable.
 */
public final class Aggregate {

    private final String function;
    private final List<Object> columns;

    /**
     * Creates the aggregate.
     *
     * @param function the SQL function name, such as {@code count} or {@code max}
     * @param columns the columns passed to the function, each a {@code String} or an
     *     {@link Expression}, never {@code null}; copied
     * @throws NullPointerException if {@code columns} is {@code null}
     */
    public Aggregate(String function, List<?> columns) {
        this.function = function;
        this.columns = Clauses.copy(columns);
    }

    /**
     * Returns the SQL function name.
     *
     * @return the function name
     */
    public String function() {
        return function;
    }

    /**
     * Returns the columns passed to the function.
     *
     * @return the columns, unmodifiable, never {@code null}
     */
    public List<Object> columns() {
        return columns;
    }
}
