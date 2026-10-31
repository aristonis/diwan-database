package org.aristonis.photisma.database.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.sqlite.SQLiteDataSource;

/** SQLite file database that counts connections still open and checks auto-commit on return. */
final class CountingDataSource implements DataSource {

    private final SQLiteDataSource target = new SQLiteDataSource();
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger returnedWithoutAutoCommit = new AtomicInteger();

    private boolean handsOutWithoutAutoCommit;

    /** Behaves like a pool configured with auto-commit off (common with Spring Boot and HikariCP). */
    CountingDataSource withoutAutoCommit() {
        handsOutWithoutAutoCommit = true;
        return this;
    }

    CountingDataSource(String file) {
        target.setUrl("jdbc:sqlite:" + file);
        target.setBusyTimeout(10_000);
    }

    int openConnections() {
        return open.get();
    }

    int returnedWithoutAutoCommit() {
        return returnedWithoutAutoCommit.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection real = target.getConnection();
        if (handsOutWithoutAutoCommit) {
            real.setAutoCommit(false);
        }
        open.incrementAndGet();
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("close") && !real.isClosed()) {
                if (real.getAutoCommit() == handsOutWithoutAutoCommit) {
                    returnedWithoutAutoCommit.incrementAndGet();
                }
                open.decrementAndGet();
            }
            try {
                return method.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
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
