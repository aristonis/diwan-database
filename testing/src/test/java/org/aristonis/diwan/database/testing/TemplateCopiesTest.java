package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shared build step of the copy engines. */
class TemplateCopiesTest {

    @TempDir
    Path folder;

    @Test
    void migrateIntoRunsTheMigrationAndClosesTheManager() {
        List<Object> seen = new ArrayList<>();

        TemplateCopies.migrateInto(FakeCopies::manager, folder.resolve("built.db").toString(),
                connection -> seen.add(connection.scalar("select 7").orElseThrow()));

        assertEquals(1, seen.size());
    }

    @Test
    void migrateIntoClosesTheManagerWhenTheMigrationFails() {
        List<org.aristonis.diwan.database.manager.DatabaseManager> opened = new ArrayList<>();

        assertThrows(IllegalArgumentException.class, () -> TemplateCopies.migrateInto(file -> {
            org.aristonis.diwan.database.manager.DatabaseManager manager = FakeCopies.manager(file);
            opened.add(manager);
            return manager;
        }, folder.resolve("failed.db").toString(), connection -> {
            throw new IllegalArgumentException("bad migration");
        }));

        assertThrows(IllegalStateException.class, () -> opened.get(0).query());
    }

    @Test
    void migrateIntoRefusesANullManager() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TemplateCopies.migrateInto(name -> null, "nowhere", connection -> { }));

        assertTrue(error.getMessage().contains("nowhere"), error.getMessage());
    }
}
