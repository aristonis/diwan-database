package org.aristonis.diwan.database.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.OptionalInt;
import org.aristonis.diwan.database.driver.DatabaseFamily;
import org.aristonis.diwan.database.driver.LockWaitGuard;

/**
 * Applies a lock wait on a JDBC connection, the way one database does it.
 *
 * <p>The session picks one from the detected database so it can limit the wait for locks
 * of a transaction or a statement. An implementation cannot throw {@link java.sql.SQLException}
 * from its guard, so it wraps one in a {@link Failure}; the session translates it like any other
 * JDBC failure, so a lock timeout is reported as a concurrency failure.
 */
interface JdbcLockWait {

    /**
     * Prepares the wait for one transaction on a connection.
     *
     * @param connection the connection that runs the transaction, never {@code null}
     * @param millis the longest wait in milliseconds, more than zero
     * @return the guard that applies and removes the wait, never {@code null}
     */
    LockWaitGuard guard(Connection connection, long millis);

    /**
     * Prepares a limit on the wait for the locks of one statement on a connection. The caller
     * applies it with {@link LockWaitGuard#beforeBegin()}, runs the statement, and puts the previous
     * wait back with {@link LockWaitGuard#restore()}; nothing is applied before that.
     *
     * @param connection the connection that runs the statement, never {@code null}
     * @param millis the longest wait in milliseconds, more than zero
     * @return the guard of that one statement, never {@code null}
     * @throws UnsupportedOperationException if the database cannot limit the wait of one statement
     */
    LockWaitGuard statement(Connection connection, long millis);

    /**
     * Tells how many significant digits the database stores exactly in a decimal column.
     *
     * @return the number of exact significant digits, or empty when there is no known limit
     */
    OptionalInt exactDecimalDigits();

    /**
     * Chooses the strategy of a database.
     *
     * @param family the detected database family, never {@code null}
     * @return the strategy, or {@code null} for a database without one
     */
    static JdbcLockWait forFamily(DatabaseFamily family) {
        switch (family) {
            case SQLITE:
                return SqlitePragmaLockWait.INSTANCE;
            case POSTGRESQL:
                return PostgresSetLocalLockWait.INSTANCE;
            default:
                return null;
        }
    }

    /**
     * Carries the {@link SQLException} of a lock wait step out of a method that declares none.
     */
    static final class Failure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The database failure being carried. */
        private final SQLException sqlException;

        /**
         * Creates a failure.
         *
         * @param sqlException the JDBC failure, never {@code null}
         * @throws NullPointerException if {@code sqlException} is {@code null}
         */
        Failure(SQLException sqlException) {
            super(Objects.requireNonNull(sqlException, "sqlException"));
            this.sqlException = sqlException;
        }

        /**
         * Returns the JDBC failure.
         *
         * @return the wrapped failure, never {@code null}
         */
        SQLException sqlException() {
            return sqlException;
        }
    }
}
