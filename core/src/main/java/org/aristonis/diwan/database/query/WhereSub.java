package org.aristonis.diwan.database.query;

/**
 * A column compared with the single value a subquery returns: {@code "a" = (select ...)}.
 *
 * <p>The subquery is a private copy taken when the condition was added, and its bindings were
 * copied into the where bucket at that moment, so the SQL and the bindings never drift apart.
 * {@link #query()} hands out another copy; the stored one is never changed.
 */
public final class WhereSub implements Where {
    private final String conjunction;
    private final String column;
    private final String operator;
    private final Builder query;

    /**
     * Creates the condition.
     *
     * @param conjunction the joining word
     * @param column the column name
     * @param operator the operator, as the allowlist spells it
     * @param query the subquery; owned by this condition from now on
     */
    public WhereSub(String conjunction, String column, String operator, Builder query) {
        this.conjunction = conjunction;
        this.column = column;
        this.operator = operator;
        this.query = query;
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
     * Returns the operator.
     *
     * @return the operator
     */
    public String operator() {
        return operator;
    }

    /**
     * Returns a copy of the subquery.
     *
     * @return an independent copy, never {@code null}
     */
    public Builder query() {
        return query.clone();
    }

    /** Returns the stored subquery, for the package's grammar, which never changes it. */
    Builder builder() {
        return query;
    }
}
