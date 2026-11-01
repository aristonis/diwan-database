package org.aristonis.diwan.database.query;

/**
 * The single value a subquery returns, compared with a value: {@code (select ...) > ?}.
 *
 * <p>Laravel compiles the subquery to an expression and stores a basic condition on it; here the
 * subquery is kept, as a private copy taken when the condition was added, so a database grammar
 * compiles it with its own rules. Its bindings come first in the where bucket, then the value's.
 */
public final class WhereSubValue implements Where {
    private final String conjunction;
    private final Builder query;
    private final String operator;
    private final Object value;

    /**
     * Creates the condition.
     *
     * @param conjunction the joining word
     * @param query the subquery; owned by this condition from now on
     * @param operator the operator, as the allowlist spells it, or {@code is} / {@code is not}
     *     for a {@code null} value
     * @param value the value, an {@link Expression} or a bindable value
     */
    public WhereSubValue(String conjunction, Builder query, String operator, Object value) {
        this.conjunction = conjunction;
        this.query = query;
        this.operator = operator;
        this.value = value;
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
     * Returns the operator.
     *
     * @return the operator
     */
    public String operator() {
        return operator;
    }

    /**
     * Returns the value.
     *
     * @return the value, an {@link Expression} or a bindable value
     */
    public Object value() {
        return value;
    }

    /** Returns the stored subquery, for the package's grammar, which never changes it. */
    Builder builder() {
        return query;
    }
}
