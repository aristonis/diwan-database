package org.aristonis.diwan.database.eloquent;

import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Model events on SQLite: the lifecycle order, vetoes, rollback with a listener write, retrieved and quiet writes. */
class SQLiteEventsTest {

    private EventFixtures fx;

    @BeforeEach
    void open() throws SQLException {
        fx = EventFixtures.open();
        fx.seed();
    }

    @AfterEach
    void close() throws SQLException {
        EventFixtures.forgetAll();
        fx.close();
    }

    @Test
    void lifecycleEventsFireInOrder() {
        EventFixtures.Flows.lifecycleEventOrder(fx);
    }

    @Test
    void vetoedSaveWritesNothing() {
        EventFixtures.Flows.vetoedSaveWritesNothing(fx);
    }

    @Test
    void listenerWriteRollsBackWithTheSave() {
        EventFixtures.Flows.listenerWriteRollsBackWithTheSave(fx);
    }

    @Test
    void retrievedFiresPerHydratedModelAndForEagerLoads() {
        EventFixtures.Flows.retrievedFiresPerHydratedModel(fx);
    }

    @Test
    void quietWritesAreSilent() {
        EventFixtures.Flows.quietWritesAreSilent(fx);
    }
}
