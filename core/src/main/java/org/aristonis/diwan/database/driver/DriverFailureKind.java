package org.aristonis.diwan.database.driver;

import org.aristonis.diwan.database.exception.DriverException;

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
     * Marks a deadlock, a serialization failure, a busy or locked database, or a lock that was not
     * available in time (PostgreSQL {@code lock_timeout} or {@code NOWAIT}), so each retry may wait
     * that timeout again.
     *
     * <p>A failure of this kind raised by a statement or savepoint command on the transaction's own
     * session is retryable at the outermost transaction. A failure raised by
     * {@link DriverSession#commit()} is never retried, whatever its kind, because the commit may
     * have landed; a driver must not report a connection lost during a commit as this kind.
     */
    CONCURRENCY,

    /** Marks a failure caused by a foreign key constraint violation. */
    FOREIGN_KEY_VIOLATION,

    /** Marks a statement the database cancelled, such as one that ran past a statement timeout. */
    CANCELLED,

    /**
     * Marks a failure that ended the connection: a network failure, or the server shutting down.
     *
     * <p>Never retried, and the session that saw it is dropped rather than handed back. It says
     * nothing about whether a commit in flight landed: after a lost connection during a commit,
     * check the data before repeating the work.
     */
    CONNECTION_LOST,

    /**
     * Marks a commit whose connection was lost before the database answered, so the work may or may
     * not have been committed.
     *
     * <p>Never retried by the library. Check the data before repeating the work; repeating it blindly
     * can apply it twice.
     */
    COMMIT_OUTCOME_UNKNOWN,

    /** Marks a failure caused by a check constraint violation. */
    CHECK_VIOLATION,

    /** Marks a failure caused by a {@code NOT NULL} constraint violation. */
    NOT_NULL_VIOLATION
}
