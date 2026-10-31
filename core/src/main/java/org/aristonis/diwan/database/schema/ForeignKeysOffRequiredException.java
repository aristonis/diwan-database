package org.aristonis.diwan.database.schema;

/**
 * Thrown when a schema change inside a transaction needs foreign key enforcement off, which the
 * database can only switch before the transaction starts.
 *
 * <p>On SQLite this is a rebuild of a table other tables point at: with enforcement on, dropping the
 * old table would apply their delete actions. The migrator catches it and runs the migration again
 * with enforcement off; other callers run the change through
 * {@link Schema#withoutForeignKeyConstraints(java.util.function.Consumer)}.
 */
public class ForeignKeysOffRequiredException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the explanation, never {@code null}
     */
    public ForeignKeysOffRequiredException(String message) {
        super(message);
    }
}
