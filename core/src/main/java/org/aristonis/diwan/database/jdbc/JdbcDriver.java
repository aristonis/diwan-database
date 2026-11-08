package org.aristonis.diwan.database.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.driver.DatabaseFamily;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.DriverSession;

/**
 * Implements {@link Driver} over JDBC connections from a {@link JdbcConnectionProvider}.
 *
 * <p>Each session works on one connection acquired when it opens and released when it closes.
 * The driver is safe to share between threads; it holds no state besides the provider and the
 * cached {@link DriverInfo}.
 *
 * @implNote Capabilities are derived from the product name: savepoint release for PostgreSQL,
 *     MySQL, MariaDB and SQLite; transactional DDL for PostgreSQL, SQLite and SQL Server. Other
 *     databases report no capabilities.
 */
public final class JdbcDriver implements Driver {

    /** The source of connections. */
    private final JdbcConnectionProvider provider;
    /** The database description, read on first use. */
    private final AtomicReference<DriverInfo> info = new AtomicReference<>();
    private final AtomicReference<OptionalInt> exactDigits = new AtomicReference<>();

    /**
     * Creates a driver over a connection provider.
     *
     * @param provider the provider, never {@code null}
     * @throws NullPointerException if {@code provider} is {@code null}
     */
    public JdbcDriver(JdbcConnectionProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Opens a session on a connection acquired from the provider.
     *
     * @return a new session, never {@code null}
     * @throws DriverException if no connection can be acquired or prepared
     */
    @Override
    public DriverSession open() {
        Connection connection;
        try {
            connection = provider.acquire();
        } catch (SQLException e) {
            throw JdbcFailures.translate(e, null);
        }
        return JdbcSession.open(provider, connection, info);
    }

    /**
     * Describes the database, reading its metadata on the first call only.
     *
     * <p>A session opened before the first call seeds the description from its own connection,
     * so no extra connection is acquired here once a session has been opened.
     *
     * @implNote Concurrent first calls may each read the metadata; the first result stored wins.
     * @return the description, never {@code null}
     * @throws DriverException if the metadata cannot be read
     */
    @Override
    public DriverInfo info() {
        DriverInfo cached = info.get();
        if (cached != null) {
            return cached;
        }
        info.compareAndSet(null, readInfo());
        return info.get();
    }

    /**
     * Tells how many significant digits the database stores exactly in a decimal column.
     *
     * @implNote The answer is kept after the first call, as the description is.
     * @return the digits the detected database keeps exactly, or empty when there is no known limit
     * @throws DriverException if the metadata cannot be read
     */
    @Override
    public OptionalInt exactDecimalDigits() {
        OptionalInt cached = exactDigits.get();
        if (cached != null) {
            return cached;
        }
        JdbcLockWait lockWait = JdbcLockWait.forFamily(DatabaseFamily.of(info().databaseName()));
        exactDigits.compareAndSet(null, lockWait == null ? OptionalInt.empty() : lockWait.exactDecimalDigits());
        return exactDigits.get();
    }

    private DriverInfo readInfo() {
        Connection connection;
        try {
            connection = provider.acquire();
        } catch (SQLException e) {
            throw JdbcFailures.translate(e, null);
        }
        DriverException failure = null;
        DriverInfo result = null;
        try {
            result = describe(connection.getMetaData());
        } catch (SQLException e) {
            failure = JdbcFailures.translate(e, null);
        }
        try {
            provider.release(connection);
        } catch (SQLException e) {
            DriverException releaseFailure = JdbcFailures.translate(e, null);
            if (failure == null) {
                failure = releaseFailure;
            } else {
                failure.addSuppressed(releaseFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    /**
     * Builds a description from connection metadata.
     *
     * @param metaData the metadata of an open connection
     * @return the description, never {@code null}
     * @throws SQLException if the metadata cannot be read
     */
    static DriverInfo describe(DatabaseMetaData metaData) throws SQLException {
        String name = nonNull(metaData.getDatabaseProductName());
        String version = nonNull(metaData.getDatabaseProductVersion());
        return new DriverInfo(name, version, capabilities(name));
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    static Set<DriverCapability> capabilities(String productName) {
        DatabaseFamily family = DatabaseFamily.of(productName);
        Set<DriverCapability> result = EnumSet.noneOf(DriverCapability.class);
        boolean isPostgres = family == DatabaseFamily.POSTGRESQL;
        boolean isSqlite = family == DatabaseFamily.SQLITE;
        boolean isMysqlFamily = family == DatabaseFamily.MYSQL;
        boolean isSqlServer = family == DatabaseFamily.SQLSERVER;
        if (isPostgres || isSqlite || isMysqlFamily) {
            result.add(DriverCapability.SAVEPOINT_RELEASE);
        }
        if (isPostgres || isSqlite || isSqlServer) {
            result.add(DriverCapability.TRANSACTIONAL_DDL);
        }
        if (isPostgres) {
            result.add(DriverCapability.STREAMS_IN_TRANSACTION);
        }
        return result;
    }
}
