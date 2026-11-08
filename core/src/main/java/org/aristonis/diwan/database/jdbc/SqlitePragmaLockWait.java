package org.aristonis.diwan.database.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.OptionalInt;
import org.aristonis.diwan.database.driver.LockWaitGuard;

/**
 * The lock wait of SQLite: the {@code busy_timeout} pragma of the connection.
 *
 * <p>SQLite waits for a lock only as long as the busy timeout says, and the timeout must be set before
 * the transaction begins. The guard saves the current value, sets the new one, and puts the saved one
 * back when the transaction ends. A single statement cannot have its own wait. SQLite stores decimals
 * as binary floating point, so 15 significant digits are exact.
 *
 * <p>Instances hold no state and are safe to share across threads.
 */
final class SqlitePragmaLockWait implements JdbcLockWait {

    /** The shared instance. */
    static final SqlitePragmaLockWait INSTANCE = new SqlitePragmaLockWait();

    private SqlitePragmaLockWait() {
    }

    /** The significant decimal digits a double keeps exactly. */
    static final int EXACT_DECIMAL_DIGITS = 15;

    @Override
    public LockWaitGuard guard(Connection connection, long millis) {
        return new Guard(connection, millis);
    }

    @Override
    public LockWaitGuard statement(Connection connection, long millis) {
        throw new UnsupportedOperationException("A lock wait timeout on a single statement is not supported "
                + "by SQLite; use TransactionOptions.lockWait");
    }

    @Override
    public OptionalInt exactDecimalDigits() {
        return OptionalInt.of(EXACT_DECIMAL_DIGITS);
    }

    private static final class Guard implements LockWaitGuard {
        private final Connection connection;
        private final long millis;
        /** The timeout before this guard, or {@code null} when it was not read. */
        private Long saved;

        Guard(Connection connection, long millis) {
            this.connection = connection;
            this.millis = millis;
        }

        @Override
        public void beforeBegin() {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("PRAGMA busy_timeout")) {
                    if (rows.next()) {
                        saved = rows.getLong(1);
                    }
                }
                if (saved == null) {
                    throw new SQLException("PRAGMA busy_timeout returned no row");
                }
                statement.execute("PRAGMA busy_timeout = " + millis);
            } catch (SQLException e) {
                saved = null;
                throw new JdbcLockWait.Failure(e);
            }
        }

        @Override
        public void afterBegin() {
            // The timeout is already set; nothing to do inside the transaction.
        }

        @Override
        public void restore() {
            Long previous = saved;
            if (previous == null) {
                return;
            }
            saved = null;
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = " + previous);
            } catch (SQLException e) {
                throw new JdbcLockWait.Failure(e);
            }
        }
    }
}
