package org.aristonis.diwan.database.driver;

/**
 * Classifies a {@link DriverException} so the core can react without knowing the database.
 *
 * <p>Only the driver can decide the kind, because only it knows its database's SQL states and
 * vendor codes.
 *
 * <p>Constants may be added in later versions. A driver reports only the constants it knows, and
 * callers must tolerate constants they do not know.
 */
public enum DriverFailureKind {

    /** Marks a failure caused by a unique or primary key constraint violation. */
    UNIQUE_VIOLATION,

    /**
     * Marks a deadlock, a serialization failure, or a busy or locked database.
     *
     * <p>A failure of this kind raised by a statement or savepoint command on the transaction's own
     * session is retryable at the outermost transaction. A failure raised by
     * {@link DriverSession#commit()} is never retried, whatever its kind, because the commit may
     * have landed; a driver must not report a connection lost during a commit as this kind.
     */
    CONCURRENCY
}
