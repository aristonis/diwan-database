package org.aristonis.diwan.database.query;

import java.util.List;

/**
 * One condition of a having clause, as the {@link Builder} records it and a {@link QueryGrammar}
 * compiles it.
 *
 * <p>Conjunctions, values and the choice of final classes over records follow the same rules as
 * {@link Where}.
 */
public interface Having {

    /**
     * Returns the word that joins this condition to the one before it.
     *
     * @return {@code and} or {@code or}, never {@code null}
     */
    String conjunction();

    /**
     * A column compared with a value.
     */
    final class Basic implements Having {
        private final String conjunction;
        private final String column;
        private final String operator;
        private final Object value;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name or alias
         * @param operator the operator, as the allowlist spells it
         * @param value the value, an {@link Expression} or a bindable value, may be {@code null}
         */
        public Basic(String conjunction, String column, String operator, Object value) {
            this.conjunction = conjunction;
            this.column = column;
            this.operator = operator;
            this.value = value;
        }

        /**
         * Returns the joining word.
         *
         * @return the joining word
         */
        @Override
        public String conjunction() {
            return conjunction;
        }

        /**
         * Returns the column name or alias.
         *
         * @return the column name or alias
         */
        public String column() {
            return column;
        }

        /**
         * Returns the operator, as the allowlist spells it.
         *
         * @return the operator, as the allowlist spells it
         */
        public String operator() {
            return operator;
        }

        /**
         * Returns the value, an {@link Expression} or a bindable value, may be {@code null}.
         *
         * @return the value, an {@link Expression} or a bindable value, may be {@code null}
         */
        public Object value() {
            return value;
        }
    }

    /**
     * Trusted SQL written as given.
     */
    final class Raw implements Having {
        private final String conjunction;
        private final String sql;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param sql the SQL
         */
        public Raw(String conjunction, String sql) {
            this.conjunction = conjunction;
            this.sql = sql;
        }

        /**
         * Returns the joining word.
         *
         * @return the joining word
         */
        @Override
        public String conjunction() {
            return conjunction;
        }

        /**
         * Returns the SQL.
         *
         * @return the SQL
         */
        public String sql() {
            return sql;
        }
    }

    /**
     * A column that is null.
     */
    final class Null implements Having {
        private final String conjunction;
        private final String column;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         */
        public Null(String conjunction, String column) {
            this.conjunction = conjunction;
            this.column = column;
        }

        /**
         * Returns the joining word.
         *
         * @return the joining word
         */
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
    }

    /**
     * A column that is not null.
     */
    final class NotNull implements Having {
        private final String conjunction;
        private final String column;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         */
        public NotNull(String conjunction, String column) {
            this.conjunction = conjunction;
            this.column = column;
        }

        /**
         * Returns the joining word.
         *
         * @return the joining word
         */
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
    }

    /**
     * A column between two values.
     */
    final class Between implements Having {
        private final String conjunction;
        private final String column;
        private final List<Object> values;
        private final boolean not;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         * @param values the low and high value, unmodifiable, exactly two
         * @param not whether the condition is negated
         */
        public Between(String conjunction, String column, List<Object> values, boolean not) {
            this.conjunction = conjunction;
            this.column = column;
            this.values = Clauses.copy(values);
            this.not = not;
        }

        /**
         * Returns the joining word.
         *
         * @return the joining word
         */
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
         * Returns the low and high value, unmodifiable, exactly two.
         *
         * @return the low and high value, unmodifiable, exactly two
         */
        public List<Object> values() {
            return values;
        }

        /**
         * Returns whether the condition is negated.
         *
         * @return whether the condition is negated
         */
        public boolean not() {
            return not;
        }
    }

    /**
     * A group of having conditions in parentheses.
     */
    final class Nested implements Having {
        private final String conjunction;
        private final Builder query;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param query the builder holding the group's conditions; owned by this condition
         */
        public Nested(String conjunction, Builder query) {
            this.conjunction = conjunction;
            this.query = query;
        }

        /**
         * Returns the joining word.
         *
         * @return the joining word
         */
        @Override
        public String conjunction() {
            return conjunction;
        }

        /**
         * Returns a copy of the group's builder. The own builder is never handed out: the parent
         * copied the group's bindings when the group was added, so changing the group afterwards
         * would put the SQL and the bindings out of step.
         *
         * @return an independent copy, never {@code null}
         */
        public Builder query() {
            return query.clone();
        }

        /** Returns the own builder, for the package's grammar and copy code, which never change it. */
        Builder builder() {
            return query;
        }
    }
}
