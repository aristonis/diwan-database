package org.aristonis.diwan.database;

import org.aristonis.diwan.database.event.QueryExecuted;
import org.aristonis.diwan.database.event.Registration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Slow-statement and total-time handlers. The fake driver sleeps for the slow statements, so the margins are wide:
 * a slow statement takes 300 ms against a 150 ms threshold, a fast one takes no time at all.
 */
class SlowQueryTest {

    private static final long SLOW_MILLIS = 300;
    private static final Duration THRESHOLD = Duration.ofMillis(150);
    private static final String SLOW = "select slow";
    private static final String FAST = "select fast";

    private final FakeDriver driver = new FakeDriver().takes(SLOW, SLOW_MILLIS);

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void firesTheHandlerForASlowStatement() {
        Connection db = connection();
        List<QueryExecuted> slow = new ArrayList<>();
        db.events().whenQueryTakesLongerThan(THRESHOLD, slow::add);

        db.select(SLOW);

        assertEquals(1, slow.size());
        assertEquals(SLOW, slow.get(0).sql());
        assertTrue(slow.get(0).time().compareTo(THRESHOLD) > 0, slow.get(0).time().toString());
    }

    @Test
    void doesNotFireTheHandlerForAFastStatement() {
        Connection db = connection();
        List<QueryExecuted> slow = new ArrayList<>();
        db.events().whenQueryTakesLongerThan(THRESHOLD, slow::add);

        db.select(FAST);

        assertTrue(slow.isEmpty());
    }

    @Test
    void firesOnlyForTheSlowOneOfTwoStatements() {
        Connection db = connection();
        List<String> slow = new ArrayList<>();
        db.events().whenQueryTakesLongerThan(THRESHOLD, e -> slow.add(e.sql()));

        db.select(FAST);
        db.select(SLOW);
        db.select(FAST);

        assertEquals(Arrays.asList(SLOW), slow);
    }

    @Test
    void firesTheHandlerForEveryRepeatedSlowStatement() {
        Connection db = connection();
        List<String> slow = new ArrayList<>();
        db.events().whenQueryTakesLongerThan(THRESHOLD, e -> slow.add(e.sql()));

        db.select(SLOW);
        db.select(SLOW);

        assertEquals(2, slow.size());
    }

    @Test
    void stopsFiringAfterTheRegistrationIsRemoved() {
        Connection db = connection();
        List<String> slow = new ArrayList<>();
        Registration registration = db.events().whenQueryTakesLongerThan(THRESHOLD, e -> slow.add(e.sql()));

        registration.remove();
        db.select(SLOW);

        assertTrue(slow.isEmpty());
    }

    @Test
    void firesTheTotalTimeHandlerOnceUntilTheTotalIsReset() {
        Connection db = connection();
        List<String> fired = new ArrayList<>();
        db.events().whenQueryingForLongerThan(THRESHOLD, (c, e) -> fired.add(e.sql()));

        db.select(SLOW);
        db.select(SLOW);

        assertEquals(1, fired.size());
    }

    @Test
    void firesTheTotalTimeHandlerAgainAfterTheTotalIsReset() {
        Connection db = connection();
        List<String> fired = new ArrayList<>();
        db.events().whenQueryingForLongerThan(THRESHOLD, (c, e) -> fired.add(e.sql()));
        db.select(SLOW);

        db.events().resetTotalQueryDuration();
        db.select(SLOW);

        assertEquals(2, fired.size());
    }

    @Test
    void addsUpTheTimeOfEveryStatementAndResetsToZero() {
        Connection db = connection();
        db.events().whenQueryingForLongerThan(Duration.ofHours(1), (c, e) -> { });

        db.select(SLOW);
        db.select(SLOW);

        assertTrue(db.events().totalQueryDuration().compareTo(Duration.ofMillis(2 * SLOW_MILLIS - 50)) > 0,
                db.events().totalQueryDuration().toString());
        db.events().resetTotalQueryDuration();
        assertEquals(Duration.ZERO, db.events().totalQueryDuration());
    }

    @Test
    void doesNotFireTheTotalTimeHandlerBelowTheThreshold() {
        Connection db = connection();
        List<String> fired = new ArrayList<>();
        db.events().whenQueryingForLongerThan(Duration.ofHours(1), (c, e) -> fired.add(e.sql()));

        db.select(SLOW);

        assertTrue(fired.isEmpty());
        assertFalse(db.events().totalQueryDuration().isNegative());
    }

    @Test
    void stopsFiringTheTotalTimeHandlerAfterTheRegistrationIsRemoved() {
        Connection db = connection();
        List<String> fired = new ArrayList<>();
        Registration registration = db.events().whenQueryingForLongerThan(THRESHOLD, (c, e) -> fired.add(e.sql()));

        registration.remove();
        db.select(SLOW);

        assertTrue(fired.isEmpty());
    }

    @Test
    void handsTheTotalTimeHandlerTheConnectionThatRanTheStatement() {
        Connection db = connection();
        AtomicReference<Connection> seen = new AtomicReference<>();
        db.events().whenQueryingForLongerThan(THRESHOLD, (c, e) -> seen.set(c));

        db.transaction(tx -> {
            tx.select(SLOW);
            assertSame(tx, seen.get());
            return null;
        });
    }
}
