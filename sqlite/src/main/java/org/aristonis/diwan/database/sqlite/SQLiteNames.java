package org.aristonis.diwan.database.sqlite;

import java.util.Locale;

/** Recognises the SQLite spellings that mean an in-memory database. */
final class SQLiteNames {

    private static final String MEMORY = ":memory:";
    private static final String FILE_MEMORY = "file::memory:";
    private static final String MEMORY_MODE = "mode=memory";
    private static final String FILE_URI = "file:";

    private SQLiteNames() {
    }

    /**
     * Returns whether a database target names an in-memory database.
     *
     * <p>The empty text, {@code :memory:}, {@code file::memory:} and a {@code file:} URI that
     * contains {@code mode=memory} all count, in any letter case. A plain path that happens to
     * contain {@code mode=memory} is a file.
     *
     * @param target the target text, a file path or the part of a JDBC URL after its prefix, never {@code null}
     * @return {@code true} if the target is an in-memory database
     */
    static boolean isMemoryTarget(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.isEmpty() || MEMORY.equals(lower) || FILE_MEMORY.equals(lower) 
                || (lower.startsWith(FILE_URI) && lower.contains(MEMORY_MODE));
    }
}
