package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;

/** The rules of {@link RefreshDatabase#restartingIdentities()} that do not need PostgreSQL. */
class RefreshDatabaseOptionsTest {

    private static final String ONLY_IN_KIT = "org.aristonis.diwan.database.testing.KitRun#isActive";

    private static volatile Connection connection;
    private static final RefreshDatabase ORIGINAL = RefreshDatabase.rollback(() -> SQLiteQuery.of(connection),
            SQLiteSchema::of, Fixtures.base());

    @Test
    void refusesParallelRestart() {
        connection = Fixtures.sqlite("sqlite-options-parallel");

        EngineExecutionResults results = KitRun.runParallel(Restarting.class);

        results.testEvents().assertStatistics(stats -> stats.started(0));
        List<String> messages = failureMessages(results);
        assertTrue(messages.stream().anyMatch(m -> m.contains("restartingIdentities() cannot run tests in parallel")),
                messages.toString());
    }

    @Test
    void truncateModeRefusesTheOption() {
        RefreshDatabase truncate = RefreshDatabase.truncate(() -> SQLiteQuery.of(connection), SQLiteSchema::of,
                Fixtures.base());

        IllegalStateException error = assertThrows(IllegalStateException.class, truncate::restartingIdentities);

        assertTrue(error.getMessage().contains("already restarts identities"), error.getMessage());
    }

    @Test
    void optionReturnsANewExtension() {
        connection = Fixtures.sqlite("sqlite-options-original");

        RefreshDatabase derived = ORIGINAL.restartingIdentities();

        assertNotSame(ORIGINAL, derived);
        // The original keeps running in parallel, which the option would refuse.
        KitRun.runParallel(Original.class).testEvents().assertStatistics(stats -> stats.succeeded(1));
    }

    private static List<String> failureMessages(EngineExecutionResults results) {
        List<String> messages = new ArrayList<>();
        results.containerEvents().failed().stream().forEach(event -> event.getPayload(TestExecutionResult.class)
                .flatMap(TestExecutionResult::getThrowable)
                .ifPresent(failure -> messages.add(String.valueOf(failure.getMessage()))));
        return messages;
    }

    /** A fixture: rollback with the option on. */
    @EnabledIf(ONLY_IN_KIT)
    static class Restarting {
        @RegisterExtension
        static final RefreshDatabase DB = ORIGINAL.restartingIdentities();

        @Test
        void neverRuns() {
            throw new AssertionError("The test body must not run when the option is refused");
        }
    }

    /** A fixture: the original extension, after the option was taken from it. */
    @EnabledIf(ONLY_IN_KIT)
    static class Original {
        @RegisterExtension
        static final RefreshDatabase DB = ORIGINAL;

        @Test
        void usesTheDatabase(DatabaseAssertions assertions) {
            assertions.assertEmpty("users");
        }
    }
}
