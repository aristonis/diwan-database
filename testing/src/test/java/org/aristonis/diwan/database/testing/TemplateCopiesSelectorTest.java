package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.aristonis.diwan.database.pgsql.PostgresSchema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlite.SQLiteDataSource;

/** The refusals of template mode that do not need a copy engine. */
class TemplateCopiesSelectorTest {

    @TempDir
    Path folder;

    @Test
    void sqliteWithoutADirectoryIsRefusedAsInMemory() {
        SQLiteDataSource root = sqlite("memory-refused.db");
        TemplateSettings settings = TemplateSettings.of(root, FakeCopies::manager, SQLiteSchema::of, Fixtures.base());
        RunConnection run = RunConnection.open(root);

        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> TemplateCopiesSelector.choose(settings, run));

            assertTrue(error.getMessage().contains("in-memory SQLite"), error.getMessage());
            assertTrue(error.getMessage().contains("directory(Path)"), error.getMessage());
        } finally {
            run.close();
        }
    }

    @Test
    void aDirectoryForPostgresIsRefused() {
        PGSimpleDataSource root = PostgresServer.dataSource("postgres");
        TemplateSettings settings = TemplateSettings.of(root, PostgresTemplateFixtures::manager,
                PostgresSchema::of, Fixtures.base()).withDirectory(folder);
        RunConnection run = RunConnection.open(root);

        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> TemplateCopiesSelector.choose(settings, run));

            assertTrue(error.getMessage().contains("directory() applies to SQLite template mode"),
                    error.getMessage());
        } finally {
            run.close();
        }
    }

    @Test
    void theExtensionFailsBeforeTheFirstTest() {
        EngineExecutionResults results = KitRun.run(InMemory.class);

        results.testEvents().assertStatistics(stats -> stats.started(0));
        boolean isRefused = results.containerEvents().failed().stream()
                .map(event -> event.getPayload(TestExecutionResult.class).flatMap(TestExecutionResult::getThrowable))
                .anyMatch(failure -> failure.isPresent() && String.valueOf(failure.get().getMessage())
                        .contains("in-memory SQLite"));
        assertTrue(isRefused);
    }

    private SQLiteDataSource sqlite(String file) {
        SQLiteDataSource root = new SQLiteDataSource();
        root.setUrl("jdbc:sqlite:" + folder.resolve(file));
        return root;
    }

    /** A fixture: SQLite template mode without a directory. */
    @EnabledIf("org.aristonis.diwan.database.testing.KitRun#isActive")
    static class InMemory {
        @RegisterExtension
        static final RefreshDatabase DB = RefreshDatabase.template(memory(), FakeCopies::manager,
                SQLiteSchema::of, Fixtures.base());

        private static SQLiteDataSource memory() {
            SQLiteDataSource root = new SQLiteDataSource();
            root.setUrl("jdbc:sqlite::memory:");
            return root;
        }

        @Test
        void neverRuns() {
            throw new AssertionError("The test body must not run when the target is refused");
        }
    }
}
