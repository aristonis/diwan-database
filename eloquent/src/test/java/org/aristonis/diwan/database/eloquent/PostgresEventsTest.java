package org.aristonis.diwan.database.eloquent;

import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Model events on a throwaway PostgreSQL, the same flows as on SQLite. Skipped when the server binaries are missing. */
class PostgresEventsTest {

    private EventFixtures fx;

    @BeforeEach
    void open() {
        fx = EventFixtures.openPostgres();
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
