package org.aristonis.photisma.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MigrationNamesTest {

    @Test
    void acceptsPlainLowercaseTableNames() {
        assertEquals("migrations", MigrationNames.checkTable("migrations"));
        assertEquals("_app_migrations2", MigrationNames.checkTable("_app_migrations2"));
        assertEquals(63, MigrationNames.checkTable(MigrationEntryTest.repeat('m', 63)).length());
    }

    @Test
    void refusesTableNamesThatCouldCarrySql() {
        String[] bad = {"", "my table", "t\"x", "t;drop", "1table", "Migrations", "t-x", "t.x",
            MigrationEntryTest.repeat('m', 64)};
        String fixedMessage = assertThrows(IllegalArgumentException.class,
                () -> MigrationNames.checkTable("t;drop")).getMessage();
        for (String name : bad) {
            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> MigrationNames.checkTable(name));
            assertEquals(fixedMessage, error.getMessage(), "the message never echoes the name");
        }
        assertThrows(NullPointerException.class, () -> MigrationNames.checkTable(null));
    }
}
