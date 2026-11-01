package org.aristonis.diwan.database.query;

/**
 * One query joined to its builder by {@code union} or {@code union all}.
 *
 * <p>The query is a private copy taken by {@link Builder#union(Builder)}; its bindings were copied
 * into the union bucket at that moment. {@link #query()} hands out another copy, so the stored
 * query never changes and a {@code Union} is effectively immutable.
 */
public final class Union {
    private final Builder query;
    private final boolean all;

    /**
     * Creates the entry.
     *
     * @param query the query; owned by this entry from now on
     * @param all {@code true} for {@code union all}
     */
    Union(Builder query, boolean all) {
        this.query = query;
        this.all = all;
    }

    /**
     * Returns a copy of the query.
     *
     * @return an independent copy, never {@code null}
     */
    public Builder query() {
        return query.clone();
    }

    /**
     * Returns whether duplicates are kept.
     *
     * @return {@code true} for {@code union all}
     */
    public boolean isAll() {
        return all;
    }

    /** Returns the stored query, for the package's grammar, which never changes it. */
    Builder builder() {
        return query;
    }
}
