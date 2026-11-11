package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.sqlite.SQLiteDataSource;

/**
 * Template mode end to end with a fake copy engine over SQLite files: set-up order, one copy per
 * test, parameters from the test's own database, parallel tests, refusals and drop failures.
 */
class TemplateModeFakeTest {

    private static final String ONLY_IN_KIT = "org.aristonis.diwan.database.testing.KitRun#isActive";

    private static volatile FakeCopies copies;
    private static volatile SQLiteDataSource current;

    /** One root for every fixture run: the fixtures' extensions are built once per JVM, the folder per test. */
    private static final SQLiteDataSource ROOT = new SQLiteDataSource() {
        @Override
        public java.sql.Connection getConnection() throws SQLException {
            return current.getConnection();
        }
    };
    private static final List<String> SEEN_FILES = Collections.synchronizedList(new ArrayList<String>());
    private static final List<Long> SEEN_COUNTS = Collections.synchronizedList(new ArrayList<Long>());

    @TempDir
    Path folder;

    @BeforeEach
    void reset() {
        SEEN_FILES.clear();
        SEEN_COUNTS.clear();
        current = new SQLiteDataSource();
        current.setUrl("jdbc:sqlite:" + folder.resolve("root.db"));
    }

    @Test
    void eachTestGetsItsOwnCopyAndParametersPointAtIt() {
        copies = new FakeCopies(folder, false, false);

        EngineExecutionResults results = KitRun.run(Sequential.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(2));
        assertEquals(2, new HashSet<>(SEEN_FILES).size(), SEEN_FILES.toString());
        for (String file : SEEN_FILES) {
            assertTrue(file.startsWith(folder.toString()) && file.contains("copy_"), file);
        }
    }

    @Test
    void aCommittedRowDoesNotReachTheNextTest() {
        copies = new FakeCopies(folder, false, false);

        EngineExecutionResults results = KitRun.run(Sequential.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(2));
        // Both tests insert one row and then count: each saw exactly its own.
        assertEquals(Arrays.asList(1L, 1L), SEEN_COUNTS);
    }

    @Test
    void setUpRunsOnceThenOneCopyPerTestIsMadeAndDropped() {
        copies = new FakeCopies(folder, false, false);

        KitRun.run(Sequential.class, SecondClass.class);

        List<String> events = copies.events();
        assertEquals("prepare", events.get(0));
        assertEquals("sweep", events.get(1));
        assertEquals(1, events.stream().filter("prepare"::equals).count(), events.toString());
        assertEquals(3, events.stream().filter(e -> e.startsWith("create:")).count(), events.toString());
        assertEquals(3, events.stream().filter(e -> e.startsWith("drop:")).count(), events.toString());
        assertEquals("close", events.get(events.size() - 1));
        assertFalse(Files.exists(folder.resolve("copy_1.db")));
    }

    @Test
    void parallelTestsGetDifferentCopies() {
        copies = new FakeCopies(folder, false, false);

        EngineExecutionResults results = KitRun.runParallel(Parallel.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(4));
        assertEquals(4, new HashSet<>(SEEN_FILES).size(), SEEN_FILES.toString());
    }

    @Test
    void aFailedPrepareStopsBeforeTheFirstTest() {
        copies = new FakeCopies(folder, true, false);

        EngineExecutionResults results = KitRun.run(Sequential.class);

        results.testEvents().assertStatistics(stats -> stats.started(0));
        assertTrue(failureMessages(results).stream().anyMatch(m -> m.contains("the template cannot be built")),
                failureMessages(results).toString());
        // The copies are closed again, so a failed prepare leaves no lock behind.
        assertEquals("close", copies.events().get(copies.events().size() - 1));
    }

    @Test
    void aFailingDropIsLoggedAndTheTestStillPasses() {
        copies = new FakeCopies(folder, false, true);
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(TestDatabase.class.getName());
        logger.addHandler(handler);
        try {
            EngineExecutionResults results = KitRun.run(Sequential.class);

            results.testEvents().assertStatistics(stats -> stats.succeeded(2));
        } finally {
            logger.removeHandler(handler);
        }
        assertEquals(2, records.stream().filter(r -> r.getLevel() == Level.WARNING).count());
        assertTrue(records.get(0).getMessage().contains("copy_"), records.get(0).getMessage());
    }

