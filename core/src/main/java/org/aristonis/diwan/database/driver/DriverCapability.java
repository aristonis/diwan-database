package org.aristonis.diwan.database.driver;

/**
 * Names an optional feature a driver's database supports, as reported by {@link DriverInfo}.
 *
 * <p>Constants may be added in later versions. A driver reports only the constants it knows, and
 * callers must tolerate constants they do not know.
 */
public enum DriverCapability {

    /** Indicates the database can release a savepoint without ending the transaction. */
    SAVEPOINT_RELEASE,

    /** Indicates schema changes (DDL) take part in transactions and roll back with them. */
    TRANSACTIONAL_DDL,

    /**
     * Indicates a cursor streams only inside a transaction, as PostgreSQL's JDBC driver fetches
     * rows in batches only with auto-commit off. A session that reports it opens a transaction of
     * its own for a cursor read outside any transaction, and ends it when the cursor closes.
     */
    STREAMS_IN_TRANSACTION
}
