package org.aristonis.diwan.database.query;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A subquery compiled once, when it is attached: its SQL and its bindings, taken together.
 *
 * <p>Laravel's {@code createSub}: a builder is compiled with its own grammar, raw text is taken as
 * given with no bindings. Both parts are fixed at attach time, so changing the source builder
 * afterwards cannot change the outer query.
 */
final class SubQuery {
    final String sql;
    final List<Object> bindings;

    private SubQuery(String sql, List<Object> bindings) {
        this.sql = sql;
        this.bindings = bindings;
    }

    static SubQuery of(Builder query) {
        Objects.requireNonNull(query, "query");
        return new SubQuery(query.toSql(), query.getBindings());
    }

    static SubQuery of(String sql) {
        Objects.requireNonNull(sql, "query");
        if (sql.trim().isEmpty()) {
            throw new IllegalArgumentException("A raw subquery is empty");
        }
        return new SubQuery(sql, Collections.emptyList());
    }
}
