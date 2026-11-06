package org.aristonis.diwan.database.query;

import java.util.List;
import java.util.Locale;
import org.aristonis.diwan.database.Internal;

/**
 * One condition of a where clause, as the {@link Builder} records it and a {@link QueryGrammar}
 * compiles it.
 *
 * <p>Laravel keeps each condition as an untyped map with a {@code type} key and dispatches on the
 * string; here each type is a class, so a grammar reads typed fields. The types are plain final
 * classes, not records or a sealed hierarchy, because the library runs on Android API 28, which
 * has neither; a grammar that meets a type it does not know fails with
 * {@link IllegalStateException}.
 *
 * <p>Every condition carries its conjunction: {@code and}, {@code or}, {@code and not} or
 * {@code or not}. Values are the caller's values before binding; an {@link Expression} value is
 * written into the SQL instead of bound. List fields are unmodifiable copies and may hold
 * {@code null}. A {@link Nested} condition returns a copy of its builder, so every condition is
 * effectively immutable.
 *
 * <p>Conditions that hold a subquery are top-level classes in this package: {@link WhereSub},
 * {@link WhereSubValue}, {@link WhereInSub}, {@link WhereExists}, plus {@link WhereRowValues}.
 *
 * @apiNote Internal: read by dialect grammars; may change without notice.
 */
@Internal
public interface Where {

    /**
     * Returns the word that joins this condition to the one before it.
     *
     * @return {@code and}, {@code or}, {@code and not} or {@code or not}, never {@code null}
     */
    String conjunction();

    /**
     * A column compared with a value: {@code "a" > ?}.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class Basic implements Where {
        private final String conjunction;
        private final String column;
        private final String operator;
        private final Object value;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         * @param operator the operator, as the allowlist spells it
         * @param value the value, an {@link Expression} or a bindable value
         */
        public Basic(String conjunction, String column, String operator, Object value) {
            this.conjunction = conjunction;
            this.column = column;
            this.operator = operator;
            this.value = value;
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
         * Returns the operator, as the allowlist spells it.
         *
         * @return the operator, as the allowlist spells it
         */
        public String operator() {
            return operator;
        }

        /**
         * Returns the value, an {@link Expression} or a bindable value.
         *
         * @return the value, an {@link Expression} or a bindable value
         */
        public Object value() {
            return value;
        }
    }

