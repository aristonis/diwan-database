package org.aristonis.photisma.database.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Provides connections from a {@link DataSource}, closing each one when it is given back.
 *
 * <p>With a pooling data source, closing returns the connection to the pool. Instances are
 * immutable and safe to share between threads when the data source is.
 */
public final class DataSourceConnectionProvider implements JdbcConnectionProvider {

    /** The source of connections. */
    private final DataSource dataSource;

    /**
     * Creates a provider over a data source.
     *
     * @param dataSource the data source, never {@code null}
     * @throws NullPointerException if {@code dataSource} is {@code null}
     */
    public DataSourceConnectionProvider(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Obtains a connection with {@link DataSource#getConnection()}.
     *
     * @return a new or pooled connection, never {@code null}
     * @throws SQLException if the data source cannot supply one
     */
    @Override
    public Connection acquire() throws SQLException {
        Connection connection = dataSource.getConnection();
        if (connection == null) {
            throw new SQLException("data source returned no connection");
        }
        return connection;
    }

    /**
     * Closes the connection.
     *
     * @param connection the connection to close, never {@code null}
     * @throws SQLException if closing fails
     */
    @Override
    public void release(Connection connection) throws SQLException {
        connection.close();
    }
}
