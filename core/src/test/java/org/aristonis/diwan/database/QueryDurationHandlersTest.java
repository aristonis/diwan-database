package org.aristonis.diwan.database;

import org.aristonis.diwan.database.event.ConnectionEvents;
import org.aristonis.diwan.database.event.ConnectionListener;
import org.aristonis.diwan.database.event.QueryExecuted;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Several total-time handlers on one connection, and a handler that reads the query log. */
class QueryDurationHandlersTest {

    private static ConnectionEvents events() {
        return new ConnectionEvents(10, Collections.<ConnectionListener>emptyList());
    }

    private static QueryExecuted query(String sql, Duration time) {
        return new QueryExecuted("main", QueryKind.SELECT, sql, new ArrayList<>(), time, 0);
    }

    @Test
    void severalTotalTimeHandlersWithTheSameThresholdEachFireOnce() {
        ConnectionEvents events = events();
        List<String> fired = new ArrayList<>();
        events.whenQueryingForLongerThan(Duration.ofMillis(5), (connection, e) -> fired.add("one"));
        events.whenQueryingForLongerThan(Duration.ofMillis(5), (connection, e) -> fired.add("two"));

        events.executed(null, query("a", Duration.ofMillis(4)));
        events.executed(null, query("b", Duration.ofMillis(4)));
        events.executed(null, query("c", Duration.ofMillis(4)));

        assertEquals(Arrays.asList("one", "two"), fired);
    }

    @Test
    void severalTotalTimeHandlersWithDifferentThresholdsFireWhenTheirOwnThresholdIsPassed() {
        ConnectionEvents events = events();
        List<String> fired = new ArrayList<>();
        events.whenQueryingForLongerThan(Duration.ofMillis(5), (connection, e) -> fired.add("short:" + e.sql()));
        events.whenQueryingForLongerThan(Duration.ofMillis(20), (connection, e) -> fired.add("long:" + e.sql()));

        events.executed(null, query("a", Duration.ofMillis(3)));
        events.executed(null, query("b", Duration.ofMillis(3)));
        events.executed(null, query("c", Duration.ofMillis(10)));
        events.executed(null, query("d", Duration.ofMillis(10)));

        assertEquals(Arrays.asList("short:b", "long:d"), fired);
    }

    @Test
    void aTotalTimeHandlerCanReadTheWholeQueryLog() {
        ConnectionEvents events = events();
        events.enableQueryLog();
        List<Integer> loggedWhenFired = new ArrayList<>();
        events.whenQueryingForLongerThan(Duration.ofMillis(5),
                (connection, e) -> loggedWhenFired.add(events.getQueryLog().size()));

        events.executed(null, query("a", Duration.ofMillis(3)));
        events.executed(null, query("b", Duration.ofMillis(3)));

        assertEquals(Arrays.asList(2), loggedWhenFired);
    }
}
