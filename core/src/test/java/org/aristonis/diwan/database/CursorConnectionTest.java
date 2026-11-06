package org.aristonis.diwan.database;

import org.aristonis.diwan.database.exception.QueryException;
import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.aristonis.diwan.database.FakeDriver.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.query.QueryCursor;
import org.junit.jupiter.api.Test;

class CursorConnectionTest {

    private static final String SELECT = "select * from t";

    private final FakeDriver driver = new FakeDriver()
            .returns(SELECT, Arrays.asList(row("id", 1L), row("id", 2L), row("id", 3L)));

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    private static List<Object> ids(QueryCursor<Map<String, Object>> cursor) {
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> row : cursor) {
            ids.add(row.get("id"));
        }
        return ids;
    }

    @Test
    void rootCursorStreamsRowsAndGivesTheSessionBackWhenExhausted() {
        QueryCursor<Map<String, Object>> cursor = connection().cursor(SELECT);

        assertEquals(1, driver.openSessions());
        assertEquals(Arrays.asList(1L, 2L, 3L), ids(cursor));
        assertEquals(0, driver.openCursors());
        assertEquals(0, driver.openSessions());
        assertEquals(Arrays.asList("open", "cursor " + SELECT + " []", "cursorClose", "close"), driver.calls());
        cursor.close();
    }

    @Test
    void closingEarlyGivesTheSessionBack() {
        try (QueryCursor<Map<String, Object>> cursor = connection().cursor(SELECT, 7)) {
            assertEquals(1L, cursor.iterator().next().get("id"));
        }

        assertEquals(0, driver.openSessions());
        assertEquals(0, driver.openCursors());
        assertTrue(driver.calls().contains("cursor " + SELECT + " [7]"));
    }

    @Test
    void secondIterationIsRefused() {
        try (QueryCursor<Map<String, Object>> cursor = connection().cursor(SELECT)) {
            Iterator<Map<String, Object>> first = cursor.iterator();

            assertNotNull(first);
            assertThrows(IllegalStateException.class, cursor::iterator);
            assertThrows(IllegalStateException.class, cursor::stream);
        }
    }

    @Test
    void nextAfterTheEndAndAfterCloseFailsClearly() {
        Iterator<Map<String, Object>> rows = connection().cursor(SELECT).iterator();
        while (rows.hasNext()) {
            rows.next();
        }

        assertThrows(NoSuchElementException.class, rows::next);
        QueryCursor<Map<String, Object>> closed = connection().cursor(SELECT);
        Iterator<Map<String, Object>> closedRows = closed.iterator();
        closed.close();
        assertFalse(closedRows.hasNext());
        assertThrows(NoSuchElementException.class, closedRows::next);
    }

    @Test
    void rowsAreUnmodifiableAndRemoveIsRefused() {
        try (QueryCursor<Map<String, Object>> cursor = connection().cursor(SELECT)) {
            Iterator<Map<String, Object>> rows = cursor.iterator();
            Map<String, Object> row = rows.next();

            assertThrows(UnsupportedOperationException.class, () -> row.put("id", 9L));
            assertThrows(UnsupportedOperationException.class, rows::remove);
        }
    }

    @Test
    void fiftyCursorsOnAPoolOfOneNeverLeak() {
        driver.poolOf(1);
        Connection connection = connection();

        for (int i = 0; i < 50; i++) {
            try (QueryCursor<Map<String, Object>> cursor = connection.cursor(SELECT)) {
                cursor.iterator().next();
            }
            assertEquals(3, ids(connection.cursor(SELECT)).size());
        }

        assertEquals(0, driver.openSessions());
        assertEquals(0, driver.openCursors());
    }

    @Test
    void aFailureMidwayClosesTheCursorAndTheSession() {
        driver.poolOf(1).cursorFailsAtRow(1, failure("XX000"));
        Connection connection = connection();

        for (int i = 0; i < 50; i++) {
            Iterator<Map<String, Object>> rows = connection.cursor(SELECT).iterator();
            rows.next();
            QueryException error = assertThrows(QueryException.class, rows::next);
            assertEquals(SELECT, error.getSql());
            assertEquals(0, driver.openCursors());
        }

        assertEquals(0, driver.openSessions());
        assertEquals(0, driver.openCursors());
    }

    @Test
    void aFailureToOpenTheCursorGivesTheSessionBack() {
        driver.failsOn(SELECT, failure("42P01"));

        assertThrows(QueryException.class, () -> connection().cursor(SELECT));

        assertEquals(0, driver.openSessions());
    }

    @Test
    void aFailingCursorCloseStillGivesTheSessionBack() {
        driver.cursorFailsToClose(failure("XX001"));
        QueryCursor<Map<String, Object>> cursor = connection().cursor(SELECT);

        assertThrows(QueryException.class, cursor::close);

        assertEquals(0, driver.openCursors());
        assertEquals(0, driver.openSessions());
        cursor.close();
    }

    @Test
    void aDriverWithoutCursorsIsReportedAndItsSessionGivenBack() {
        Connection connection = Connection.builder().name("plain").driver(new PlainDriver()).build();

        assertThrows(UnsupportedOperationException.class, () -> connection.cursor(SELECT));
    }

    @Test
    void aCursorInATransactionBlocksOtherStatementsUntilClosed() {
        driver.returns("select 1", Arrays.asList(row("x", 1L)));

        connection().transaction(tx -> {
            QueryCursor<Map<String, Object>> cursor = tx.cursor(SELECT);
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> tx.select("select 1"));
            assertTrue(refused.getMessage().contains("cursor is open"));
            assertThrows(IllegalStateException.class, () -> tx.cursor(SELECT));
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> null));
            cursor.close();
            return tx.select("select 1");
        });

        assertEquals(1, driver.sessionsOpened());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void exhaustingACursorReleasesTheGuard() {
        driver.returns("select 1", Arrays.asList(row("x", 1L)));

        List<Map<String, Object>> rows = connection().transaction(tx -> {
            ids(tx.cursor(SELECT));
            return tx.select("select 1");
        });

        assertEquals(1, rows.size());
    }

    @Test
    void endingATransactionClosesItsOpenCursorBeforeTheCommit() {
        connection().transaction(tx -> tx.cursor(SELECT).iterator().next());

        List<String> calls = driver.calls();
        assertTrue(calls.indexOf("cursorClose") < calls.indexOf("commit"));
        assertEquals(0, driver.openCursors());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aFailedTransactionClosesItsCursorAndRollsBack() {
        assertThrows(IllegalArgumentException.class, () -> connection().transaction(tx -> {
            tx.cursor(SELECT).iterator().next();
            throw new IllegalArgumentException("boom");
        }));

        List<String> calls = driver.calls();
        assertTrue(calls.indexOf("cursorClose") < calls.indexOf("rollback"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aNestedTransactionClosesOnlyTheCursorItOpened() {
        connection().transaction(tx -> {
            tx.transaction(inner -> inner.cursor(SELECT).iterator().next());
            assertEquals(0, driver.openCursors());
            return tx.cursor(SELECT).iterator().next();
        });

        assertEquals(0, driver.openCursors());
    }

    @Test
    void aCursorInAParentScopeBlocksTheChildScope() {
        try (TransactionScope scope = connection().beginTransaction()) {
            TransactionScope child = scope.beginTransaction();
            QueryCursor<Map<String, Object>> cursor = scope.cursor(SELECT);

            assertThrows(IllegalStateException.class, () -> child.select(SELECT));
            assertThrows(IllegalStateException.class, child::beginTransaction);
            cursor.close();
            child.select(SELECT);
            child.commit();
            scope.commit();
        }

        assertEquals(0, driver.openSessions());
    }

    @Test
    void endingAChildScopeClosesOnlyItsOwnCursor() {
        try (TransactionScope scope = connection().beginTransaction()) {
            TransactionScope child = scope.beginTransaction();
            QueryCursor<Map<String, Object>> cursor = child.cursor(SELECT);

            child.close();

            assertEquals(0, driver.openCursors());
            cursor.close();
            assertEquals(1L, scope.selectOne(SELECT).get().get("id"));
            scope.cursor(SELECT);
            scope.commit();
            assertEquals(0, driver.openCursors());
        }
        assertEquals(0, driver.openSessions());
    }

    @Test
    void committingANestedSavepointScopeClosesTheCursorLeftOpenInIt() {
        try (TransactionScope scope = connection().beginTransaction()) {
            TransactionScope child = scope.beginTransaction();
            TransactionScope grandchild = child.beginTransaction();
            grandchild.cursor(SELECT).iterator().next();

            grandchild.commit();

            assertEquals(0, driver.openCursors());
            assertEquals(1L, child.selectOne(SELECT).get().get("id"));
            child.commit();
            scope.commit();
        }
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aDriverRefusalOffTheOwnerThreadLeavesTheCursorForItsOwner() throws Exception {
        driver.cursorsOwnedByTheirThread();
        QueryCursor<Map<String, Object>> cursor = connection().cursor(SELECT);
        Iterator<Map<String, Object>> rows = cursor.iterator();
        List<Class<?>> refusals = new ArrayList<>();
        Thread other = new Thread(() -> {
            for (Runnable use : Arrays.<Runnable>asList(rows::hasNext, rows::next, cursor::close)) {
                try {
                    use.run();
                } catch (IllegalStateException refused) {
                    refusals.add(refused.getClass());
                }
            }
        });
        other.start();
        other.join();

        assertEquals(3, refusals.size());
        assertEquals(1, driver.openCursors());
        assertEquals(1, driver.openSessions());
        assertEquals(1L, rows.next().get("id"));
        cursor.close();
        assertEquals(0, driver.openCursors());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aSessionCursorBorrowsTheSessionAndIsClosedWhenTheSessionEnds() {
        connection().session(held -> {
            QueryCursor<Map<String, Object>> cursor = held.cursor(SELECT);
            assertThrows(IllegalStateException.class, () -> held.select(SELECT));
            assertThrows(IllegalStateException.class, () -> held.transaction(tx -> null));
            cursor.iterator().next();
            return null;
        });

        List<String> calls = driver.calls();
        assertEquals(1, driver.sessionsOpened());
        assertTrue(calls.indexOf("cursorClose") < calls.indexOf("close"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aCursorOpenedInASessionBlocksATransactionStartedEarlierOnIt() {
        connection().session(held -> held.transaction(tx -> {
            QueryCursor<Map<String, Object>> cursor = held.cursor(SELECT);
            assertThrows(IllegalStateException.class, () -> tx.select(SELECT));
            cursor.close();
            return tx.select(SELECT);
        }));

        assertEquals(0, driver.openSessions());
    }

    @Test
    void pretendModeRefusesACursor() {
        connection().pretend(fake -> assertThrows(UnsupportedOperationException.class, () -> fake.cursor(SELECT)));
    }

    @Test
    void theRootConnectionRefusesACursorInsideItsOwnTransaction() {
        Connection connection = connection();

        connection.transaction(tx -> assertThrows(IllegalStateException.class, () -> connection.cursor(SELECT)));
    }

    @Test
    void bindingsAreCheckedBeforeASessionOpens() {
        assertThrows(IllegalArgumentException.class, () -> connection().cursor(SELECT, new Object()));
        assertThrows(NullPointerException.class, () -> connection().cursor(null));
        assertEquals(0, driver.sessionsOpened());
    }

    /** A driver that predates cursors: it keeps the interface's default. */
    private static final class PlainDriver implements org.aristonis.diwan.database.driver.Driver {

        @Override
        public DriverSession open() {
            return new DriverSession() {
                @Override
                public List<Map<String, Object>> query(String sql, List<Object> bindings) {
                    return new ArrayList<>();
                }

                @Override
                public int update(String sql, List<Object> bindings) {
                    return 0;
                }

                @Override
                public boolean execute(String sql, List<Object> bindings) {
                    return false;
                }

                @Override
                public void executeUnprepared(String sql) {
                }

                @Override
                public boolean inExternalTransaction() {
                    return false;
                }

                @Override
                public void begin() {
                }

                @Override
                public void commit() {
                }

                @Override
                public void rollback() {
                }

                @Override
                public void savepoint(String name) {
                }

                @Override
                public void rollbackToSavepoint(String name) {
                }

                @Override
                public void releaseSavepoint(String name) {
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public org.aristonis.diwan.database.driver.DriverInfo info() {
            return new org.aristonis.diwan.database.driver.DriverInfo("Plain", "1",
                    java.util.EnumSet.noneOf(org.aristonis.diwan.database.driver.DriverCapability.class));
        }
    }

    @Test
    void driverExceptionTypeIsNotLeakedFromIteration() {
        driver.cursorFailsAtRow(0, failure("XX000"));
        Iterator<Map<String, Object>> rows = connection().cursor(SELECT).iterator();

        RuntimeException error = assertThrows(RuntimeException.class, rows::hasNext);

        assertFalse(error instanceof DriverException);
        assertTrue(error.getCause() instanceof DriverException);
    }
}
