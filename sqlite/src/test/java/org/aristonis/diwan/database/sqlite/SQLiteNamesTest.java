package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SQLiteNamesTest {

    @Test
    void everyMemorySpellingIsRecognised() {
        assertTrue(SQLiteNames.isMemoryTarget(""));
        assertTrue(SQLiteNames.isMemoryTarget(":memory:"));
        assertTrue(SQLiteNames.isMemoryTarget(":MEMORY:"));
        assertTrue(SQLiteNames.isMemoryTarget("file::memory:"));
        assertTrue(SQLiteNames.isMemoryTarget("file:x?mode=memory&cache=shared"));
        assertTrue(SQLiteNames.isMemoryTarget("file:x?MODE=Memory"));
        assertTrue(SQLiteNames.isMemoryTarget("FILE:x?mode=memory"));
        assertTrue(SQLiteNames.isMemoryTarget("file:x?mode=memory"));
    }

    @Test
    void aFilePathIsNotMemory() {
        assertFalse(SQLiteNames.isMemoryTarget("/tmp/x.db"));
        assertFalse(SQLiteNames.isMemoryTarget("memory.db"));
        assertFalse(SQLiteNames.isMemoryTarget("/tmp/a_mode=memory/x.db"));
    }
}
