package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A listener belongs to a connection family: the root, its sessions and its transactions share it. */
class ListenerScopeTest {

    private final FakeDriver driver = new FakeDriver();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void aListenerAddedOnTheRootSeesQueriesRunInsideATransaction() {
        Connection db = connection();
        RecordingListener listener = new RecordingListener();
        db.events().addListener(listener);

        db.transaction(tx -> tx.insert("insert into t values (1)"));

        assertEquals(Arrays.asList("insert into t values (1)"), listener.executedSql());
        assertEquals(1, listener.executed().get(0).transactionLevel());
    }

    @Test
    void aListenerAddedOnTheRootSeesQueriesRunInsideASession() {
        Connection db = connection();
        RecordingListener listener = new RecordingListener();
        db.events().addListener(listener);

        db.session(s -> s.select("select 1"));

        assertEquals(Arrays.asList("select 1"), listener.executedSql());
    }

    @Test
    void aListenerAddedOnTheRootSeesQueriesRunInsideATransactionScope() {
        Connection db = connection();
        RecordingListener listener = new RecordingListener();
        db.events().addListener(listener);

        try (TransactionScope tx = db.beginTransaction()) {
            tx.insert("insert into t values (1)");
            tx.commit();
        }

        assertEquals(Arrays.asList("insert into t values (1)"), listener.executedSql());
    }

    @Test
    void aListenerAddedOnATransactionIsTheSameFamilyAsTheRoot() {
        Connection db = connection();
        RecordingListener listener = new RecordingListener();

        db.transaction(tx -> {
            tx.events().addListener(listener);
            return null;
        });
        db.select("select 1");

        assertEquals(Arrays.asList("select 1"), listener.executedSql());
    }

    @Test
    void aListenerGivenToTheBuilderHearsTheVeryFirstStatement() {
        RecordingListener listener = new RecordingListener();
        Connection db = Connection.builder().name("main").driver(driver).listener(listener).build();

        db.statement("create table t (a integer)");

        assertEquals(Arrays.asList("create table t (a integer)"), listener.executedSql());
    }

    @Test
    void connectionsBuiltSeparatelyDoNotShareListeners() {
        Connection first = connection();
        Connection second = connection();
        RecordingListener listener = new RecordingListener();
        first.events().addListener(listener);

        second.select("select 1");

        assertTrue(listener.executed().isEmpty());
    }

    @Test
    void severalListenersAllHearEachEventInTheOrderTheyWereAdded() {
        Connection db = connection();
        List<String> order = new ArrayList<>();
        db.events().listen(e -> order.add("first"));
        db.events().listen(e -> order.add("second"));

        db.select("select 1");

        assertEquals(Arrays.asList("first", "second"), order);
    }

    @Test
    void aRemovedListenerHearsNothingMore() {
        Connection db = connection();
        List<String> seen = new ArrayList<>();
        Registration registration = db.events().listen(e -> seen.add(e.sql()));
        db.select("select 1");

        registration.remove();
        db.select("select 2");

        assertEquals(Arrays.asList("select 1"), seen);
    }

    @Test
    void removingAListenerTwiceDoesNothing() {
        Connection db = connection();
        Registration registration = db.events().listen(e -> { });

        registration.remove();
        registration.remove();

        assertTrue(!db.events().isActive());
    }
}