    @Test
    void otherOptionsForTheSameTemplateInOneRunAreRefused() {
        copies = new FakeCopies(folder, false, false);

        EngineExecutionResults results = KitRun.run(Sequential.class, OtherOptions.class);

        List<String> messages = failureMessages(results);
        assertTrue(messages.stream().anyMatch(m -> m.contains("with other options")), messages.toString());
    }

    @Test
    void parametersOutsideATestAreRefusedWithAClearMessage() {
        copies = new FakeCopies(folder, false, false);

        EngineExecutionResults results = KitRun.run(InConstructor.class);

        assertTrue(failureMessages(results).stream().anyMatch(m -> m.contains("no database for this test")),
                failureMessages(results).toString());
    }

    private static List<String> failureMessages(EngineExecutionResults results) {
        List<String> messages = new ArrayList<>();
        results.containerEvents().failed().stream().forEach(event -> event.getPayload(TestExecutionResult.class)
                .flatMap(TestExecutionResult::getThrowable)
                .ifPresent(failure -> messages.add(String.valueOf(failure.getMessage()))));
        results.testEvents().failed().stream().forEach(event -> event.getPayload(TestExecutionResult.class)
                .flatMap(TestExecutionResult::getThrowable)
                .ifPresent(failure -> messages.add(String.valueOf(failure.getMessage()))));
        return messages;
    }

    private static RefreshDatabase extension() {
        return RefreshDatabase.template(ROOT, FakeCopies::manager, SQLiteSchema::of, Fixtures.base())
                .withCopies((settings, run) -> copies);
    }

    private static void work(QueryFactory query, Connection connection, DatabaseAssertions assertions) {
        assertions.assertEmpty("users");
        SEEN_FILES.add(String.valueOf(connection.scalar("select file from pragma_database_list where name = 'main'")
                .orElse("")));
        query.table("users").insert(Map.of("name", "ann"));
        SEEN_COUNTS.add(query.table("users").count());
    }

    /** A fixture: two sequential tests, each inserting a row. */
    @EnabledIf(ONLY_IN_KIT)
    static class Sequential {
        @RegisterExtension
        static final RefreshDatabase DB = extension();

        @Test
        void first(QueryFactory query, Connection connection, DatabaseAssertions assertions) {
            work(query, connection, assertions);
        }

        @Test
        void second(QueryFactory query, Connection connection, DatabaseAssertions assertions) {
            work(query, connection, assertions);
        }
    }

    /** A fixture: one more test on the same extension settings, as a second class of the run. */
    @EnabledIf(ONLY_IN_KIT)
    static class SecondClass {
        @RegisterExtension
        static final RefreshDatabase DB = extension();

        @Test
        void third(QueryFactory query, Connection connection, DatabaseAssertions assertions) {
            work(query, connection, assertions);
        }
    }

    /** A fixture: four tests that may run at the same time. */
    @EnabledIf(ONLY_IN_KIT)
    @Execution(ExecutionMode.CONCURRENT)
    static class Parallel {
        @RegisterExtension
        static final RefreshDatabase DB = extension();

        @Test
        void a(QueryFactory query, Connection connection, DatabaseAssertions assertions) {
            work(query, connection, assertions);
        }

        @Test
        void b(QueryFactory query, Connection connection, DatabaseAssertions assertions) {
            work(query, connection, assertions);
        }

        @Test
        void c(QueryFactory query, Connection connection, DatabaseAssertions assertions) {
            work(query, connection, assertions);
        }

        @Test
        void d(QueryFactory query, Connection connection, DatabaseAssertions assertions) {
            work(query, connection, assertions);
        }
    }

    /** A fixture: same root and migrations as {@link Sequential}, but another copy strategy. */
    @EnabledIf(ONLY_IN_KIT)
    static class OtherOptions {
        @RegisterExtension
        static final RefreshDatabase DB = extension()
                .strategy(org.aristonis.diwan.database.schema.CopyStrategy.WAL_LOG);

        @Test
        void neverRuns() {
            throw new AssertionError("The test body must not run when the options differ");
        }
    }

    /** A fixture: asks for a parameter in the constructor, where no test database exists. */
    @EnabledIf(ONLY_IN_KIT)
    static class InConstructor {
        @RegisterExtension
        static final RefreshDatabase DB = extension();

        InConstructor(QueryFactory query) {
        }

        @Test
        void neverRuns() {
            throw new AssertionError("The constructor must fail first");
        }
    }
}
