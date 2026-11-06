package org.aristonis.diwan.database.exception;

/**
 * Thrown when rows break a foreign key after a schema change that the database could not check
 * as it ran, such as a SQLite table rebuild. The change is undone before this is thrown.
 *
 * <p>The message names the tables and the number of rows, never row values.
 */
public class ForeignKeyCheckException extends DatabaseException {

    private static final long serialVersionUID = 1L;

    /** The table holding the bad rows. */
    private final String table;
    /** The table the bad rows should point at. */
    private final String referencedTable;
    /** How many rows break a key. */
    private final int rowCount;

    /**
     * Creates the exception.
     *
     * @param table the table holding the bad rows, never {@code null}
     * @param referencedTable the table they should point at, never {@code null}
     * @param rowCount how many rows break a key, at least 1
     */
    public ForeignKeyCheckException(String table, String referencedTable, int rowCount) {
        super(rowCount + (rowCount == 1 ? " row" : " rows") + " of table " + table
                + " point at missing rows of table " + referencedTable + "; the schema change was undone");
        this.table = table;
        this.referencedTable = referencedTable;
        this.rowCount = rowCount;
    }

    /**
     * Returns the table holding the bad rows.
     *
     * @return the table name, never {@code null}
     */
    public String table() {
        return table;
    }

    /**
     * Returns the table the bad rows should point at.
     *
     * @return the table name, never {@code null}
     */
    public String referencedTable() {
        return referencedTable;
    }

    /**
     * Returns how many rows break a key.
     *
     * @return the count, at least 1
     */
    public int rowCount() {
        return rowCount;
    }
}
