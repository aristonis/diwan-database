package org.aristonis.diwan.database.eloquent.internal;

import org.aristonis.diwan.database.Internal;

/**
 * Numbers the table aliases of self-relation existence subqueries: {@code laravel_reserved_0},
 * {@code laravel_reserved_1}, and so on.
 *
 * <p>Laravel keeps this number in a static field of {@code Relation}, so the alias a query gets
 * depends on every query the process built before it, and two builds of the same query give
 * different SQL. Here one counter belongs to the outermost builder and is shared with its
 * subqueries, so the numbering starts at 0 for every top-level query and is the same each time.
 *
 * <p>Internal: not API. Not thread-safe; a counter belongs to one builder chain on one thread.
 */
@Internal
public final class AliasCounter {

    /** The prefix Laravel gives a self-relation alias. */
    private static final String PREFIX = "laravel_reserved_";

    /** The number the next alias gets. */
    private int next;

    /**
     * Creates a counter whose first alias is {@code laravel_reserved_0}.
     */
    public AliasCounter() {
        this(0);
    }

    private AliasCounter(int next) {
        this.next = next;
    }

    /**
     * Returns the next alias and advances the counter.
     *
     * @return the alias, such as {@code laravel_reserved_0}, never {@code null}
     */
    public String next() {
        return PREFIX + next++;
    }

    /**
     * Returns an independent counter that continues from this one's current value.
     *
     * <p>A copied builder takes a copy, so building on the copy (a pagination count, say) cannot
     * shift the aliases of the original.
     *
     * @return a new counter, never {@code null}
     */
    public AliasCounter copy() {
        return new AliasCounter(next);
    }
}
