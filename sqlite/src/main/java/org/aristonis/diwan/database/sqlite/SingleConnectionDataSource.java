package org.aristonis.diwan.database.sqlite;

import org.aristonis.diwan.database.Internal;
import java.io.PrintWriter;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A data source over one JDBC connection that it lends to one session at a time.
 *
 * <p>An in-memory SQLite database lives inside a single connection, so every session has to use
 * that one connection. {@link #getConnection()} waits until the previous session has closed its
 * handle, then returns a handle whose {@code close()} gives the connection back instead of closing
 * it. A session that holds the connection while it asks for a second one on the same thread waits
 * for itself and fails after the timeout. {@link #close()} closes the real connection.
 *
 * <p>Instances are safe to share across threads.
 */
final class SingleConnectionDataSource implements DataSource, AutoCloseable {

    private final String name;
    private final Connection real;
    private final long timeoutMillis;
    private final Semaphore permit = new Semaphore(1, true);

    /**
     * Wraps an open connection.
     *
     * @param name the connection name, used in the busy message
     * @param real the open connection, owned by this data source from now on
     * @param timeoutMillis how long a session waits for the connection, zero or more
     */
    SingleConnectionDataSource(String name, Connection real, long timeoutMillis) {
        this.name = Objects.requireNonNull(name, "name");
        this.real = Objects.requireNonNull(real, "real");
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public Connection getConnection() throws SQLException {
        boolean isAcquired;
        try {
            isAcquired = permit.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for the in-memory SQLite database [" + name + "]", e);
        }
        if (!isAcquired) {
            throw new SQLException(
                    "In-memory SQLite database [" + name + "] is busy: one session at a time");
        }
        if (real.isClosed()) {
            permit.release();
            throw new SQLException("In-memory SQLite database [" + name + "] is closed");
        }
        return new Lease();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    /** Closes the real connection, which drops the database; a session still using it fails. */
    @Override
    public void close() throws SQLException {
        real.close();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        throw new SQLFeatureNotSupportedException("SingleConnectionDataSource does not support a log writer");
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("SingleConnectionDataSource does not support a login timeout");
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("SingleConnectionDataSource has no parent logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("SingleConnectionDataSource is not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    // The handle one session holds. Every call goes to the real connection while the lease is open
    // and fails once it is closed, so a stale handle never touches a connection another session has.
    private final class Lease implements Connection {

        private boolean isReleased;

        private synchronized Connection live() throws SQLException {
            if (isReleased) {
                throw new SQLException("The session on in-memory SQLite database [" + name + "] is closed");
            }
            return real;
        }

        @Override
        public synchronized void close() throws SQLException {
            if (isReleased) {
                return;
            }
            isReleased = true;
            try {
                // A session that ends mid-transaction must not leak it to the next session.
                if (!real.isClosed() && !real.getAutoCommit()) {
                    real.rollback();
                    real.setAutoCommit(true);
                }
            } finally {
                permit.release();
            }
        }

        @Override
        public synchronized boolean isClosed() throws SQLException {
            return isReleased || real.isClosed();
        }

        @Override
        public Statement createStatement() throws SQLException {
            return live().createStatement();
        }

        @Override
        public PreparedStatement prepareStatement(String sql) throws SQLException {
            return live().prepareStatement(sql);
        }

        @Override
        public CallableStatement prepareCall(String sql) throws SQLException {
            return live().prepareCall(sql);
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            return live().nativeSQL(sql);
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            live().setAutoCommit(autoCommit);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return live().getAutoCommit();
        }

        @Override
        public void commit() throws SQLException {
            live().commit();
        }

        @Override
        public void rollback() throws SQLException {
            live().rollback();
        }

        @Override
        public DatabaseMetaData getMetaData() throws SQLException {
            return live().getMetaData();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            live().setReadOnly(readOnly);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return live().isReadOnly();
        }

        @Override
        public void setCatalog(String catalog) throws SQLException {
            live().setCatalog(catalog);
        }

        @Override
        public String getCatalog() throws SQLException {
            return live().getCatalog();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            live().setTransactionIsolation(level);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return live().getTransactionIsolation();
        }

        @Override
        public SQLWarning getWarnings() throws SQLException {
            return live().getWarnings();
        }

        @Override
        public void clearWarnings() throws SQLException {
            live().clearWarnings();
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return live().createStatement(resultSetType, resultSetConcurrency);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
                throws SQLException {
            return live().prepareStatement(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
                throws SQLException {
            return live().prepareCall(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public Map<String, Class<?>> getTypeMap() throws SQLException {
            return live().getTypeMap();
        }

        @Override
        public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
            live().setTypeMap(map);
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
            live().setHoldability(holdability);
        }

        @Override
        public int getHoldability() throws SQLException {
            return live().getHoldability();
        }

        @Override
        public Savepoint setSavepoint() throws SQLException {
            return live().setSavepoint();
        }

        @Override
        public Savepoint setSavepoint(String savepointName) throws SQLException {
            return live().setSavepoint(savepointName);
        }

        @Override
        public void rollback(Savepoint savepoint) throws SQLException {
            live().rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(Savepoint savepoint) throws SQLException {
            live().releaseSavepoint(savepoint);
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
                throws SQLException {
            return live().createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                int resultSetHoldability) throws SQLException {
            return live().prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
                int resultSetHoldability) throws SQLException {
            return live().prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return live().prepareStatement(sql, autoGeneratedKeys);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return live().prepareStatement(sql, columnIndexes);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return live().prepareStatement(sql, columnNames);
        }

        @Override
        public Clob createClob() throws SQLException {
            return live().createClob();
        }

        @Override
        public Blob createBlob() throws SQLException {
            return live().createBlob();
        }

        @Override
        public NClob createNClob() throws SQLException {
            return live().createNClob();
        }

        @Override
        public SQLXML createSQLXML() throws SQLException {
            return live().createSQLXML();
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            return !isClosed() && real.isValid(timeout);
        }

        @Override
        public void setClientInfo(String clientName, String value) throws SQLClientInfoException {
            real.setClientInfo(clientName, value);
        }

        @Override
        public void setClientInfo(Properties properties) throws SQLClientInfoException {
            real.setClientInfo(properties);
        }

        @Override
        public String getClientInfo(String clientName) throws SQLException {
            return live().getClientInfo(clientName);
        }

        @Override
        public Properties getClientInfo() throws SQLException {
            return live().getClientInfo();
        }

        @Override
        public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return live().createArrayOf(typeName, elements);
        }

        @Override
        public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return live().createStruct(typeName, attributes);
        }

        @Override
        public void setSchema(String schema) throws SQLException {
            live();
            throw new SQLFeatureNotSupportedException("setSchema");
        }

        @Override
        public String getSchema() throws SQLException {
            live();
            return null;
        }

        @Override
        public void abort(Executor executor) throws SQLException {
            close();
        }

        @Override
        public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
            live();
            throw new SQLFeatureNotSupportedException("setNetworkTimeout");
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            live();
            return 0;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return live().unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || live().isWrapperFor(iface);
        }
    }
}
