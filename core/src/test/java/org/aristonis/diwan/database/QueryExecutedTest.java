package org.aristonis.diwan.database;

import org.aristonis.diwan.database.event.ConnectionListener;
import org.aristonis.diwan.database.event.QueryExecuted;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.FakeDriver;
import org.aristonis.diwan.database.QueryKind;
import org.aristonis.diwan.database.RecordingListener;
import org.aristonis.diwan.database.exception.ListenerFailedException;
import org.aristonis.diwan.database.exception.QueryException;
import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.query.QueryCursor;
import org.junit.jupiter.api.Test;

/** What a successful statement reports to a listener, and what the event does with its data. */
class QueryExecutedTest {

    private final FakeDriver driver = new FakeDriver();
    private final RecordingListener listener = new RecordingListener();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).listener(listener).build();
    }

    private QueryExecuted only() {
        List<QueryExecuted> events = listener.executed();
        assertEquals(1, events.size(), events.toString());
        return events.get(0);
    }

    @Test
    void reportsASelectWithItsSqlBindingsAndConnectionName() {
        connection().select("select * from t where a = ?", 5);

        QueryExecuted event = only();
        assertEquals(QueryKind.SELECT, event.kind());
        assertEquals("select * from t where a = ?", event.sql());
        assertEquals(Arrays.<Object>asList(5), event.bindings());
        assertEquals("main", event.connectionName());
        assertEquals(0, event.transactionLevel());
        assertFalse(event.time().isNegative());
    }

    @Test
    void reportsASelectOneAsItsOwnKind() {
        connection().selectOne("select * from t limit 1");

        assertEquals(QueryKind.SELECT_ONE, only().kind());
    }

    @Test
    void reportsAScalarAsItsOwnKind() {
        connection().scalar("select count(*) from t");

        assertEquals(QueryKind.SCALAR, only().kind());
    }

    @Test
    void reportsAnInsert() {
        connection().insert("insert into t values (?)", 1);

        assertEquals(QueryKind.INSERT, only().kind());
    }

    @Test
    void reportsAnUpdate() {
        connection().update("update t set a = ?", 2);

        assertEquals(QueryKind.UPDATE, only().kind());
    }

    @Test
    void reportsADelete() {
        connection().delete("delete from t where a = ?", 3);

        assertEquals(QueryKind.DELETE, only().kind());
    }

    @Test
    void reportsAStatement() {
        connection().statement("create table t (a integer)");

        assertEquals(QueryKind.STATEMENT, only().kind());
    }

    @Test
    void reportsAnAffectingStatement() {
        connection().affectingStatement("update t set a = 1");

        assertEquals(QueryKind.AFFECTING, only().kind());
    }

    @Test
    void reportsAnUnpreparedStatementAsAStatement() {
        connection().unprepared("vacuum");

        QueryExecuted event = only();
        assertEquals(QueryKind.STATEMENT, event.kind());
        assertEquals("vacuum", event.sql());
    }

    @Test
    void reportsACursorOnceItIsOpened() {
        try (QueryCursor<Map<String, Object>> cursor = connection().cursor("select * from t where a = ?", 7)) {
            assertNotNull(cursor);
            assertEquals(1, listener.executed().size());
        }

        QueryExecuted event = only();
        assertEquals(QueryKind.CURSOR, event.kind());
        assertEquals(Arrays.<Object>asList(7), event.bindings());
    }

    @Test
    void firesOneEventPerStatementInOrder() {
        Connection db = connection();

        db.insert("insert into t values (1)");
        db.select("select * from t");
        db.delete("delete from t");

        assertEquals(Arrays.asList("insert into t values (1)", "select * from t", "delete from t"),
                listener.executedSql());
    }

    @Test
    void carriesTheTransactionLevelOfTheStatement() {
        Connection db = connection();

        db.transaction(tx -> {
            tx.insert("insert into t values (1)");
            tx.transaction(inner -> inner.insert("insert into t values (2)"));
            return null;
        });
        db.insert("insert into t values (3)");

        List<QueryExecuted> events = listener.executed();
        assertEquals(Arrays.asList(1, 2, 0), Arrays.asList(
                events.get(0).transactionLevel(), events.get(1).transactionLevel(), events.get(2).transactionLevel()));
    }

    @Test
    void firesNoEventForAStatementThatFails() {
        driver.failsOn("select * from broken", failure("42000"));

        assertThrows(QueryException.class, () -> connection().select("select * from broken"));

        assertTrue(listener.executed().isEmpty());
    }

    @Test
    void copiesTheBindingsSoLaterChangesToTheCallersListDoNotShowThrough() {
        List<Object> bindings = new ArrayList<>(Arrays.<Object>asList(1, "a"));
        QueryExecuted event = new QueryExecuted("main", QueryKind.SELECT, "select 1", bindings, Duration.ZERO, 0);

        bindings.set(0, 99);

        assertEquals(Arrays.<Object>asList(1, "a"), event.bindings());
    }

    @Test
    void redactedReplacesEveryBindingWithAQuestionMarkAndKeepsTheRest() {
        QueryExecuted event = new QueryExecuted("main", QueryKind.INSERT, "insert into t values (?, ?, ?)",
                Arrays.<Object>asList(1, "secret", new byte[] {1, 2}), Duration.ofMillis(5), 2);

        QueryExecuted redacted = event.redacted();

        assertEquals(Arrays.<Object>asList("?", "?", "?"), redacted.bindings());
        assertEquals(event.sql(), redacted.sql());
        assertEquals(event.kind(), redacted.kind());
        assertEquals(event.connectionName(), redacted.connectionName());
        assertEquals(event.time(), redacted.time());
        assertEquals(2, redacted.transactionLevel());
    }

    @Test
    void redactedLeavesTheOriginalEventUntouched() {
        byte[] blob = {1, 2, 3};
        QueryExecuted event = new QueryExecuted("main", QueryKind.INSERT, "insert into t values (?)",
                Arrays.<Object>asList(blob), Duration.ZERO, 0);

        QueryExecuted redacted = event.redacted();

        assertNotSame(event, redacted);
        assertArrayEquals(blob, (byte[]) event.bindings().get(0));
    }

    @Test
    void redactedOfAnEventWithoutBindingsHasNoBindings() {
        QueryExecuted event = new QueryExecuted("main", QueryKind.STATEMENT, "vacuum", Arrays.asList(),
                Duration.ZERO, 0);

        assertTrue(event.redacted().bindings().isEmpty());
    }

    @Test
    void wrapsAListenerFailureAfterALevelZeroInsertAndSaysTheStatementSucceeded() {
        IllegalStateException boom = new IllegalStateException("listener broke");
        Connection db = Connection.builder().name("main").driver(driver)
                .listener(new ConnectionListener() {
                    @Override
                    public void queryExecuted(QueryExecuted event) {
                        throw boom;
                    }
                }).build();

        ListenerFailedException error = assertThrows(ListenerFailedException.class,
                () -> db.insert("insert into t values (1)"));

        assertSame(boom, error.getCause());
        assertTrue(error.getMessage().contains("succeeded"), error.getMessage());
        assertTrue(driver.calls().contains("execute insert into t values (1) []"));
        assertEquals(0, driver.openSessions());
    }

    @Test
    void aListenerFailureIsAnUncheckedException() {
        assertTrue(RuntimeException.class.isAssignableFrom(ListenerFailedException.class));
    }
}
