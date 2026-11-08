package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverFailureMessages;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.exception.DriverException;
import org.junit.jupiter.api.Test;

/** How a session ends its own transaction, per database family, against a recording connection. */
class JdbcSessionCommitTest {

    private static final int SQLITE_BUSY = 5;

    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    private boolean isAutoCommit = true;
    private SQLException setAutoCommitFailure;

    private JdbcSession session(String product) throws SQLException {
        AtomicReference<DriverInfo> info = new AtomicReference<>(
                new DriverInfo(product, "1", EnumSet.of(DriverCapability.SAVEPOINT_RELEASE)));
        java.sql.Connection connection = (java.sql.Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "setAutoCommit":
                            events.add(name + " " + Arrays.toString(args));
                            if (setAutoCommitFailure != null && (Boolean) args[0]) {
                                SQLException failure = setAutoCommitFailure;
                                setAutoCommitFailure = null;
                                isAutoCommit = true;
                                throw failure;
                            }
                            isAutoCommit = (Boolean) args[0];
                            return null;
                        case "rollback":
                            if (isAutoCommit) {
                                throw new SQLException("database in auto-commit mode");
                            }
                            events.add(name);
                            return null;
                        case "commit":
                            events.add(name);
                            return null;
                        case "getAutoCommit":
                            return isAutoCommit;
                        case "isClosed":
                            return false;
                        default:
                            return null;
                    }
                });
        JdbcConnectionProvider provider = new JdbcConnectionProvider() {
            @Override
            public java.sql.Connection acquire() {
                return connection;
            }

            @Override
            public void release(java.sql.Connection released) {
                // The recording connection has nothing to release.
            }
        };
        return JdbcSession.open(provider, connection, info);
    }

    @Test
    void sqliteCommitNeverCallsCommitAgain() throws SQLException {
        JdbcSession session = session("SQLite");
        session.begin();
        events.clear();

        session.commit();

        assertEquals(Collections.singletonList("setAutoCommit [true]"), events);
    }

    @Test
    void postgresCommitPathIsUnchanged() throws SQLException {
        JdbcSession session = session("PostgreSQL");
        session.begin();
        events.clear();

        session.commit();

        assertEquals(Arrays.asList("commit", "setAutoCommit [true]"), events);
    }

    @Test
    void sqliteCommitFailureKeepsItsKinds() throws SQLException {
        JdbcSession session = session("SQLite");
        session.begin();
        setAutoCommitFailure = new SQLException("database is locked", "", SQLITE_BUSY);

        DriverException failure = assertThrows(DriverException.class, session::commit);

        assertTrue(failure.isConcurrencyFailure());
        assertTrue(failure.kinds().contains(DriverFailureKind.CONCURRENCY));
        assertEquals(DriverFailureMessages.message(failure.kinds(), failure.sqlState().orElse(null), SQLITE_BUSY,
                "SQLITE_BUSY", failure.error()), failure.getMessage());
        assertFalse(isAutoCommit);
        session.rollback();
        assertTrue(events.contains("rollback"));
    }

    @Test
    void sqliteCommitOutcomeUnknownIsInTheMessage() throws SQLException {
        JdbcSession session = session("SQLite");
        session.begin();
        setAutoCommitFailure = new SQLException("connection lost", "08006");

        DriverException failure = assertThrows(DriverException.class, session::commit);

        assertTrue(failure.getMessage().contains("commit outcome unknown"), failure.getMessage());
    }
}
