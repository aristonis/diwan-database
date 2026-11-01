package org.aristonis.diwan.database.query;

/**
 * The clause a binding belongs to. {@link Builder#getBindings()} flattens the buckets in the
 * order of these constants, which is the order their clauses appear in the compiled SQL.
 *
 * <p>The order is Laravel's full list, including buckets for clauses this version does not build
 * yet ({@link #JOIN}, {@link #UNION}, {@link #UNION_ORDER}), so adding those clauses later cannot
 * reorder the bindings of a query that already works.
 */
public enum BindingType {
    /** Bindings of raw select expressions. */
    SELECT,
    /** Bindings of a raw from clause. */
    FROM,
    /** Bindings of join clauses. */
    JOIN,
    /** Bindings of where clauses. */
    WHERE,
    /** Bindings of raw group by expressions. */
    GROUP_BY,
    /** Bindings of having clauses. */
    HAVING,
    /** Bindings of raw order by expressions. */
    ORDER,
    /** Bindings of union queries. */
    UNION,
    /** Bindings of the order by clause that applies to a union. */
    UNION_ORDER
}
