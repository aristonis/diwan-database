package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.sqlite.SQLiteDataSource;

/** The option rules of {@link RefreshDatabase} that do not need PostgreSQL. */
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

    @Test
    void strategyReturnsANewExtension() {
        RefreshDatabase original = template();

        RefreshDatabase derived = original.strategy(CopyStrategy.WAL_LOG);

        assertNotSame(original, derived);
    }

    @Test
    void directoryAndDropStaleTemplatesReturnNewExtensions() {
        RefreshDatabase original = template();

        assertNotSame(original, original.directory(Paths.get("templates")));
        assertNotSame(original, original.dropStaleTemplates());
    }

    @Test
    void optionsChainOnTheTemplateExtension() {
        RefreshDatabase chained = template().strategy(CopyStrategy.FILE_COPY).directory(Paths.get("templates"))
                .dropStaleTemplates();

        assertNotNull(chained);
    }

    @Test
    void templateOptionsNeedTemplateMode() {
        for (RefreshDatabase other : new RefreshDatabase[] {ORIGINAL, RefreshDatabase.truncate(
                () -> SQLiteQuery.of(connection), SQLiteSchema::of, Fixtures.base())}) {
            assertAppliesToTemplate("strategy", () -> other.strategy(CopyStrategy.FILE_COPY));
            assertAppliesToTemplate("directory", () -> other.directory(Paths.get("templates")));
            assertAppliesToTemplate("dropStaleTemplates", other::dropStaleTemplates);
        }
    }

    @Test
    void templateModeRefusesRestartingIdentities() {
        IllegalStateException error = assertThrows(IllegalStateException.class, template()::restartingIdentities);

        assertTrue(error.getMessage().contains("fresh copy"), error.getMessage());
    }

    @Test
    void templateOptionsRefuseNull() {
        RefreshDatabase template = template();

        assertThrows(NullPointerException.class, () -> template.strategy(null));
        assertThrows(NullPointerException.class, () -> template.directory(null));
    }

    @Test
    void templateFactoryRefusesNullArguments() {
        SQLiteDataSource root = new SQLiteDataSource();

        assertThrows(NullPointerException.class,
                () -> RefreshDatabase.template(null, FakeCopies::manager, SQLiteSchema::of, Fixtures.base()));
        assertThrows(NullPointerException.class,
                () -> RefreshDatabase.template(root, null, SQLiteSchema::of, Fixtures.base()));
        assertThrows(NullPointerException.class,
                () -> RefreshDatabase.template(root, FakeCopies::manager, null, Fixtures.base()));
        assertThrows(NullPointerException.class,
                () -> RefreshDatabase.template(root, FakeCopies::manager, SQLiteSchema::of, null));
        assertThrows(NullPointerException.class, () -> RefreshDatabase.template(root, FakeCopies::manager,
                SQLiteSchema::of, Arrays.asList((Migration) null)));
    }

    private static RefreshDatabase template() {
        return RefreshDatabase.template(new SQLiteDataSource(), FakeCopies::manager, SQLiteSchema::of,
                Fixtures.base());
    }

    private static void assertAppliesToTemplate(String option, Runnable call) {
        IllegalStateException error = assertThrows(IllegalStateException.class, call::run);

        assertEquals(option + "() applies to template mode", error.getMessage());
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
