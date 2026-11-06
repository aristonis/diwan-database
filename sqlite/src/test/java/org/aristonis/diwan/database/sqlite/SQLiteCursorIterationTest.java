package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteCursorIterationTest {

    @TempDir
    Path folder;

    private QueryFactory db;

    @BeforeEach
    void seed() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("iteration.db"));
        Connection connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("sparse", t -> {
            t.id();
            t.string("a").nullable();
            t.integer("b").nullable();
        });
        db.table("sparse").insert(List.of(row("a", "x", "b", 1), row("a", null, "b", null), row("a", "y", "b", 3)));
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private QueryCursor<Map<String, Object>> cursor() {
        return db.table("sparse").orderBy("id").cursor();
    }

    @Test
    void nextAfterTheLastRowThrowsNoSuchElementWhenHasNextWasChecked() {
        try (QueryCursor<Map<String, Object>> cursor = cursor()) {
            Iterator<Map<String, Object>> rows = cursor.iterator();
            while (rows.hasNext()) {
                rows.next();
            }

            assertFalse(rows.hasNext());
            assertThrows(NoSuchElementException.class, rows::next);
        }
    }

    @Test
    void nextAfterTheLastRowThrowsNoSuchElementWithoutAnyHasNextCall() {
        try (QueryCursor<Map<String, Object>> cursor = cursor()) {
            Iterator<Map<String, Object>> rows = cursor.iterator();
            rows.next();
            rows.next();
            rows.next();

            assertThrows(NoSuchElementException.class, rows::next);
        }
    }

    @Test
    void aRowWithOnlyNullValuesDoesNotEndTheIteration() {
        List<Map<String, Object>> seen = new ArrayList<>();
        try (QueryCursor<Map<String, Object>> cursor = db.table("sparse").select("a", "b").orderBy("id").cursor()) {
            for (Map<String, Object> row : cursor) {
                seen.add(row);
            }
        }

        assertEquals(3, seen.size());
        assertNull(seen.get(1).get("a"));
        assertNull(seen.get(1).get("b"));
        assertEquals("y", seen.get(2).get("a"));
    }

    @Test
    void callingHasNextTwiceDoesNotSkipARow() {
        try (QueryCursor<Map<String, Object>> cursor = cursor()) {
            Iterator<Map<String, Object>> rows = cursor.iterator();

            assertTrue(rows.hasNext());
            assertTrue(rows.hasNext());
            assertEquals(1L, ((Number) rows.next().get("id")).longValue());
            assertTrue(rows.hasNext());
            assertTrue(rows.hasNext());
            assertEquals(2L, ((Number) rows.next().get("id")).longValue());
            assertTrue(rows.hasNext());
            assertEquals(3L, ((Number) rows.next().get("id")).longValue());
            assertFalse(rows.hasNext());
            assertFalse(rows.hasNext());
        }
    }
}
