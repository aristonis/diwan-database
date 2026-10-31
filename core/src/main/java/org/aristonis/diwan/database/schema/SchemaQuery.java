package org.aristonis.diwan.database.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A query a grammar returns for the schema builder to run, with its binding values.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class SchemaQuery {

    private final String sql;
    private final List<Object> bindings;

    /**
     * Creates a query.
     *
     * @param sql the SQL, never {@code null}
     * @param bindings the positional binding values, never {@code null}; copied
     * @throws NullPointerException if an argument is {@code null}
     */
    public SchemaQuery(String sql, List<?> bindings) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.bindings = Collections.unmodifiableList(new ArrayList<Object>(Objects.requireNonNull(bindings, "bindings")));
    }

    /**
     * Returns the SQL.
     *
     * @return the SQL, never {@code null}
     */
    public String sql() {
        return sql;
    }

    /**
     * Returns the binding values.
     *
     * @return an unmodifiable list in positional order, never {@code null}
     */
    public List<Object> bindings() {
        return bindings;
    }
}
