package org.aristonis.diwan.database.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.OptionalInt;
import org.aristonis.diwan.database.driver.LockWaitGuard;

/**
 * The lock wait of PostgreSQL: {@code SET LOCAL lock_timeout} inside the transaction.
 *
 * <p>The setting lasts until the transaction ends, so a transaction guard restores nothing. A
 * statement guard limits the locks of one statement inside an open transaction: it saves the
 * current {@code lock_timeout}, sets the new one, and puts the saved value back with
 * {@code set_config}, passed as a bound parameter, once the statement ran. Only a number of
 * milliseconds goes into the SQL text.
 *
 * <p>Instances hold no state and are safe to share across threads.
 */
final class PostgresSetLocalLockWait implements JdbcLockWait {

    /** The shared instance. */
    static final PostgresSetLocalLockWait INSTANCE = new PostgresSetLocalLockWait();

    private PostgresSetLocalLockWait() {
    }

    @Override
    public LockWaitGuard guard(Connection connection, long millis) {
        return new Guard(connection, millis);
    }

    @Override
    public LockWaitGuard statement(Connection connection, long millis) {
        return new StatementGuard(connection, millis);
    }

    @Override
    public OptionalInt exactDecimalDigits() {
        return OptionalInt.empty();
    }

    private static void setLocal(Connection connection, long millis) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '" + Long.toString(millis) + "ms'");
        } catch (SQLException e) {
            throw new JdbcLockWait.Failure(e);
        }
    }

    private static final class Guard implements LockWaitGuard {
        private final Connection connection;
        private final long millis;

        Guard(Connection connection, long millis) {
            this.connection = connection;
            this.millis = millis;
        }

        @Override
        public void beforeBegin() {
            // The setting is local to the transaction, so it can only be made after the begin.
        }

        @Override
        public void afterBegin() {
            setLocal(connection, millis);
        }

        @Override
        public void restore() {
            // The setting ends with the transaction.
        }
    }

    // Saves the timeout in force, so a statement limit does not outlive its statement.
    private static final class StatementGuard implements LockWaitGuard {
        private final Connection connection;
        private final long millis;
        /** The timeout before this guard, or {@code null} when it was not read. */
        private String saved;

        StatementGuard(Connection connection, long millis) {
            this.connection = connection;
            this.millis = millis;
        }

        @Override
        public void beforeBegin() {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("select current_setting('lock_timeout')")) {
                if (!rows.next()) {
                    throw new SQLException("current_setting('lock_timeout') returned no row");
                }
                saved = rows.getString(1);
            } catch (SQLException e) {
                throw new JdbcLockWait.Failure(e);
            }
            setLocal(connection, millis);
        }

        @Override
        public void afterBegin() {
            // A statement guard is applied by beforeBegin, which the caller runs right before the statement.
        }

        @Override
        public void restore() {
            String previous = saved;
            if (previous == null) {
                return;
            }
            saved = null;
            try (PreparedStatement statement =
                    connection.prepareStatement("select set_config('lock_timeout', ?, true)")) {
                statement.setString(1, previous);
                // execute, not executeQuery: the row only echoes the value, so nothing is read.
                statement.execute();
            } catch (SQLException e) {
                throw new JdbcLockWait.Failure(e);
            }
        }
    }
}
