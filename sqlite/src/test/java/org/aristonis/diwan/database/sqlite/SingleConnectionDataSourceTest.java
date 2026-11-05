package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SingleConnectionDataSourceTest {

    private static final String URL = "jdbc:sqlite::memory:";
    private static final long SHORT_TIMEOUT_MILLIS = 100;
    private static final long LONG_TIMEOUT_MILLIS = 10_000;
    private static final int WAIT_SECONDS = 10;

    @Test
    void dataSurvivesAcrossSessions() throws SQLException {
        try (SingleConnectionDataSource source = open(LONG_TIMEOUT_MILLIS)) {
            try (Connection first = source.getConnection(); Statement st = first.createStatement()) {
                st.execute("create table t (id integer)");
                st.execute("insert into t values (1)");
            }

            try (Connection second = source.getConnection(); Statement st = second.createStatement();
                    ResultSet rows = st.executeQuery("select count(*) from t")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void closingTheHandleKeepsTheRealConnectionOpen() throws SQLException {
        try (SingleConnectionDataSource source = open(LONG_TIMEOUT_MILLIS)) {
            Connection handle = source.getConnection();

            handle.close();

            assertTrue(handle.isClosed());
            assertThrows(SQLException.class, handle::createStatement);
            try (Connection again = source.getConnection()) {
                assertFalse(again.isClosed());
            }
        }
    }

    @Test
    void aSecondSessionWaitsUntilTheFirstCloses() throws Exception {
        try (SingleConnectionDataSource source = open(LONG_TIMEOUT_MILLIS)) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Connection first = source.getConnection();
                Future<Boolean> second = pool.submit(() -> {
                    try (Connection c = source.getConnection()) {
                        return !c.isClosed();
                    }
                });

                Thread.sleep(SHORT_TIMEOUT_MILLIS);
                assertFalse(second.isDone());
                first.close();

                assertTrue(second.get(WAIT_SECONDS, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aSessionThatWaitsPastTheTimeoutFailsWithTheBusyMessage() throws SQLException {
        try (SingleConnectionDataSource source = open(SHORT_TIMEOUT_MILLIS); Connection held = source.getConnection()) {
            SQLException e = assertThrows(SQLException.class, source::getConnection);

            assertEquals("In-memory SQLite database [mem] is busy: one session at a time", e.getMessage());
            assertFalse(held.isClosed());
        }
    }

    @Test
    void closingTheSourceClosesTheRealConnection() throws SQLException {
        Connection underlying = DriverManager.getConnection(URL);
        SingleConnectionDataSource source = new SingleConnectionDataSource("mem", underlying, SHORT_TIMEOUT_MILLIS);

        source.close();

        assertTrue(underlying.isClosed());
        assertThrows(SQLException.class, source::getConnection);
    }

    @Test
    void aSessionEndedMidTransactionIsRolledBack() throws SQLException {
        try (SingleConnectionDataSource source = open(LONG_TIMEOUT_MILLIS)) {
            try (Connection first = source.getConnection(); Statement st = first.createStatement()) {
                st.execute("create table t (id integer)");
                first.setAutoCommit(false);
                st.execute("insert into t values (1)");
            }

            try (Connection second = source.getConnection(); Statement st = second.createStatement();
                    ResultSet rows = st.executeQuery("select count(*) from t")) {
                assertTrue(second.getAutoCommit());
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    private static SingleConnectionDataSource open(long timeoutMillis) throws SQLException {
        return new SingleConnectionDataSource("mem", DriverManager.getConnection(URL), timeoutMillis);
    }
}
