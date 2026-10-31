package org.aristonis.diwan.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MigrationEntryTest {

    @Test
    void hasValueSemantics() {
        MigrationEntry entry = new MigrationEntry("a", 1);

        assertEquals(new MigrationEntry("a", 1), entry);
        assertEquals(new MigrationEntry("a", 1).hashCode(), entry.hashCode());
        assertNotEquals(new MigrationEntry("a", 2), entry);
        assertNotEquals(new MigrationEntry("b", 1), entry);
        assertNotEquals(entry, "a");
        assertEquals("MigrationEntry[a, batch 1]", entry.toString());
    }

    @Test
    void refusesInvalidNamesAndBatches() {
        assertThrows(NullPointerException.class, () -> new MigrationEntry(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new MigrationEntry("  ", 1));
        assertThrows(IllegalArgumentException.class, () -> new MigrationEntry("a\nb", 1));
        assertThrows(IllegalArgumentException.class, () -> new MigrationEntry(repeat('x', 192), 1));
        assertThrows(IllegalArgumentException.class, () -> new MigrationEntry("a", 0));
        assertEquals(191, new MigrationEntry(repeat('x', 191), 1).name().length());
    }

    static String repeat(char c, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(c);
        }
        return text.toString();
    }
}
