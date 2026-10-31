package org.aristonis.photisma.database;

import static org.aristonis.photisma.database.FakeDriver.failure;
import static org.aristonis.photisma.database.FakeDriver.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.photisma.database.driver.DriverException;
import org.aristonis.photisma.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;

class RootConnectionTest {

    private final FakeDriver driver = new FakeDriver();
    private final List<Throwable> cleanupFailures = new ArrayList<>();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).onCleanupFailure(cleanupFailures::add).build();
    }

    @Test
    void runsAStatementOnABorrowedSessionAndGivesItBack() {
        boolean ran = connection().statement("create table t (id integer)");

        assertTrue(ran);
        assertEquals(Arrays.asList("open", "execute create table t (id integer) []", "close"), driver.calls());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void returnsAffectedRowsForUpdateDeleteAndAffectingStatement() {
        driver.affects("update t set a = ?", 3).affects("delete from t", 2).affects("update t set b = 1", 5);
        Connection connection = connection();

        assertEquals(3, connection.update("update t set a = ?", 1));
        assertEquals(2, connection.delete("delete from t"));
        assertEquals(5, connection.affectingStatement("update t set b = 1"));
    }

    @Test
    void insertsAndReportsThatItRan() {
        assertTrue(connection().insert("insert into t values (?)", 1));
        assertTrue(driver.calls().contains("execute insert into t values (?) [1]"));
    }

    @Test
    void selectsRowsInOrderAsUnmodifiableMaps() {
        driver.returns("select * from t", Arrays.asList(row("id", 1, "name", "a"), row("id", 2, "name", "b")));

        List<Map<String, Object>> rows = connection().select("select * from t");

        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("id", "name"), new ArrayList<>(rows.get(0).keySet()));
        assertEquals("b", rows.get(1).get("name"));
        assertThrows(UnsupportedOperationException.class, () -> rows.add(row()));
        assertThrows(UnsupportedOperationException.class, () -> rows.get(0).put("id", 9));
    }

    @Test
    void selectOneReturnsTheFirstRowOrNothing() {
        driver.returns("select * from t", Arrays.asList(row("id", 1), row("id", 2)));
        Connection connection = connection();

        assertEquals(1, connection.selectOne("select * from t").orElseThrow().get("id"));
        assertFalse(connection.selectOne("select * from empty").isPresent());
    }

    @Test
    void scalarReturnsTheFirstValueOrNothing() {
        driver.returns("select count(*) from t", Arrays.asList(row("count", 4L)))
                .returns("select max(a) from t", Arrays.asList(row("max", null)));
        Connection connection = connection();

        assertEquals(4L, connection.scalar("select count(*) from t").orElseThrow());
        assertFalse(connection.scalar("select max(a) from t").isPresent());
        assertFalse(connection.scalar("select a from empty").isPresent());
    }

    @Test
    void scalarRefusesARowWithSeveralColumns() {
        driver.returns("select a, b from t", Arrays.asList(row("a", 1, "b", 2)));

        assertThrows(MultipleColumnsSelectedException.class, () -> connection().scalar("select a, b from t"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void runsUnpreparedSql() {
        assertTrue(connection().unprepared("pragma foreign_keys = on"));
        assertTrue(driver.calls().contains("unprepared pragma foreign_keys = on"));
    }

    @Test
    void acceptsBindingsAsAList() {
        Connection connection = connection();

        connection.statement("insert into t values (?, ?)", Arrays.<Object>asList(1, "a"));
        connection.statement("insert into t values (?, ?)", 1, "a");

        List<String> calls = driver.calls();
        assertEquals(calls.get(1), calls.get(4));
    }

    @Test
    void refusesANullBindingsArray() {
        Connection connection = connection();

        assertThrows(IllegalArgumentException.class, () -> connection.statement("select 1", (Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> connection.select("select 1", (List<?>) null));
        assertEquals(0, driver.sessionsOpened());
    }

    @Test
    void passesEverySupportedBindingTypeToTheDriver() {
        List<Object> values = Arrays.<Object>asList(null, true, (byte) 1, (short) 2, 3, 4L, 5.5f, 6.5d,
                new BigDecimal("7.25"), BigInteger.TEN, "text", new byte[] {1, 2}, LocalDate.of(2026, 10, 30),
                LocalTime.of(12, 0), LocalDateTime.of(2026, 10, 30, 12, 0),
                OffsetDateTime.of(2026, 10, 30, 12, 0, 0, 0, ZoneOffset.UTC), Instant.EPOCH);

        assertTrue(connection().statement("insert into all_types values (?)", values));
    }

    @Test
    void refusesAnUnsupportedBindingTypeBeforeOpeningASession() {
        Connection connection = connection();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> connection.statement("insert into t values (?)", new java.util.Date()));

        assertTrue(error.getMessage().contains("java.util.Date"));
        assertTrue(error.getMessage().contains("position 1"));
        assertEquals(0, driver.sessionsOpened());
    }

    @Test
    void wrapsADriverFailureWithTheSqlAndBindings() {
        DriverException cause = failure("42P01");
        driver.failsOn("select * from missing where id = ?", cause);

        QueryException error = assertThrows(QueryException.class,
                () -> connection().select("select * from missing where id = ?", 5));

        assertEquals("main", error.getConnectionName());
        assertEquals("select * from missing where id = ?", error.getSql());
        assertEquals(Arrays.<Object>asList(5), error.getBindings());
        assertSame(cause, error.getCause());
        assertEquals(0, driver.openSessions());
    }

    @Test
    void reportsAUniqueViolationAsItsOwnType() {
        driver.failsOn("insert into t values (?)", failure("23505", DriverFailureKind.UNIQUE_VIOLATION));

        assertThrows(UniqueConstraintViolationException.class, () -> connection().insert("insert into t values (?)", 1));
    }

    @Test
    void hidesBindingsInErrorsUnlessShowingIsSwitchedOn() {
        driver.failsOn("select * from u where email = ?", failure("42P01"));
        Connection showing = Connection.builder().name("main").driver(driver).showBindingsInErrors(true).build();

        QueryException hidden = assertThrows(QueryException.class,
                () -> connection().select("select * from u where email = ?", "ana@example.com"));
        QueryException shown = assertThrows(QueryException.class,
                () -> showing.select("select * from u where email = ?", "ana@example.com"));

        assertFalse(hidden.getMessage().contains("ana@example.com"));
        assertTrue(shown.getMessage().contains("ana@example.com"));
    }

    @Test
    void reportsAFailureToOpenASessionAsAQueryException() {
        driver.failsToOpen(failure("08001"));

        QueryException error = assertThrows(QueryException.class, () -> connection().statement("select 1"));

        assertEquals("select 1", error.getSql());
    }

    @Test
    void returnsTheResultWhenGivingTheSessionBackFails() {
        RuntimeException closeFailure = new IllegalStateException("network gone");
        driver.failsToClose(closeFailure);

        assertTrue(connection().statement("select 1"));
        assertEquals(Arrays.<Throwable>asList(closeFailure), cleanupFailures);
    }

    @Test
    void attachesACloseFailureToTheOriginalFailure() {
        driver.failsOn("select 1", failure("42000")).failsToClose(new IllegalStateException("network gone"));

        QueryException error = assertThrows(QueryException.class, () -> connection().statement("select 1"));

        assertEquals(1, error.getSuppressed().length);
    }

    @Test
    void refusesNullSql() {
        assertThrows(NullPointerException.class, () -> connection().statement(null));
    }

    @Test
    void reportsThatAStatementRanEvenWhenItProducedNoRows() {
        Connection connection = connection();

        assertTrue(connection.statement("create table t (id integer)"));
        assertTrue(connection.insert("insert into t values (1)"));
    }

    @Test
    void keepsItsOwnCopyOfTheRowsTheDriverReturned() {
        Map<String, Object> mutable = row("id", 1);
        driver.returns("select * from t", Arrays.asList(mutable));

        List<Map<String, Object>> rows = connection().select("select * from t");
        mutable.put("id", 99);

        assertEquals(1, rows.get(0).get("id"));
    }

    @Test
    void keepsItsOwnCopyOfTheCallersBindings() {
        driver.failsOn("insert into t values (?)", failure("42000"));
        byte[] key = {1, 2, 3};

        QueryException error = assertThrows(QueryException.class,
                () -> connection().insert("insert into t values (?)", (Object) key));
        key[0] = 9;

        assertEquals(1, ((byte[]) error.getBindings().get(0))[0]);
    }

    @Test
    void refusesASubclassOfASupportedType() {
        BigDecimal subclass = new BigDecimal("1") {
            private static final long serialVersionUID = 1L;
        };

        assertThrows(IllegalArgumentException.class, () -> connection().statement("select ?", subclass));
    }

    @Test
    void passesTheConcurrencyFlagThrough() {
        driver.failsOn("update t set a = 1", failure("40001", DriverFailureKind.CONCURRENCY));

        QueryException error = assertThrows(QueryException.class, () -> connection().update("update t set a = 1"));

        assertTrue(error.isConcurrencyFailure());
    }

    @Test
    void keepsItsOwnCopyOfTheCallersBindingList() {
        driver.failsOn("insert into t values (?, ?)", failure("42000"));
        List<Object> values = new ArrayList<>(Arrays.<Object>asList(1, "a"));

        QueryException error = assertThrows(QueryException.class,
                () -> connection().insert("insert into t values (?, ?)", values));
        values.clear();

        assertEquals(Arrays.<Object>asList(1, "a"), error.getBindings());
    }

    @Test
    void logsAFailureToGiveTheSessionBackAsAWarningByDefault() {
        driver.failsToClose(new IllegalStateException("network gone"));
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(ConnectionBuilder.class.getName());
        List<java.util.logging.LogRecord> records = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            assertTrue(Connection.builder().name("main").driver(driver).build().statement("select 1"));
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(1, records.size());
        assertEquals(java.util.logging.Level.WARNING, records.get(0).getLevel());
    }
}
