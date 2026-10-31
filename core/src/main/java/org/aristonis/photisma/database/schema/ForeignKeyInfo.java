package org.aristonis.photisma.database.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One foreign key of a table as the database reports it, from {@link Schema#foreignKeys(String)}.
 *
 * <p>Actions are the database's lower-case words, such as {@code cascade} or {@code no action}.
 * Instances are immutable.
 */
public final class ForeignKeyInfo {

    private final List<String> columns;
    private final String foreignTable;
    private final List<String> foreignColumns;
    private final String onUpdate;
    private final String onDelete;

    /**
     * Creates the description.
     *
     * @param columns the referencing columns in order, never {@code null}; copied
     * @param foreignTable the referenced table, never {@code null}
     * @param foreignColumns the referenced columns in order, never {@code null}; copied
     * @param onUpdate the update action, never {@code null}
     * @param onDelete the delete action, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public ForeignKeyInfo(List<String> columns, String foreignTable, List<String> foreignColumns, String onUpdate,
            String onDelete) {
        this.columns = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(columns, "columns")));
        this.foreignTable = Objects.requireNonNull(foreignTable, "foreignTable");
        this.foreignColumns = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(foreignColumns, "foreignColumns")));
        this.onUpdate = Objects.requireNonNull(onUpdate, "onUpdate");
        this.onDelete = Objects.requireNonNull(onDelete, "onDelete");
    }

    /**
     * Returns the referencing columns.
     *
     * @return an unmodifiable list, never {@code null}
     */
    public List<String> columns() {
        return columns;
    }

    /**
     * Returns the referenced table.
     *
     * @return the table name, never {@code null}
     */
    public String foreignTable() {
        return foreignTable;
    }

    /**
     * Returns the referenced columns.
     *
     * @return an unmodifiable list, never {@code null}
     */
    public List<String> foreignColumns() {
        return foreignColumns;
    }

    /**
     * Returns the update action.
     *
     * @return the action words, lower case, never {@code null}
     */
    public String onUpdate() {
        return onUpdate;
    }

    /**
     * Returns the delete action.
     *
     * @return the action words, lower case, never {@code null}
     */
    public String onDelete() {
        return onDelete;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ForeignKeyInfo)) {
            return false;
        }
        ForeignKeyInfo that = (ForeignKeyInfo) other;
        return columns.equals(that.columns) && foreignTable.equals(that.foreignTable)
                && foreignColumns.equals(that.foreignColumns) && onUpdate.equals(that.onUpdate)
                && onDelete.equals(that.onDelete);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns, foreignTable, foreignColumns, onUpdate, onDelete);
    }

    @Override
    public String toString() {
        return "ForeignKeyInfo[" + columns + " -> " + foreignTable + foreignColumns + ", on update " + onUpdate
                + ", on delete " + onDelete + "]";
    }
}
