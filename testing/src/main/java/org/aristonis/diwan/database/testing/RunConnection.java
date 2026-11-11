package org.aristonis.diwan.database.testing;

import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

/**
 * The one JDBC connection a template-mode run holds on its root data source.
 *
 * <p>Creating and dropping databases, and the locks that tell runs apart, all go through it, so a
 * lock lives exactly as long as the run and a pooled data source loses one slot, not one per test.
 * It is opened with auto-commit on, because {@code create database} cannot run in a transaction.
 * The JUnit root store closes it when the run ends. Give the root a plain driver data source or a
 * pool in session mode; a transaction-mode pooler moves statements between sessions and breaks
 * session locks.
 *
 * <p>The Diwan connection over it is safe to share, but the JDBC connection serves one statement
 * at a time.
 */
// CloseableResource is deprecated in JUnit 6, but JUnit 5.10, the oldest supported, closes only it.
@SuppressWarnings("deprecation")
final class RunConnection implements CloseableResource {

    private final java.sql.Connection jdbc;
    private final Connection connection;
    private final Object monitor = new Object();

    private RunConnection(java.sql.Connection jdbc) {
        this.jdbc = jdbc;
        this.connection = Connection.builder().name("diwan-template-run")
                .connectionProvider(new Lender(jdbc)).build();
    }

    /**
     * Opens the connection.
     *
     * @param root the data source of the server that holds the databases, never {@code null}
     * @return the open run connection, never {@code null}
     * @throws NullPointerException if {@code root} is {@code null}
     * @throws IllegalStateException if the data source cannot give a connection
     */
    static RunConnection open(DataSource root) {
        Objects.requireNonNull(root, "root");
        java.sql.Connection jdbc;
        try {
            jdbc = root.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("Template mode cannot open its root connection: " + e.getMessage(), e);
        }
        try {
            jdbc.setAutoCommit(true);
            return new RunConnection(jdbc);
        } catch (SQLException | RuntimeException e) {
            closeAfterFailure(jdbc, e);
            throw new IllegalStateException("Template mode cannot set up its root connection: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the Diwan connection over the held JDBC connection.
     *
     * @return the connection, never {@code null}
     */
    Connection connection() {
        return connection;
    }

    /**
     * Returns the monitor that serialises work on the JDBC connection; every template that shares
     * this run connection takes it around each statement sequence it sends.
     *
     * @return the monitor, the same object on every call, never {@code null}
     */
    Object monitor() {
        return monitor;
    }

    /**
     * Closes the JDBC connection, which frees every lock held on it.
     *
     * @throws IllegalStateException if the close fails
     */
    @Override
    public void close() {
        try {
            jdbc.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Template mode cannot close its root connection: " + e.getMessage(), e);
        }
    }

    private static void closeAfterFailure(java.sql.Connection jdbc, Exception failure) {
        try {
            jdbc.close();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    /** Lends the one connection; giving it back does nothing, because the run owns it. */
    private static final class Lender implements JdbcConnectionProvider {
        private final java.sql.Connection jdbc;

        Lender(java.sql.Connection jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public java.sql.Connection acquire() throws SQLException {
            if (jdbc.isClosed()) {
                throw new SQLException("The template run's root connection is closed");
            }
            return jdbc;
        }

        @Override
        public void release(java.sql.Connection connection) {
            // Kept open for the run on purpose: it carries the run's locks.
        }
    }
}
