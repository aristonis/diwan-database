package org.aristonis.diwan.database.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseVersionTest {

    @Test
    void comparesPartByPart() {
        assertTrue(DatabaseVersion.atLeast("3.35.0", 3, 35, 0));
        assertTrue(DatabaseVersion.atLeast("3.45.1", 3, 35, 0));
        assertTrue(DatabaseVersion.atLeast("4", 3, 35, 0));
        assertFalse(DatabaseVersion.atLeast("3.22.0", 3, 25, 0));
    }

    @Test
    void readsLeadingDigitsAndTreatsTheRestAsZero() {
        assertTrue(DatabaseVersion.atLeast("18.3 (Debian 18.3-1)", 18));
        assertFalse(DatabaseVersion.atLeast("17.6 (Ubuntu)", 18));
        assertFalse(DatabaseVersion.atLeast("3.x", 3, 25, 0));
        assertTrue(DatabaseVersion.atLeast("99999999999.1", 18));
    }

    @Test
    void nullIsRefused() {
        assertThrows(NullPointerException.class, () -> DatabaseVersion.atLeast(null, 1));
    }
}
