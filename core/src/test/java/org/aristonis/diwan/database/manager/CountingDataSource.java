package org.aristonis.diwan.database.manager;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** A data source that counts checkouts and closes and never hands out a real connection. */
public final class CountingDataSource implements DataSource, AutoCloseable {

    private final AtomicInteger checkouts = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    /** Returns how many connections were requested. */
    public int checkouts() {
        return checkouts.get();
    }

    /** Returns how many times {@link #close()} ran. */
    public int closes() {
        return closes.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkouts.incrementAndGet();
        throw new SQLException("The counting data source runs no SQL");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return false;
    }
}
