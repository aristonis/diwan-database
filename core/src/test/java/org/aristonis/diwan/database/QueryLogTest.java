package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bounded query log: limit, switches, snapshot and the compact form of large bindings. */
class QueryLogTest {

    private static final int DEFAULT_LIMIT = 1000;
    private static final int LONG_TEXT = 2000;
    private static final int TEXT_LIMIT = 1024;

    private final FakeDriver driver = new FakeDriver();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    private Connection connectionShowingBindings() {
        return Connection.builder().name("main").driver(driver).showBindingsInErrors(true).build();
    }

    @Test
    void dropsTheOldestEntriesOverTheConfiguredLimit() {
        Connection db = Connection.builder().name("main").driver(driver).queryLogLimit(3).build();
        db.events().enableQueryLog();

        for (int i = 1; i <= 5; i++) {
            db.select("select " + i);
        }

        List<LoggedQuery> log = db.events().getQueryLog();
        assertEquals(3, log.size());
        assertEquals(Arrays.asList("select 3", "select 4", "select 5"),
                Arrays.asList(log.get(0).sql(), log.get(1).sql(), log.get(2).sql()));
    }

    @Test
    void keepsAtMostAThousandEntriesByDefault() {
        Connection db = connection();
        db.events().enableQueryLog();

        for (int i = 0; i < DEFAULT_LIMIT + 5; i++) {
            db.select("select " + i);
        }

        List<LoggedQuery> log = db.events().getQueryLog();
        assertEquals(DEFAULT_LIMIT, log.size());
        assertEquals("select 5", log.get(0).sql());
    }

    @Test
    void refusesALimitBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> Connection.builder().queryLogLimit(0));
        assertThrows(IllegalArgumentException.class, () -> Connection.builder().queryLogLimit(-1));
    }

    @Test
    void isOffUntilEnabled() {
        Connection db = connection();

        db.select("select 1");

        assertFalse(db.events().isLoggingQueries());
        assertTrue(db.events().getQueryLog().isEmpty());
    }

    @Test
    void recordsStatementsWhileEnabledWithTheirBindings() {
        Connection db = connectionShowingBindings();
        db.events().enableQueryLog();

        db.insert("insert into t values (?, ?)", 1, "a");

        assertTrue(db.events().isLoggingQueries());
        List<LoggedQuery> log = db.events().getQueryLog();
        assertEquals(1, log.size());
        assertEquals("insert into t values (?, ?)", log.get(0).sql());
        assertEquals(Arrays.<Object>asList(1, "a"), log.get(0).bindings());
    }

    @Test
    void stopsRecordingWhenDisabled() {
        Connection db = connection();
        db.events().enableQueryLog();
        db.select("select 1");

        db.events().disableQueryLog();
        db.select("select 2");

        assertFalse(db.events().isLoggingQueries());
        assertFalse(db.events().getQueryLog().stream().anyMatch(q -> q.sql().equals("select 2")));
    }

    @Test
    void flushEmptiesTheLogButKeepsLogging() {
        Connection db = connection();
        db.events().enableQueryLog();
        db.select("select 1");

        db.events().flushQueryLog();
        db.select("select 2");

        assertTrue(db.events().isLoggingQueries());
        assertEquals(1, db.events().getQueryLog().size());
        assertEquals("select 2", db.events().getQueryLog().get(0).sql());
    }

    @Test
    void returnsAnImmutableSnapshot() {
        Connection db = connection();
        db.events().enableQueryLog();
        db.select("select 1");

        List<LoggedQuery> snapshot = db.events().getQueryLog();
        db.select("select 2");

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new LoggedQuery("x", Arrays.asList())));
        assertEquals(1, snapshot.size());
        assertEquals(2, db.events().getQueryLog().size());
    }

    @Test
    void logsStatementsRunInsideATransaction() {
        Connection db = connection();
        db.events().enableQueryLog();

        db.transaction(tx -> tx.insert("insert into t values (1)"));

        assertEquals(1, db.events().getQueryLog().size());
    }

    @Test
    void doesNotLogAFailedStatement() {
        driver.failsOn("select broken", FakeDriver.failure("42000"));
        Connection db = connection();
        db.events().enableQueryLog();

        assertThrows(QueryException.class, () -> db.select("select broken"));

        assertTrue(db.events().getQueryLog().isEmpty());
    }

    @Test
    void storesABlobAsItsLengthInsteadOfItsBytes() {
        Connection db = connectionShowingBindings();
        db.events().enableQueryLog();

        db.insert("insert into t values (?)", (Object) new byte[] {1, 2, 3});

        assertEquals("<byte[3]>", db.events().getQueryLog().get(0).bindings().get(0));
    }

    @Test
    void storesALongStringCutWithItsOriginalLength() {
        Connection db = connectionShowingBindings();
        db.events().enableQueryLog();
        String text = "a".repeat(LONG_TEXT);

        db.insert("insert into t values (?)", text);

        String stored = (String) db.events().getQueryLog().get(0).bindings().get(0);
        assertTrue(stored.startsWith("a".repeat(100)));
        assertTrue(stored.endsWith("...(2000 chars)"), stored.substring(Math.max(0, stored.length() - 40)));
        assertTrue(stored.length() < LONG_TEXT);
    }

    @Test
    void keepsAStringOfExactlyTheLimitAndOrdinaryValuesAsTheyAre() {
        Connection db = connectionShowingBindings();
        db.events().enableQueryLog();
        String text = "b".repeat(TEXT_LIMIT);

        db.insert("insert into t values (?, ?, ?)", text, 42, "short");

        assertEquals(Arrays.<Object>asList(text, 42, "short"), db.events().getQueryLog().get(0).bindings());
    }

    @Test
    void theListenersEventKeepsTheRawBindingsEvenThoughTheLogIsCompact() {
        Connection db = connection();
        RecordingListener listener = new RecordingListener();
        db.events().addListener(listener);
        db.events().enableQueryLog();
        String text = "c".repeat(LONG_TEXT);

        db.insert("insert into t values (?)", text);

        assertEquals(text, listener.executed().get(0).bindings().get(0));
    }

    @Test
    void theLogEntryTextHidesBindingsByDefault() {
        Connection db = connection();
        db.events().enableQueryLog();

        db.insert("insert into t values (?)", "secret-value");

        String text = db.events().getQueryLog().get(0).toString();
        assertFalse(text.contains("secret-value"), text);
    }

    @Test
    void storesAQuestionMarkForEachBindingByDefault() {
        Connection db = connection();
        db.events().enableQueryLog();

        db.insert("insert into t values (?, ?)", 1, "secret-value");

        assertEquals(Arrays.<Object>asList("?", "?"), db.events().getQueryLog().get(0).bindings());
    }
}
