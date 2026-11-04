package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.aristonis.diwan.database.query.Builder;

/**
 * One recorded pivot-table condition, a {@code wherePivot*} or {@code withPivotValue} call, kept
 * so that it can be replayed in the same order on any query.
 *
 * <p>Instances are immutable. {@link #applyTo} calls the core method for the recorded kind and
 * nothing else, so the core's own rules still apply: an empty {@code in} list matches no row,
 * and a {@code between} list that is not exactly two values throws.
 */
final class PivotClause {

    /** The core method family that a clause applies. */
    enum Kind { BASIC, IN, BETWEEN, NULL }

    private final Kind kind;
    private final String column;
    private final String operator;
    private final List<Object> values;
    private final boolean isOr;
    private final boolean isNot;

    private PivotClause(Kind kind, String column, String operator, List<Object> values, boolean isOr,
            boolean isNot) {
        this.kind = kind;
        this.column = column;
        this.operator = operator;
        this.values = values;
        this.isOr = isOr;
        this.isNot = isNot;
    }

    /**
     * Returns a clause for a column comparison, with an operator or without one.
     *
     * @param column the column, already qualified, never {@code null}
     * @param operator the operator, or {@code null} for the two-argument form
     * @param value the value, may be {@code null}
     * @param isOr whether the condition joins with {@code or}
     * @return the clause, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     */
    static PivotClause basic(String column, String operator, Object value, boolean isOr) {
        return new PivotClause(Kind.BASIC, requireColumn(column), operator,
                copyOf(Collections.singletonList(value)), isOr, false);
    }

    /**
     * Returns a clause for a column in a list, or not in a list.
     *
     * @param column the column, already qualified, never {@code null}
     * @param values the values, never {@code null}; copied, so later changes to it are not seen
     * @param isOr whether the condition joins with {@code or}
     * @param isNot whether the condition is {@code not in}
     * @return the clause, never {@code null}
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     */
    static PivotClause in(String column, Collection<?> values, boolean isOr, boolean isNot) {
        return new PivotClause(Kind.IN, requireColumn(column), null,
                copyOf(Objects.requireNonNull(values, "values")), isOr, isNot);
    }

    /**
     * Returns a clause for a column between two values, or not between them.
     *
     * @param column the column, already qualified, never {@code null}
     * @param values the low and high value, never {@code null}; copied, and checked by the core
     *     when applied, not here
     * @param isOr whether the condition joins with {@code or}
     * @param isNot whether the condition is {@code not between}
     * @return the clause, never {@code null}
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     */
    static PivotClause between(String column, List<?> values, boolean isOr, boolean isNot) {
        return new PivotClause(Kind.BETWEEN, requireColumn(column), null,
                copyOf(Objects.requireNonNull(values, "values")), isOr, isNot);
    }

    /**
     * Returns a clause for a column that is null, or not null.
     *
     * @param column the column, already qualified, never {@code null}
     * @param isOr whether the condition joins with {@code or}
     * @param isNot whether the condition is {@code is not null}
     * @return the clause, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     */
    static PivotClause nullCheck(String column, boolean isOr, boolean isNot) {
        return new PivotClause(Kind.NULL, requireColumn(column), null, Collections.<Object>emptyList(), isOr,
                isNot);
    }

    /**
     * Adds this condition to a query by calling the core method for its kind.
     *
     * @param query the query that receives the condition, never {@code null}
     * @throws NullPointerException if {@code query} is {@code null}
     * @throws IllegalArgumentException if the clause is a between and the core rejects its list
     */
    void applyTo(Builder query) {
        Objects.requireNonNull(query, "query");
        switch (kind) {
            case BASIC:
                applyBasic(query);
                break;
            case IN:
                applyIn(query);
                break;
            case BETWEEN:
                applyBetween(query);
                break;
            case NULL:
                applyNull(query);
                break;
            default:
                throw new IllegalStateException("unknown pivot clause kind " + kind);
        }
    }

    /**
     * Returns the core method family of this clause.
     *
     * @return the kind, never {@code null}
     */
    Kind kind() {
        return kind;
    }

    /**
     * Returns the qualified column of this clause.
     *
     * @return the column, never {@code null}
     */
    String column() {
        return column;
    }

    /**
     * Returns the operator of a basic clause.
     *
     * @return the operator, or {@code null} for the two-argument form and for the other kinds
     */
    String operator() {
        return operator;
    }

    /**
     * Returns the recorded values in the order they were given.
     *
     * @return an unmodifiable list, never {@code null}; the single value of a basic clause, the
     *     list of an in or between clause, and empty for a null check
     */
    List<Object> values() {
        return values;
    }

    /**
     * Returns whether this clause joins the query with {@code or}.
     *
     * @return {@code true} for an {@code or} clause
     */
    boolean isOr() {
        return isOr;
    }

    /**
     * Returns whether this clause negates its condition.
     *
     * @return {@code true} for a {@code not} clause
     */
    boolean isNot() {
        return isNot;
    }

    private void applyBasic(Builder query) {
        Object value = values.get(0);
        if (operator == null) {
            if (isOr) {
                query.orWhere(column, value);
            } else {
                query.where(column, value);
            }
            return;
        }
        if (isOr) {
            query.orWhere(column, operator, value);
        } else {
            query.where(column, operator, value);
        }
    }

    private void applyIn(Builder query) {
        if (isNot) {
            if (isOr) {
                query.orWhereNotIn(column, values);
            } else {
                query.whereNotIn(column, values);
            }
        } else if (isOr) {
            query.orWhereIn(column, values);
        } else {
            query.whereIn(column, values);
        }
    }

    private void applyBetween(Builder query) {
        if (isNot) {
            if (isOr) {
                query.orWhereNotBetween(column, values);
            } else {
                query.whereNotBetween(column, values);
            }
        } else if (isOr) {
            query.orWhereBetween(column, values);
        } else {
            query.whereBetween(column, values);
        }
    }

    private void applyNull(Builder query) {
        if (isNot) {
            if (isOr) {
                query.orWhereNotNull(column);
            } else {
                query.whereNotNull(column);
            }
        } else if (isOr) {
            query.orWhereNull(column);
        } else {
            query.whereNull(column);
        }
    }

    private static String requireColumn(String column) {
        return Objects.requireNonNull(column, "column");
    }

    /**
     * Copies the source into a new unmodifiable list.
     *
     * <p>The copy is taken at recording time, so a caller that keeps and later edits its own list
     * cannot change a condition that has already been recorded.
     */
    private static List<Object> copyOf(Collection<?> source) {
        return Collections.unmodifiableList(new ArrayList<Object>(source));
    }
}
