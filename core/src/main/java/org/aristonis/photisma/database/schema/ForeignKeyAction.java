package org.aristonis.photisma.database.schema;

/**
 * What the database does to referencing rows when the referenced row is deleted or its key changes.
 */
public enum ForeignKeyAction {

    /** Deletes or updates the referencing rows too. */
    CASCADE("cascade"),
    /** Refuses the change while referencing rows exist, checked at once. */
    RESTRICT("restrict"),
    /** Sets the referencing columns to SQL {@code NULL}. */
    SET_NULL("set null"),
    /** Sets the referencing columns to their default. */
    SET_DEFAULT("set default"),
    /** Refuses the change while referencing rows exist, checked at the end of the statement. */
    NO_ACTION("no action");

    private final String sql;

    ForeignKeyAction(String sql) {
        this.sql = sql;
    }

    /**
     * Returns the action as SQL.
     *
     * @return the SQL words, lower case, never {@code null}
     */
    public String sql() {
        return sql;
    }
}
