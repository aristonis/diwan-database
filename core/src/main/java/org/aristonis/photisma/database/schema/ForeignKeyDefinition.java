package org.aristonis.photisma.database.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A foreign key of a {@link Blueprint}, with Laravel's chained calls:
 * {@code table.foreign("user_id").references("id").on("users").cascadeOnDelete()}.
 *
 * <p>Like a column, a definition changes only inside the schema callback and is frozen once the
 * schema builder has checked the blueprint.
 */
public final class ForeignKeyDefinition {

    private final List<String> columns;
    private final String requestedName;
    private String name;
    private List<String> referencedColumns = Collections.emptyList();
    private String referencedTable;
    private ForeignKeyAction onDelete;
    private ForeignKeyAction onUpdate;
    private boolean isFrozen;

    ForeignKeyDefinition(List<String> columns, String name) {
        this.columns = checkColumns(columns);
        this.requestedName = name == null ? null : SchemaNames.check("foreign key", name);
    }

    /**
     * Sets the referenced columns.
     *
     * @param columns the column names of the referenced table, at least one, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, a name is invalid or repeated
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition references(String... columns) {
        ensureOpen();
        this.referencedColumns = checkColumns(Arrays.asList(Objects.requireNonNull(columns, "columns")));
        return this;
    }

    /**
     * Sets the referenced table; the connection's table prefix is added to it.
     *
     * @param table the table name without prefix, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition on(String table) {
        ensureOpen();
        this.referencedTable = SchemaNames.check("table", table);
        return this;
    }

    /**
     * Sets what happens to referencing rows when the referenced row is deleted.
     *
     * @param action the action, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code action} is {@code null}
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition onDelete(ForeignKeyAction action) {
        ensureOpen();
        this.onDelete = Objects.requireNonNull(action, "action");
        return this;
    }

    /**
     * Sets what happens to referencing rows when the referenced key changes.
     *
     * @param action the action, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code action} is {@code null}
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition onUpdate(ForeignKeyAction action) {
        ensureOpen();
        this.onUpdate = Objects.requireNonNull(action, "action");
        return this;
    }

    /**
     * Deletes referencing rows with the referenced row.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition cascadeOnDelete() {
        return onDelete(ForeignKeyAction.CASCADE);
    }

    /**
     * Refuses to delete a row that is still referenced.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition restrictOnDelete() {
        return onDelete(ForeignKeyAction.RESTRICT);
    }

    /**
     * Sets the referencing columns to SQL {@code NULL} when the referenced row is deleted.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition nullOnDelete() {
        return onDelete(ForeignKeyAction.SET_NULL);
    }

    /**
     * Refuses, at the end of the statement, to delete a row that is still referenced.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition noActionOnDelete() {
        return onDelete(ForeignKeyAction.NO_ACTION);
    }

    /**
     * Changes referencing columns with the referenced key.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition cascadeOnUpdate() {
        return onUpdate(ForeignKeyAction.CASCADE);
    }

    /**
     * Refuses to change a key that is still referenced.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition restrictOnUpdate() {
        return onUpdate(ForeignKeyAction.RESTRICT);
    }

    /**
     * Sets the referencing columns to SQL {@code NULL} when the referenced key changes.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition nullOnUpdate() {
        return onUpdate(ForeignKeyAction.SET_NULL);
    }

    /**
     * Refuses, at the end of the statement, to change a key that is still referenced.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ForeignKeyDefinition noActionOnUpdate() {
        return onUpdate(ForeignKeyAction.NO_ACTION);
    }

    /**
     * Returns the referencing columns.
     *
     * @return an unmodifiable list, never {@code null} or empty
     */
    public List<String> columns() {
        return columns;
    }

    /**
     * Returns the key's name: the one given, or Laravel's {@code <table>_<columns>_foreign}.
     *
     * @return the name, never {@code null} once the blueprint is checked
     * @throws IllegalStateException if the blueprint is not checked yet
     */
    public String name() {
        if (name == null) {
            throw new IllegalStateException("The foreign key name is set when the blueprint is checked");
        }
        return name;
    }

    /**
     * Returns the referenced columns.
     *
     * @return an unmodifiable list, never {@code null}; as many as {@link #columns()} once checked
     */
    public List<String> referencedColumns() {
        return referencedColumns;
    }

    /**
     * Returns the referenced table, with the connection's prefix once the blueprint is checked.
     *
     * @return the table name, never {@code null} once checked
     */
    public String referencedTable() {
        return referencedTable;
    }

    /**
     * Returns the delete action, if one was set.
     *
     * @return the action, or empty for the database's default
     */
    public Optional<ForeignKeyAction> deleteAction() {
        return Optional.ofNullable(onDelete);
    }

    /**
     * Returns the update action, if one was set.
     *
     * @return the action, or empty for the database's default
     */
    public Optional<ForeignKeyAction> updateAction() {
        return Optional.ofNullable(onUpdate);
    }

    /**
     * Checks the definition, settles its name and prefix and freezes it.
     *
     * @param defaultName the name to use when none was given
     * @param prefix the connection's table prefix
     * @throws IllegalArgumentException if the definition is incomplete
     */
    void freeze(String defaultName, String prefix) {
        if (isFrozen) {
            return;
        }
        if (referencedTable == null) {
            throw new IllegalArgumentException("Foreign key on " + columns + " needs on(table)");
        }
        if (referencedColumns.size() != columns.size()) {
            throw new IllegalArgumentException("Foreign key on " + columns + " references "
                    + referencedColumns.size() + " columns; it needs " + columns.size());
        }
        this.name = requestedName != null ? requestedName : defaultName;
        this.referencedTable = SchemaNames.check("table", prefix + referencedTable);
        isFrozen = true;
    }

    String requestedName() {
        return requestedName;
    }

    private void ensureOpen() {
        if (isFrozen) {
            throw new IllegalStateException("The foreign key on " + columns + " can no longer be changed");
        }
    }

    static List<String> checkColumns(List<String> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("At least one column is needed");
        }
        Set<String> seen = new HashSet<>();
        List<String> checked = new ArrayList<>(columns.size());
        for (String column : columns) {
            SchemaNames.check("column", column);
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The column " + column + " is listed twice");
            }
            checked.add(column);
        }
        return Collections.unmodifiableList(checked);
    }
}
