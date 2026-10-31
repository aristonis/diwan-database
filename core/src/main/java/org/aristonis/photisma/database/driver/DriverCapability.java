package org.aristonis.photisma.database.driver;

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
    TRANSACTIONAL_DDL
}
