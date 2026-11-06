package org.aristonis.diwan.database;

import org.aristonis.diwan.database.event.Registration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** A connection nobody listens to pays nothing: the engine stays inactive until something subscribes. */
class ListenerCostTest {

    private final FakeDriver driver = new FakeDriver();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).build();
    }

    @Test
    void isInactiveWithoutListenersLogOrHandlers() {
        assertFalse(connection().events().isActive());
    }

    @Test
    void staysInactiveAndLogsNothingAcrossStatements() {
        Connection db = connection();

        db.insert("insert into t values (1)");
        db.select("select * from t");

        assertFalse(db.events().isActive());
        assertTrue(db.events().getQueryLog().isEmpty());
        assertEquals(Duration.ZERO, db.events().totalQueryDuration());
    }

    @Test
    void statementsStillReachTheDriverWhileInactive() {
        connection().insert("insert into t values (?)", 1);

        assertEquals(Arrays.asList("open", "execute insert into t values (?) [1]", "close"), driver.calls());
    }

    @Test
    void becomesActiveWithAListenerAndInactiveAgainWhenItIsRemoved() {
        Connection db = connection();

        Registration registration = db.events().addListener(new RecordingListener());
        assertTrue(db.events().isActive());
        registration.remove();

        assertFalse(db.events().isActive());
    }

    @Test
    void becomesActiveWhileTheQueryLogIsOn() {
        Connection db = connection();

        db.events().enableQueryLog();
        assertTrue(db.events().isActive());
        db.events().disableQueryLog();

        assertFalse(db.events().isActive());
    }

    @Test
    void becomesActiveWithASlowQueryHandlerUntilItIsRemoved() {
        Connection db = connection();

        Registration registration = db.events().whenQueryTakesLongerThan(Duration.ofSeconds(1), e -> { });
        assertTrue(db.events().isActive());
        registration.remove();

        assertFalse(db.events().isActive());
    }

    @Test
    void becomesActiveWithATotalTimeHandlerUntilItIsRemoved() {
        Connection db = connection();

        Registration registration = db.events().whenQueryingForLongerThan(Duration.ofSeconds(1), (c, e) -> { });
        assertTrue(db.events().isActive());
        registration.remove();

        assertFalse(db.events().isActive());
    }

    @Test
    void isNotDispatchingOutsideAListener() {
        Connection db = connection();
        db.events().listen(e -> { });

        db.select("select 1");

        assertFalse(db.events().isDispatching());
    }
}
