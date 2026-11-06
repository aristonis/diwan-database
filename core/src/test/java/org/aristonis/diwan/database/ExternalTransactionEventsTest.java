package org.aristonis.diwan.database;

import org.aristonis.diwan.database.event.TransactionEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Inside a transaction the caller owns, Diwan only has a savepoint and cannot know when the caller commits. */
class ExternalTransactionEventsTest {

    private final FakeDriver driver = new FakeDriver().insideExternalTransaction();
    private final RecordingListener listener = new RecordingListener();

    private Connection connection() {
        return Connection.builder().name("main").driver(driver).listener(listener).build();
    }

    @Test
    void marksEveryTransactionEventAsASavepoint() {
        connection().transaction(tx -> tx.insert("insert into t values (1)"));

        List<TransactionEvent> events = listener.transactionEvents();
        assertFalse(events.isEmpty());
        for (TransactionEvent event : events) {
            assertTrue(event.isSavepoint(), listener.phases().toString());
        }
    }

    @Test
    void stillReportsTheRolledBackPhaseAsASavepoint() {
        assertThrows(IllegalStateException.class, () -> connection().transaction(tx -> {
            throw new IllegalStateException("rule");
        }));

        assertTrue(listener.phases().get(listener.phases().size() - 1).startsWith("rolledBack"));
        assertTrue(listener.transactionEvents().get(listener.transactionEvents().size() - 1).isSavepoint());
    }

    @Test
    void refusesAfterCommitBecauseTheCallerOwnsTheCommit() {
        List<String> ran = new ArrayList<>();

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                connection().transaction(tx -> {
                    tx.afterCommit(() -> ran.add("never"));
                    return null;
                }));

        assertTrue(error.getMessage().contains("caller"), error.getMessage());
        assertTrue(ran.isEmpty());
    }

    @Test
    void refusesAfterCommitInsideTheScopePathToo() {
        try (TransactionScope tx = connection().beginTransaction()) {
            assertThrows(IllegalStateException.class, () -> tx.afterCommit(() -> { }));
        }

        assertEquals(0, driver.openSessions());
    }
}
