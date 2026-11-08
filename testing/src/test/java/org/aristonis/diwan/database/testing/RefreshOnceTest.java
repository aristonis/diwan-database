package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.Events;

/**
 * The migrate-once rule across test classes: fixture classes run together through the engine, so
 * they share one root store, as the classes of a real run do.
 */
class RefreshOnceTest {

    private static final String ONLY_IN_KIT = "org.aristonis.diwan.database.testing.KitRun#isActive";

    /** The database of the current kit run. */
    private static volatile Connection connection;
    private static volatile Connection connectionA;
    private static volatile Connection connectionB;
    private static final AtomicInteger FAILING_SUPPLIER_CALLS = new AtomicInteger();

    @Test
    void classesWithTheSameConnectionAndMigrationsMigrateOnce() {
        connection = Fixtures.sqlite("sqlite-once-same");
        Fixtures.resetCounts();

        KitRun.run(First.class, Second.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

        assertEquals(1, Fixtures.USERS_RUNS.get());
        assertEquals(1, Fixtures.POSTS_RUNS.get());
    }

    @Test
    void aDifferentMigrationListOnTheSameConnectionIsRefused() {
        connection = Fixtures.sqlite("sqlite-once-different");
        Fixtures.resetCounts();

        EngineExecutionResults results = KitRun.run(First.class, WithTags.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(1));
        results.containerEvents().assertStatistics(stats -> stats.failed(1));
        assertTrue(failureMessages(results).stream().anyMatch(m -> m.contains("different migration list")),
                failureMessages(results).toString());
        assertEquals(1, Fixtures.USERS_RUNS.get());
        assertEquals(0, Fixtures.TAGS_RUNS.get());
    }

    @Test
    void twoDatabasesWithTheSameNameBothMigrate() {
        connectionA = Fixtures.sqlite("same-name");
        connectionB = Fixtures.sqlite("same-name");
        Fixtures.resetCounts();

        KitRun.run(SameNameA.class, SameNameB.class).testEvents().assertStatistics(stats -> stats.succeeded(2));

        assertEquals(2, Fixtures.USERS_RUNS.get());
    }

    @Test
    void truncateModeFailsClearlyWhenTestsRunInParallel() {
        connection = Fixtures.sqlite("sqlite-parallel-truncate");

        EngineExecutionResults results = KitRun.runParallel(WithTags.class);

        results.testEvents().assertStatistics(stats -> stats.started(0));
        assertTrue(failureMessages(results).stream().anyMatch(m -> m.contains("cannot run tests in parallel")),
                failureMessages(results).toString());
    }

    @Test
    void rollbackModeRunsInParallel() {
        connection = Fixtures.sqlite("sqlite-parallel-rollback");

        KitRun.runParallel(First.class).testEvents().assertStatistics(stats -> stats.succeeded(1));
    }

    @Test
    void aFailedMigrationIsTheOnlyErrorAndTheDatabaseIsLeftAlone() {
        connection = Fixtures.sqlite("sqlite-failed-migration");
        FAILING_SUPPLIER_CALLS.set(0);

        EngineExecutionResults results = KitRun.run(FailsToMigrate.class);

        results.testEvents().assertStatistics(stats -> stats.failed(1));
        List<Throwable> failures = failures(results);
        assertEquals(1, failures.size(), failures.toString());
        assertEquals(0, failures.get(0).getSuppressed().length);
        assertTrue(rootCauseMessage(failures.get(0)).contains("migration boom"), failures.toString());
        // Only beforeEach asked for the database: the after-each step did not touch it.
        assertEquals(1, FAILING_SUPPLIER_CALLS.get());
    }

    private static List<Throwable> failures(EngineExecutionResults results) {
        List<Throwable> failures = new ArrayList<>();
        for (Events events : new Events[] {results.testEvents(), results.containerEvents()}) {
            events.failed().stream().forEach(event -> event.getPayload(TestExecutionResult.class)
                    .flatMap(TestExecutionResult::getThrowable).ifPresent(failures::add));
        }
        return failures;
    }

    private static List<String> failureMessages(EngineExecutionResults results) {
        List<String> messages = new ArrayList<>();
        for (Throwable failure : failures(results)) {
            messages.add(String.valueOf(failure.getMessage()));
        }
        return messages;
    }

    private static String rootCauseMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    @Test
    void eachRunMigratesItsOwnConnectionOnce() {
        connection = Fixtures.sqlite("sqlite-once-named");
        Fixtures.resetCounts();

        KitRun.run(First.class).testEvents().assertStatistics(stats -> stats.succeeded(1));
        connection = Fixtures.sqlite("sqlite-once-other");
        KitRun.run(First.class).testEvents().assertStatistics(stats -> stats.succeeded(1));

        // Each kit run has its own root store, so each migrates once.
        assertEquals(2, Fixtures.USERS_RUNS.get());
    }

    /** A fixture: registered statically with the base migrations. */
    @EnabledIf(ONLY_IN_KIT)
    static class First {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.rollback(() -> SQLiteQuery.of(connection),
                SQLiteSchema::of, Fixtures.base());

        @Test
        void usesTheDatabase(DatabaseAssertions assertions) {
            assertions.assertEmpty("users");
        }
    }

    /** A fixture: the same connection and the same migrations as {@link First}. */
    @EnabledIf(ONLY_IN_KIT)
    static class Second {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.rollback(() -> SQLiteQuery.of(connection),
                SQLiteSchema::of, Fixtures.base());

        @Test
        void usesTheDatabase(DatabaseAssertions assertions) {
            assertions.assertEmpty("posts");
        }
    }

    /** A fixture: the same connection, one more migration. */
    @EnabledIf(ONLY_IN_KIT)
    static class WithTags {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.truncate(() -> SQLiteQuery.of(connection),
                SQLiteSchema::of, Fixtures.withTags());

        @Test
        void usesTheDatabase(DatabaseAssertions assertions) {
            assertions.assertEmpty("tags");
        }
    }

    /** A fixture: first of two databases that share a name. */
    @EnabledIf(ONLY_IN_KIT)
    static class SameNameA {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.rollback(() -> SQLiteQuery.of(connectionA),
                SQLiteSchema::of, Fixtures.base());

        @Test
        void usesTheDatabase(DatabaseAssertions assertions) {
            assertions.assertEmpty("users");
        }
    }

    /** A fixture: second of two databases that share a name. */
    @EnabledIf(ONLY_IN_KIT)
    static class SameNameB {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.rollback(() -> SQLiteQuery.of(connectionB),
                SQLiteSchema::of, Fixtures.base());

        @Test
        void usesTheDatabase(DatabaseAssertions assertions) {
            assertions.assertEmpty("users");
        }
    }

    /** A fixture: truncate mode, registered per instance so the migration runs in beforeEach and fails. */
    @EnabledIf(ONLY_IN_KIT)
    static class FailsToMigrate {
        @RegisterExtension
        final RefreshDatabase db = RefreshDatabase.truncate(() -> {
            FAILING_SUPPLIER_CALLS.incrementAndGet();
            return SQLiteQuery.of(connection);
        }, SQLiteSchema::of, Fixtures.failing());

        @Test
        void neverRuns() {
            throw new AssertionError("The test body must not run after a failed migration");
        }
    }
}
