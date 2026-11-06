package org.aristonis.diwan.database;

import org.aristonis.diwan.database.event.ConnectionListener;
import org.aristonis.diwan.database.event.QueryFailed;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.FakeDriver;
import org.aristonis.diwan.database.QueryKind;
import org.aristonis.diwan.database.RecordingListener;
import org.aristonis.diwan.database.exception.QueryException;
import static org.aristonis.diwan.database.FakeDriver.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.exception.DriverException;
import org.junit.jupiter.api.Test;

/** What a failed statement reports to a listener, and how a broken listener leaves the caller's error. */
class QueryFailedTest {

    private final FakeDriver driver = new FakeDriver();
    private final RecordingListener listener = new RecordingListener();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).listener(listener).build();
    }

    @Test
    void firesOnceWithTheExceptionTheCallerReceives() {
        driver.failsOn("update t set a = ?", failure("42000"));

        QueryException thrown = assertThrows(QueryException.class, () -> connection().update("update t set a = ?", 4));

        List<QueryFailed> events = listener.failed();
        assertEquals(1, events.size());
        assertSame(thrown, events.get(0).exception());
    }

    @Test
    void describesTheFailedStatement() {
        driver.failsOn("delete from t where a = ?", failure("23000"));
        Connection db = connection();

        db.transaction(tx -> {
            assertThrows(QueryException.class, () -> tx.delete("delete from t where a = ?", 9));
            return null;
        });

        QueryFailed event = listener.failed().get(0);
        assertEquals("main", event.connectionName());
        assertEquals(QueryKind.DELETE, event.kind());
        assertEquals("delete from t where a = ?", event.sql());
        assertEquals(Arrays.<Object>asList(9), event.bindings());
        assertEquals(1, event.transactionLevel());
        assertTrue(!event.time().isNegative());
    }

    @Test
    void firesNoExecutedEventForTheSameStatement() {
        driver.failsOn("select 1", failure("42000"));

        assertThrows(QueryException.class, () -> connection().select("select 1"));

        assertTrue(listener.executed().isEmpty());
        assertEquals(1, listener.failed().size());
    }

    @Test
    void aFailingListenerDoesNotReplaceTheOriginalQueryException() {
        driver.failsOn("select 1", failure("42000"));
        IllegalStateException listenerError = new IllegalStateException("listener broke");
        Connection db = Connection.builder().name("main").driver(driver)
                .listener(new ConnectionListener() {
                    @Override
                    public void queryFailed(QueryFailed event) {
                        throw listenerError;
                    }
                }).build();

        QueryException thrown = assertThrows(QueryException.class, () -> db.select("select 1"));

        assertTrue(Arrays.asList(thrown.getSuppressed()).contains(listenerError));
    }

    @Test
    void theEventKeepsTheExceptionItWasBuiltWith() {
        DriverException cause = failure("42000");
        QueryException exception = new QueryException("main", "select 1", Arrays.asList(), cause, false);

        QueryFailed event = new QueryFailed("main", QueryKind.SELECT, "select 1", Arrays.asList(), Duration.ZERO, 0,
                exception);

        assertSame(exception, event.exception());
        assertEquals(QueryKind.SELECT, event.kind());
    }
}
