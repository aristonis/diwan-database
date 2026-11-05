package org.aristonis.diwan.database.manager;

import java.util.Objects;
import org.aristonis.diwan.database.Connection;

/**
 * Describes a connection a {@link DatabaseManager} has just built.
 *
 * <p>Instances are immutable and safe to share between threads.
 *
 * @see ManagerListener#connectionEstablished(ConnectionEstablished)
 */
public final class ConnectionEstablished {

    private final String connectionName;
    private final String driver;
    private final Connection connection;

    ConnectionEstablished(String connectionName, String driver, Connection connection) {
        this.connectionName = Objects.requireNonNull(connectionName, "connectionName");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Returns the configured name of the connection.
     *
     * @return the name, never {@code null}
     */
    public String connectionName() {
        return connectionName;
    }

    /**
     * Returns the driver name of the connector that built the connection.
     *
     * @return the driver name, never {@code null}
     */
    public String driver() {
        return driver;
    }

    /**
     * Returns the new connection.
     *
     * @return the connection, never {@code null}
     */
    public Connection connection() {
        return connection;
    }

    @Override
    public String toString() {
        return "ConnectionEstablished[" + connectionName + ", " + driver + "]";
    }
}
