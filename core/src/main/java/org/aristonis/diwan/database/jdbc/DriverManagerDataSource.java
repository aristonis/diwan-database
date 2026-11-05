package org.aristonis.diwan.database.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.aristonis.diwan.database.manager.ConnectionConfig;

/**
 * A data source that opens a new connection through {@link DriverManager} on every call.
 *
 * <p>The user and password are passed to the driver as properties and are never written into the
 * URL. {@link #toString()} shows the URL with its secrets masked (user info and
 * {@code password}, {@code passwd} and {@code pwd} parameters) and omits the
 * password field. The JDBC driver must be on the classpath and registered the usual way.
 *
 * <p>There is no pooling: each {@link #getConnection()} opens a physical connection that the caller
 * must close. A server that needs a pool supplies its own {@link DataSource} instead. Instances are
 * immutable and safe to share between threads.
 */
public final class DriverManagerDataSource implements DataSource {

    private static final String USER_PROPERTY = "user";
    private static final String PASSWORD_PROPERTY = "password";

    /** The JDBC URL. */
    private final String url;

    /** The user name, or {@code null} for none. */
    private final String user;

    /** The password, or {@code null} for none; never printed. */
    private final String password;

    /**
     * Creates a data source.
     *
     * @param url the JDBC URL, never {@code null} or empty
     * @param user the user name, or {@code null} when the URL or the driver needs none
     * @param password the password, or {@code null} when none is needed
     * @throws NullPointerException if {@code url} is {@code null}
     * @throws IllegalArgumentException if {@code url} is empty
     */
    public DriverManagerDataSource(String url, String user, String password) {
        this.url = Objects.requireNonNull(url, "url");
        if (url.isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        this.user = user;
        this.password = password;
    }

    /**
     * Opens a connection with the user and password given at construction.
     *
     * @return a new connection the caller must close, never {@code null}
     * @throws SQLException if no driver accepts the URL or the connection fails; the message has its
     *     secrets masked
     */
    @Override
    public Connection getConnection() throws SQLException {
        return open(user, password);
    }

    /**
     * Opens a connection with credentials that replace the ones given at construction.
     *
     * @param username the user name, or {@code null} for none
     * @param secret the password, or {@code null} for none
     * @return a new connection the caller must close, never {@code null}
     * @throws SQLException if no driver accepts the URL or the connection fails
     */
    @Override
    public Connection getConnection(String username, String secret) throws SQLException {
        return open(username, secret);
    }

    private Connection open(String username, String secret) throws SQLException {
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty(USER_PROPERTY, username);
        }
        if (secret != null) {
            properties.setProperty(PASSWORD_PROPERTY, secret);
        }
        try {
            return DriverManager.getConnection(url, properties);
        } catch (SQLException e) {
            SQLException masked = new SQLException(ConnectionConfig.mask(e.getMessage()), e.getSQLState(),
                    e.getErrorCode(), e.getCause());
            masked.setStackTrace(e.getStackTrace());
            throw masked;
        }
    }

    /**
     * Returns no log writer, because this data source does not log.
     *
     * @return always {@code null}
     */
    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    /**
     * Rejects a log writer, because this data source does not log.
     *
     * @param out ignored
     * @throws SQLFeatureNotSupportedException always
     */
    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        throw new SQLFeatureNotSupportedException("DriverManagerDataSource does not support a log writer");
    }

    /**
     * Rejects a login timeout, because {@link DriverManager} keeps one value for the whole process.
     *
     * @param seconds ignored
     * @throws SQLFeatureNotSupportedException always
     */
    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("DriverManagerDataSource does not support a login timeout");
    }

    /**
     * Returns no login timeout of its own.
     *
     * @return always {@code 0}, meaning the driver default
     */
    @Override
    public int getLoginTimeout() {
        return 0;
    }

    /**
     * Rejects the request, because this data source has no logger.
     *
     * @return nothing; the method always throws
     * @throws SQLFeatureNotSupportedException always
     */
    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("DriverManagerDataSource has no parent logger");
    }

    /**
     * Returns this object when it is an instance of the interface.
     *
     * @param <T> the requested type
     * @param iface the interface or class, never {@code null}
     * @return this data source cast to {@code iface}
     * @throws SQLException if this data source is not an instance of {@code iface}
     */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("DriverManagerDataSource is not a wrapper for " + iface.getName());
    }

    /**
     * Tells whether this object is an instance of the interface.
     *
     * @param iface the interface or class, never {@code null}
     * @return {@code true} when {@link #unwrap(Class)} would succeed
     */
    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    /**
     * Describes the data source without its password.
     *
     * @return the class name, the URL with its secrets masked, and the user name
     */
    @Override
    public String toString() {
        return "DriverManagerDataSource[url=" + ConnectionConfig.mask(url)
                + ", user=" + user + "]";
    }
}