    /**
     * Trusted SQL written as given.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class Raw implements Where {
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

        /** {@inheritDoc} */
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
     * A {@code like} or {@code not like} match.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class Like implements Where {
        private final String conjunction;
        private final String column;
        private final Object value;
        private final boolean caseSensitive;
        private final boolean not;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         * @param value the pattern
         * @param caseSensitive whether the match must respect case
         * @param not whether the condition is negated
         */
        public Like(String conjunction, String column, Object value, boolean caseSensitive, boolean not) {
            this.conjunction = conjunction;
            this.column = column;
            this.value = value;
            this.caseSensitive = caseSensitive;
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
         * Returns the pattern.
         *
         * @return the pattern
         */
        public Object value() {
            return value;
        }

        /**
         * Returns whether the match must respect case.
         *
         * @return whether the match must respect case
         */
        public boolean caseSensitive() {
            return caseSensitive;
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
     * A comparison that treats two nulls as equal.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class NullSafeEquals implements Where {
        private final String conjunction;
        private final String column;
        private final Object value;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         * @param value the value, may be {@code null}
         */
        public NullSafeEquals(String conjunction, String column, Object value) {
            this.conjunction = conjunction;
            this.column = column;
            this.value = value;
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
         * Returns the value, may be {@code null}.
         *
         * @return the value, may be {@code null}
         */
        public Object value() {
            return value;
        }
    }

    /**
     * A column in a list of values; an empty list never matches.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class In implements Where {
        private final String conjunction;
        private final String column;
        private final List<Object> values;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         * @param values the values, unmodifiable, may hold {@code null}
         */
        public In(String conjunction, String column, List<Object> values) {
            this.conjunction = conjunction;
            this.column = column;
            this.values = Clauses.copy(values);
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
         * Returns the values, unmodifiable, may hold {@code null}.
         *
         * @return the values, unmodifiable, may hold {@code null}
         */
        public List<Object> values() {
            return values;
        }
    }

    /**
     * A column not in a list of values; an empty list always matches.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class NotIn implements Where {
        private final String conjunction;
        private final String column;
        private final List<Object> values;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         * @param values the values, unmodifiable, may hold {@code null}
         */
        public NotIn(String conjunction, String column, List<Object> values) {
            this.conjunction = conjunction;
            this.column = column;
            this.values = Clauses.copy(values);
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
         * Returns the values, unmodifiable, may hold {@code null}.
         *
         * @return the values, unmodifiable, may hold {@code null}
         */
        public List<Object> values() {
            return values;
        }
    }

    /**
     * A column that is null.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class Null implements Where {
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
    }

    /**
     * A column that is not null.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class NotNull implements Where {
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
    }

    /**
     * A column between two values, both ends included.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class Between implements Where {
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
     * A column between two other columns.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class BetweenColumns implements Where {
        private final String conjunction;
        private final String column;
        private final List<String> columns;
        private final boolean not;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param column the column name
         * @param columns the low and high column names, unmodifiable, exactly two
         * @param not whether the condition is negated
         */
        public BetweenColumns(String conjunction, String column, List<String> columns, boolean not) {
            this.conjunction = conjunction;
            this.column = column;
            this.columns = Clauses.copy(columns);
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
         * Returns the low and high column names, unmodifiable, exactly two.
         *
         * @return the low and high column names, unmodifiable, exactly two
         */
        public List<String> columns() {
            return columns;
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
     * A value between two columns.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class ValueBetween implements Where {
        private final String conjunction;
        private final Object value;
        private final List<String> columns;
        private final boolean not;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param value the value
         * @param columns the low and high column names, unmodifiable, exactly two
         * @param not whether the condition is negated
         */
        public ValueBetween(String conjunction, Object value, List<String> columns, boolean not) {
            this.conjunction = conjunction;
            this.value = value;
            this.columns = Clauses.copy(columns);
            this.not = not;
        }

        /** {@inheritDoc} */
        @Override
        public String conjunction() {
            return conjunction;
        }

        /**
         * Returns the value.
         *
         * @return the value
         */
        public Object value() {
            return value;
        }

        /**
         * Returns the low and high column names, unmodifiable, exactly two.
         *
         * @return the low and high column names, unmodifiable, exactly two
         */
        public List<String> columns() {
            return columns;
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
     * A part of a date or time column compared with a value: {@code date("a") = ?}.
     *
     * <p>These compare the stored text of {@code date} and {@code dateTime} columns. They do not
     * work on {@code instant} columns, which store a number; use
     * {@link Builder#whereInstantBetween} there.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class DateBased implements Where {
        private final String conjunction;
        private final DatePart part;
        private final String column;
        private final String operator;
        private final Object value;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param part which part of the column is compared
         * @param column the column name
         * @param operator the operator
         * @param value the value, already formatted as Laravel formats it
         */
        public DateBased(String conjunction, DatePart part, String column, String operator, Object value) {
            this.conjunction = conjunction;
            this.part = part;
            this.column = column;
            this.operator = operator;
            this.value = value;
        }

        /** {@inheritDoc} */
        @Override
        public String conjunction() {
            return conjunction;
        }

        /**
         * Returns which part of the column is compared.
         *
         * @return which part of the column is compared
         */
        public DatePart part() {
            return part;
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
         * Returns the value, already formatted as Laravel formats it.
         *
         * @return the value, already formatted as Laravel formats it
         */
        public Object value() {
            return value;
        }
    }

    /**
     * Two columns compared with each other.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class Column implements Where {
        private final String conjunction;
        private final String first;
        private final String operator;
        private final String second;

        /**
         * Creates the condition.
         *
         * @param conjunction the joining word
         * @param first the first column name
         * @param operator the operator
         * @param second the second column name
         */
        public Column(String conjunction, String first, String operator, String second) {
            this.conjunction = conjunction;
            this.first = first;
            this.operator = operator;
            this.second = second;
        }

        /** {@inheritDoc} */
        @Override
        public String conjunction() {
            return conjunction;
        }

        /**
         * Returns the first column name.
         *
         * @return the first column name
         */
        public String first() {
            return first;
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
         * Returns the second column name.
         *
         * @return the second column name
         */
        public String second() {
            return second;
        }
    }

    /**
     * A group of conditions in parentheses.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    final class Nested implements Where {
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

        /** {@inheritDoc} */
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

    /**
     * The part of a date or time a {@link DateBased} condition compares.
     *
     * @apiNote Internal: read by dialect grammars; may change without notice.
     */
    @Internal
    enum DatePart {
        /** The date. */
        DATE,
        /** The time of day. */
        TIME,
        /** The day of the month. */
        DAY,
        /** The month. */
        MONTH,
        /** The year. */
        YEAR;

        /**
         * Returns the name Laravel's base grammar uses as the SQL function.
         *
         * @return the lower-case name, never {@code null}
         */
        public String sqlName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
