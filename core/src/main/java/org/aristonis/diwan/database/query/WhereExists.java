package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Internal;

/**
 * A subquery that returns at least one row, or none: {@code exists (select ...)}.
 *
 * <p>The subquery is a private copy taken when the condition was added; see {@link WhereSub}.
 *
 * @apiNote Internal: read by dialect grammars; may change without notice.
 */
@Internal
public final class WhereExists implements Where {
    private final String conjunction;
    private final Builder query;
    private final boolean not;

    /**
     * Creates the condition.
     *
     * @param conjunction the joining word
     * @param query the subquery; owned by this condition from now on
     * @param not {@code true} for {@code not exists}
     */
    public WhereExists(String conjunction, Builder query, boolean not) {
        this.conjunction = conjunction;
        this.query = query;
        this.not = not;
    }

    /** {@inheritDoc} */
    @Override
    public String conjunction() {
        return conjunction;
    }

    /**
     * Returns a copy of the subquery.
     *
     * @return an independent copy, never {@code null}
     */
    public Builder query() {
        return query.clone();
    }

    /**
     * Returns whether the condition is negated.
     *
     * @return {@code true} for {@code not exists}
     */
    public boolean not() {
        return not;
    }

    /** Returns the stored subquery, for the package's grammar, which never changes it. */
    Builder builder() {
        return query;
    }
}
