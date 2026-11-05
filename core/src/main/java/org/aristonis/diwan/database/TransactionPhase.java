package org.aristonis.diwan.database;

/** The four moments of a transaction level that {@link ConnectionEvents#transaction} reports. */
enum TransactionPhase {
    /** After BEGIN or SAVEPOINT succeeded. */
    BEGINNING,
    /** Before COMMIT or RELEASE. */
    COMMITTING,
    /** After COMMIT or RELEASE succeeded. */
    COMMITTED,
    /** After ROLLBACK or ROLLBACK TO. */
    ROLLED_BACK
}
