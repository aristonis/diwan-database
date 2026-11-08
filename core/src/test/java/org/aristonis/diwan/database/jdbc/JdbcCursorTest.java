package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.driver.RowCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcCursorTest {

    private static final String SELECT = "select id, name from t order by id";

    @TempDir
    Path folder;

    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    // Thrown by the result set's close or next when set, after the event is recorded.
    private Error resultSetCloseError;
    private RuntimeException nextFailure;
    private final List<PreparedStatement> statements = new ArrayList<>();
    private SQLiteDataSource dataSource;
    private java.sql.Connection raw;

    @BeforeEach
    void createTable() throws SQLException {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("cursor.db"));
        raw = dataSource.getConnection();
        try (java.sql.Statement statement = raw.createStatement()) {
            statement.execute("create table t (id integer primary key, name text)");
            for (int i = 1; i <= 5; i++) {
                statement.execute("insert into t (name) values ('n" + i + "')");
            }
        }
    }

    @AfterEach
    void closeRaw() throws SQLException {
        raw.close();
    }

    private JdbcSession session(boolean streamsInTransaction, boolean isExternal) throws SQLException {
        EnumSet<DriverCapability> capabilities = EnumSet.of(DriverCapability.SAVEPOINT_RELEASE);
        if (streamsInTransaction) {
            capabilities.add(DriverCapability.STREAMS_IN_TRANSACTION);
        }
        AtomicReference<DriverInfo> info = new AtomicReference<>(new DriverInfo("SQLite", "3", capabilities));
        JdbcConnectionProvider provider = new JdbcConnectionProvider() {
            @Override
            public java.sql.Connection acquire() throws SQLException {
                return spy(dataSource.getConnection());
            }

            @Override
            public void release(java.sql.Connection connection) throws SQLException {
                connection.close();
            }

            @Override
            public boolean inExternalTransaction(java.sql.Connection connection) {
                return isExternal;
            }
        };
        return JdbcSession.open(provider, provider.acquire(), info);
    }

    private java.sql.Connection spy(java.sql.Connection target) {
        return (java.sql.Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("commit") || name.equals("rollback") || name.equals("setAutoCommit")) {
                        events.add(args == null ? name : name + " " + Arrays.toString(args));
                    }
                    Object result = invoke(target, method, args);
                    if (result instanceof PreparedStatement) {
                        if (args.length == 3) {
                            events.add("prepare " + args[1] + " " + args[2]);
                        }
                        PreparedStatement statement = statementSpy((PreparedStatement) result);
                        statements.add(statement);
                        return statement;
                    }
                    return result;
                });
    }

    private PreparedStatement statementSpy(PreparedStatement target) {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("setFetchSize") || name.equals("close")) {
                        events.add(args == null ? "statement " + name : "statement " + name + " " + args[0]);
                    }
                    Object result = invoke(target, method, args);
                    if (result instanceof ResultSet) {
                        ResultSet resultSet = (ResultSet) result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                                (inner, innerMethod, innerArgs) -> {
                                    if (innerMethod.getName().equals("close")) {
                                        events.add("resultSet close");
                                        if (resultSetCloseError != null) {
                                            throw resultSetCloseError;
                                        }
                                    }
                                    if (innerMethod.getName().equals("next") && nextFailure != null) {
                                        throw nextFailure;
                                    }
                                    return invoke(resultSet, innerMethod, innerArgs);
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void streamsForwardOnlyReadOnlyWithAFetchSizeAndKeepsTheStatementOpen() throws SQLException {
        JdbcSession session = session(false, false);
        try (RowCursor cursor = session.cursor(SELECT, Collections.<Object>emptyList())) {
            Map<String, Object> first = cursor.next();

            assertEquals(1L, ((Number) first.get("id")).longValue());
            assertEquals(Arrays.asList("id", "name"), new ArrayList<>(first.keySet()));
            assertFalse(statements.get(0).isClosed());
            assertTrue(events.contains("prepare " + ResultSet.TYPE_FORWARD_ONLY + " " + ResultSet.CONCUR_READ_ONLY));
            assertTrue(events.contains("statement setFetchSize 500"));
        } finally {
            session.close();
        }
        assertTrue(statements.get(0).isClosed());
        assertFalse(events.contains("commit"));
    }

    @Test
    void closesItselfWhenExhaustedAndRefusesASecondIteration() throws SQLException {
        JdbcSession session = session(false, false);
        try {
            RowCursor cursor = session.cursor("select id from t where id > ?", Collections.<Object>singletonList(3));
            List<Object> ids = new ArrayList<>();
            for (Map<String, Object> row : cursor) {
                ids.add(((Number) row.get("id")).longValue());
            }

            assertEquals(Arrays.asList(4L, 5L), ids);
            assertTrue(cursor.isClosed());
            assertTrue(statements.get(0).isClosed());
            assertThrows(IllegalStateException.class, cursor::iterator);
            assertThrows(NoSuchElementException.class, cursor::next);
            assertThrows(UnsupportedOperationException.class, cursor::remove);
        } finally {
            session.close();
        }
    }

    @Test
    void ownsATransactionWhenTheDatabaseStreamsOnlyInsideOne() throws SQLException {
        JdbcSession session = session(true, false);
        try {
            RowCursor cursor = session.cursor(SELECT, Collections.<Object>emptyList());
            cursor.next();
            assertFalse(raw.isClosed());
            cursor.close();

            assertEquals(Arrays.asList("setAutoCommit [false]", "prepare " + ResultSet.TYPE_FORWARD_ONLY + " "
                    + ResultSet.CONCUR_READ_ONLY, "statement setFetchSize 500", "resultSet close", "statement close",
                    "setAutoCommit [true]"), events);
            // SQLite commits by switching auto-commit on, so no separate commit call is recorded.
            session.update("insert into t (name) values ('after')", Collections.emptyList());
        } finally {
            session.close();
        }
    }

    @Test
    void rollsBackItsOwnTransactionAfterAFailure() throws SQLException {
        JdbcSession session = session(true, false);
        try {
            assertThrows(DriverException.class, () -> session.cursor("select nope from t", Collections.emptyList()));

            assertTrue(events.contains("rollback"));
            assertEquals("setAutoCommit [true]", events.get(events.size() - 1));
        } finally {
            session.close();
        }
    }

    @Test
    void anUncheckedFailureWhileReadingRollsBackInsteadOfCommitting() throws SQLException {
        JdbcSession session = session(true, false);
        try {
            RowCursor cursor = session.cursor(SELECT, Collections.<Object>emptyList());
            nextFailure = new IllegalStateException("driver bug");

            assertThrows(IllegalStateException.class, cursor::next);
            nextFailure = null;
            cursor.close();

            assertTrue(events.contains("rollback"));
            assertFalse(events.contains("commit"));
            assertEquals("setAutoCommit [true]", events.get(events.size() - 1));
        } finally {
            session.close();
        }
    }

    @Test
    void anErrorClosingTheResultSetStillClosesTheStatementAndRestoresAutoCommit() throws SQLException {
        JdbcSession session = session(true, false);
        try {
            RowCursor cursor = session.cursor(SELECT, Collections.<Object>emptyList());
            cursor.next();
            Error boom = new Error("boom");
            resultSetCloseError = boom;

            Error thrown = assertThrows(Error.class, cursor::close);

            assertEquals(boom, thrown);
            assertTrue(statements.get(0).isClosed());
            assertTrue(events.contains("rollback"));
            assertEquals("setAutoCommit [true]", events.get(events.size() - 1));
            resultSetCloseError = null;
            session.update("insert into t (name) values ('after')", Collections.emptyList());
        } finally {
            session.close();
        }
    }

    @Test
    void readsInsideTheCallersTransactionWithoutEndingIt() throws SQLException {
        JdbcSession session = session(true, false);
        try {
            session.begin();
            events.clear();
            session.cursor(SELECT, Collections.<Object>emptyList()).close();

            assertFalse(events.contains("commit"));
            assertFalse(events.contains("setAutoCommit [false]"));
            session.rollback();
        } finally {
            session.close();
        }
    }

    @Test
    void leavesAnExternalTransactionAlone() throws SQLException {
        JdbcSession session = session(true, true);
        try {
            session.cursor(SELECT, Collections.<Object>emptyList()).close();

            assertFalse(events.contains("commit"));
            assertFalse(events.contains("setAutoCommit [false]"));
        } finally {
            session.close();
        }
    }

    @Test
    void refusesDuplicateLabelsAndReleasesTheStatement() throws SQLException {
        JdbcSession session = session(false, false);
        try {
            assertThrows(DriverException.class, () -> session.cursor("select id, id from t", Collections.emptyList()));
            assertTrue(statements.get(0).isClosed());
        } finally {
            session.close();
        }
    }

    @Test
    void refusesACursorOnAClosedSession() throws SQLException {
        JdbcSession session = session(false, false);
        session.close();

        assertThrows(IllegalStateException.class, () -> session.cursor(SELECT, Collections.emptyList()));
    }
}
