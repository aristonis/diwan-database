package org.aristonis.diwan.database.query;

import java.util.List;
import org.aristonis.diwan.database.Internal;

/**
 * A row of columns compared with a row of values: {@code ("a", "b") < (?, ?)}.
 *
 * @apiNote Internal: read by dialect grammars; may change without notice.
 */
@Internal
public final class WhereRowValues implements Where {
    private final String conjunction;
    private final List<String> columns;
    private final String operator;
    private final List<Object> values;

    /**
     * Creates the condition.
     *
     * @param conjunction the joining word
     * @param columns the column names, as many as the values
     * @param operator the operator, as the allowlist spells it
     * @param values the values, each an {@link Expression} or a bindable value
     */
    public WhereRowValues(String conjunction, List<String> columns, String operator, List<?> values) {
        this.conjunction = conjunction;
        this.columns = Clauses.copy(columns);
        this.operator = operator;
        this.values = Clauses.copy(values);
    }

    /** {@inheritDoc} */
    @Override
    public String conjunction() {
        return conjunction;
    }

    /**
     * Returns the column names.
     *
     * @return the column names, unmodifiable
     */
    public List<String> columns() {
        return columns;
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
     * Returns the values.
     *
     * @return the values, unmodifiable, may contain {@code null}
     */
    public List<Object> values() {
        return values;
    }
}
