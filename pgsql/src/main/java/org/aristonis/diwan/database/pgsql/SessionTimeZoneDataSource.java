package org.aristonis.diwan.database.pgsql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that runs {@code SET TIME ZONE} on every connection it hands out: one
 * statement per checkout, so a pool that resets sessions between checkouts is covered only when it
 * resets before it hands the connection to this wrapper.
 *
 * <p>The zone must already be validated: it is placed in the statement between quotes. The statement
 * runs in auto-commit mode, so a later rollback cannot undo it; a connection that comes with
 * auto-commit off gets it back after the statement. A connection on which the statement fails is
 * closed before the failure is rethrown.
 *
 * <p>Instances hold no state beyond their arguments and are as thread-safe as the delegate.
 */
final class SessionTimeZoneDataSource implements DataSource {

    private final DataSource delegate;
    private final String zone;

    /**
     * Wraps a data source.
     *
     * @param delegate the source of connections
     * @param zone the validated zone name or offset to set on each connection
     */
    SessionTimeZoneDataSource(DataSource delegate, String zone) {
        this.delegate = delegate;
        this.zone = zone;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withZone(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withZone(delegate.getConnection(username, password));
    }

    private Connection withZone(Connection connection) throws SQLException {
        try {
            setZone(connection);
            return connection;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    // Outside auto-commit the statement would sit in a transaction and a rollback would undo it.
    private void setZone(Connection connection) throws SQLException {
        boolean isRestoring = !connection.getAutoCommit();
        if (isRestoring) {
            connection.setAutoCommit(true);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("set time zone '" + zone + "'");
        } finally {
            if (isRestoring) {
                connection.setAutoCommit(false);
            }
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || delegate.isWrapperFor(type);
    }
}
