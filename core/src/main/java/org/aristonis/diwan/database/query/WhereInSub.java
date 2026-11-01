package org.aristonis.diwan.database.query;

/**
 * A column in, or not in, the rows a subquery returns: {@code "a" in (select ...)}.
 *
 * <p>The subquery is a private copy taken when the condition was added; see {@link WhereSub}.
 */
public final class WhereInSub implements Where {
    private final String conjunction;
    private final String column;
    private final Builder query;
    private final boolean not;

    /**
     * Creates the condition.
     *
     * @param conjunction the joining word
     * @param column the column name
     * @param query the subquery; owned by this condition from now on
     * @param not {@code true} for {@code not in}
     */
    public WhereInSub(String conjunction, String column, Builder query, boolean not) {
        this.conjunction = conjunction;
        this.column = column;
        this.query = query;
        this.not = not;
    }

    /** {@inheritDoc} */
    @Override
    public String conjunction() {
        return conjunction;
    }

    /**
     * Returns the column name.
     *
     * @return the column name
     */
    public String column() {
        return column;
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
     * @return {@code true} for {@code not in}
     */
    public boolean not() {
        return not;
    }

    /** Returns the stored subquery, for the package's grammar, which never changes it. */
    Builder builder() {
        return query;
    }
}
