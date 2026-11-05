package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** SQL run from inside a listener fires no events of its own and never lands in the query log. */
class ListenerReentrancyTest {

    private final FakeDriver driver = new FakeDriver();
    private final Connection db = Connection.builder().name("main").driver(driver).build();

    @Test
    void aListenerRunningSqlOnTheSameFamilyCausesNoNestedEvents() {
        List<String> seen = new ArrayList<>();
        db.events().listen(e -> {
            seen.add(e.sql());
            db.select("select 'from listener'");
        });

        db.select("select 1");

        assertEquals(Arrays.asList("select 1"), seen);
        assertTrue(driver.calls().contains("execute select 'from listener' []")
                || driver.calls().contains("query select 'from listener' []"), driver.calls().toString());
    }

    @Test
    void aListenerRunningSqlIsNotWrittenToTheQueryLog() {
        db.events().enableQueryLog();
        db.events().listen(e -> db.select("select 'from listener'"));

        db.select("select 1");

        List<LoggedQuery> log = db.events().getQueryLog();
        assertEquals(1, log.size());
        assertEquals("select 1", log.get(0).sql());
    }

    @Test
    void aListenerRunningSqlOnTheTransactionConnectionCausesNoNestedEvents() {
        AtomicReference<Connection> current = new AtomicReference<>();
        List<String> seen = new ArrayList<>();
        db.events().listen(e -> {
            seen.add(e.sql());
            current.get().select("select 'from listener'");
        });

        db.transaction(tx -> {
            current.set(tx);
            return tx.insert("insert into t values (1)");
        });

        assertEquals(Arrays.asList("insert into t values (1)"), seen);
    }

    @Test
    void aSlowQueryHandlerRunningSqlCausesNoNestedEvents() {
        List<String> seen = new ArrayList<>();
        db.events().listen(e -> seen.add(e.sql()));
        db.events().whenQueryTakesLongerThan(Duration.ZERO, e -> db.select("select 'from handler'"));

        db.select("select 1");

        assertEquals(Arrays.asList("select 1"), seen);
    }

    @Test
    void firesEventsAgainForTheNextStatementOnceTheListenerHasReturned() {
        List<String> seen = new ArrayList<>();
        db.events().listen(e -> {
            seen.add(e.sql());
            db.select("select 'from listener'");
        });

        db.select("select 1");
        db.select("select 2");

        assertEquals(Arrays.asList("select 1", "select 2"), seen);
    }

    @Test
    void reportsDispatchingOnlyWhileTheListenerRuns() {
        List<Boolean> during = new ArrayList<>();
        db.events().listen(e -> during.add(db.events().isDispatching()));

        db.select("select 1");

        assertEquals(Arrays.asList(true), during);
        assertTrue(!db.events().isDispatching());
    }
}
