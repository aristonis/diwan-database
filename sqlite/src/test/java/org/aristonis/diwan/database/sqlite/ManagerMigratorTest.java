package org.aristonis.diwan.database.sqlite;

import static org.aristonis.diwan.database.sqlite.ManagerFixtures.createUsers;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.manager;
import static org.aristonis.diwan.database.sqlite.ManagerFixtures.sqlite;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The migrator the manager hands out runs on the named connection only. */
class ManagerMigratorTest {

    @TempDir
    Path folder;

    @Test
    void migrationsRunOnTheNamedConnection() {
        try (DatabaseManager manager = manager(sqlite("main", folder.resolve("main.db").toString()),
                sqlite("other", folder.resolve("other.db").toString()))) {
            assertEquals(1, manager.migrator("main").add(createUsers()).build().run().size());

            assertTrue(manager.schema("main").hasTable("users"));
            assertFalse(manager.schema("other").hasTable("users"));
        }
    }

    @Test
    void rollbackUndoesThemOnThatConnection() {
        try (DatabaseManager manager = manager(sqlite("main", ":memory:"))) {
            var migrator = manager.migrator("main").add(createUsers()).build();
            migrator.run();

            assertEquals(1, migrator.rollback().size());

            assertFalse(manager.schema("main").hasTable("users"));
        }
    }
}
