package org.aristonis.photisma.database.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies the JDBC connections a {@link JdbcDriver} works on, and takes them back.
 *
 * <p>A provider decides where connections come from and what giving one back means: closing it,
 * returning it to a pool, or leaving it with an outer owner. Integrations that manage their own
 * transactions implement this interface; a Spring provider, for example, acquires through
 * {@code DataSourceUtils.getConnection}, releases through {@code DataSourceUtils.releaseConnection},
 * and reports {@code true} from {@link #inExternalTransaction(Connection)} while Spring's
 * transaction is active, so Photisma nests inside it with savepoints instead of committing it.
 *
 * @implSpec Implementations must be safe to call from several threads at once, because one driver
 *     serves every thread that uses the connection built on it.
 */
public interface JdbcConnectionProvider {

    /**
     * Acquires a connection for one session.
     *
     * @return a usable connection, never {@code null}
     * @throws SQLException if no connection can be obtained
     */
    Connection acquire() throws SQLException;

    /**
     * Gives back a connection obtained from {@link #acquire()}.
     *
     * @param connection the connection to give back, never {@code null}
     * @throws SQLException if giving it back fails
     */
    void release(Connection connection) throws SQLException;

    /**
     * Reports whether the connection is inside a transaction owned by someone other than Photisma.
     *
     * @implSpec The default returns {@code false}. The driver asks once, right after
     *     {@link #acquire()}, and keeps the answer for the life of the session.
     * @param connection a connection obtained from {@link #acquire()}, never {@code null}
     * @return {@code true} if an outer owner will commit or roll back the transaction
     * @throws SQLException if the state cannot be determined
     */
    default boolean inExternalTransaction(Connection connection) throws SQLException {
        return false;
    }
}
