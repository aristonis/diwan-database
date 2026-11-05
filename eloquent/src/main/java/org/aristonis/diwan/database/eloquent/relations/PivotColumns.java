package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The name rules for the pivot columns of a belongsToMany relation: the checks on a column's name
 * and value, the select aliases each column is read under, and the creation and update time
 * columns.
 *
 * <p>The helpers are static and hold no state. The relation's description is passed in as text, so
 * a message names the relation the caller holds. They live apart from {@link PivotClauses} so that
 * class stays within its size budget.
 */
final class PivotColumns {

    /** The prefix of the select alias that carries each pivot column in a joined row. */
    private static final String PIVOT_ALIAS_PREFIX = "pivot_";

    private static final String NULL_VALUE_MESSAGE = "The provided value may not be null";

    private PivotColumns() {
    }

    /**
     * Refuses a pivot column whose name contains a dot, since each column is read under an alias
     * built from its name.
     *
     * @param column the column as the caller wrote it, never {@code null}
     * @param relation the description of the relation for the message, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code column} contains a dot
     */
    static void requireUndotted(String column, String relation) {
        Objects.requireNonNull(column, "column");
        if (column.indexOf('.') >= 0) {
            throw new IllegalArgumentException("The pivot column [" + column + "] of " + relation
                    + " may not contain a dot; name the column without its table");
        }
    }

    /**
     * Refuses a {@code null} value for a pivot column that is filtered on.
     *
     * @param column the pivot column, never {@code null}
     * @param value the value to check, {@code null} is refused
     * @param relation the description of the relation for the message, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    static void requireValue(String column, Object value, String relation) {
        Objects.requireNonNull(column, "column");
        if (value == null) {
            throw new IllegalArgumentException(NULL_VALUE_MESSAGE + " for pivot column [" + column
                    + "] of " + relation);
        }
    }

    /**
     * Builds the select entries that carry the pivot columns: the two keys first, then each extra
     * column, each aliased with the pivot prefix and listed once. A column written with the pivot table as a
     * qualifier counts as its bare name.
     *
     * @param table the pivot table, never {@code null}
     * @param foreignKey the pivot column that holds the parent's key, never {@code null}
     * @param relatedKey the pivot column that holds the related key, never {@code null}
     * @param columns the extra pivot columns in the order they were added, never {@code null}
     * @return the entries, never {@code null}, never empty
     */
    static String[] aliased(String table, String foreignKey, String relatedKey, List<String> columns) {
        Set<String> names = new LinkedHashSet<>();
        names.add(foreignKey);
        names.add(relatedKey);
        String prefix = table + ".";
        for (String column : columns) {
            names.add(column.startsWith(prefix) ? column.substring(prefix.length()) : column);
        }
        String[] aliased = new String[names.size()];
        int index = 0;
        for (String name : names) {
            aliased[index] = table + "." + name + " as " + PIVOT_ALIAS_PREFIX + name;
            index++;
        }
        return aliased;
    }

    /**
     * Resolves one timestamp name: {@code null} takes the parent's column, an empty name turns the
     * column off, and anything else is the pivot column.
     *
     * @param requested the caller's name, {@code null} for the parent's column, empty for none
     * @param parentColumn the parent's column of the same kind, {@code null} when the parent keeps none
     * @return the pivot column, or {@code null} when it is off
     */
    static String timestampColumn(String requested, String parentColumn) {
        if (requested == null) {
            return parentColumn;
        }
        return requested.isEmpty() ? null : requested;
    }

    /**
     * Lists the timestamp columns that are on, the creation column first.
     *
     * @param first the creation column, {@code null} when it is off
     * @param second the update column, {@code null} when it is off
     * @return the columns that are on, never {@code null}, possibly empty
     */
    static String[] enabledColumns(String first, String second) {
        List<String> enabled = new ArrayList<>(2);
        if (first != null) {
            enabled.add(first);
        }
        if (second != null) {
            enabled.add(second);
        }
        return enabled.toArray(new String[0]);
    }
}
