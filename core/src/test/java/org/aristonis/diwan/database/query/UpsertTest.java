package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import static org.aristonis.diwan.database.query.RecordingConnection.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The SQL and bindings of upsert and updateOrInsert, over a grammar that writes the shared form. */
class UpsertTest {

    private final RecordingConnection db = new RecordingConnection();
    private final QueryFactory factory = db.db(new UpsertStubGrammar());

    private static List<Map<String, Object>> twoRows() {
        return Arrays.asList(row("email", "a@x", "name", "A", "votes", 1), row("email", "b@x", "name", "B", "votes", 2));
    }

    @Test
    void upsertWithoutUpdateColumnsTakesEveryColumnOfTheFirstRow() {
        int written = factory.table("users").upsert(twoRows(), List.of("email"));

        assertEquals(1, written);
        assertEquals("affectingStatement", db.last().method());
        assertEquals("insert into \"users\" (\"email\", \"name\", \"votes\") values (?, ?, ?), (?, ?, ?) "
                + "on conflict (\"email\") do update set \"email\" = \"excluded\".\"email\", "
                + "\"name\" = \"excluded\".\"name\", \"votes\" = \"excluded\".\"votes\"", db.last().sql());
        assertEquals(Arrays.asList("a@x", "A", 1, "b@x", "B", 2), db.last().bindings());
    }

    @Test
    void upsertWithUpdateColumnsNamesOnlyThose() {
        factory.table("users").upsert(twoRows(), List.of("email", "name"), List.of("votes"));

        assertTrue(db.last().sql().endsWith("on conflict (\"email\", \"name\") do update set \"votes\" = \"excluded\".\"votes\""));
    }

    @Test
    void upsertWithAValueMapBindsTheValuesAfterTheRowValues() {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("votes", 9);
        update.put("seen", Expression.raw("now()"));
        update.put("note", "x");

        factory.table("users").upsert(List.of(row("email", "a@x", "name", "A")), List.of("email"), update);

        assertTrue(db.last().sql().endsWith("do update set \"votes\" = ?, \"seen\" = now(), \"note\" = ?"));
        assertEquals(Arrays.asList("a@x", "A", 9, "x"), db.last().bindings());
    }

    @Test
    void anEmptyUpdateLeavesAConflictingRowAlone() {
        factory.table("users").upsert(twoRows(), List.of("email"), Collections.<String>emptyList());
        assertTrue(db.last().sql().endsWith("on conflict (\"email\") do nothing"));

        factory.table("users").upsert(twoRows(), List.of("email"), Collections.<String, Object>emptyMap());
        assertTrue(db.last().sql().endsWith("on conflict (\"email\") do nothing"));
    }

    @Test
    void aNullColumnNameIsRefusedByNameBeforeAnySql() {
        NullPointerException unique = assertThrows(NullPointerException.class,
                () -> factory.table("users").upsert(twoRows(), Arrays.asList("email", null)));
        NullPointerException update = assertThrows(NullPointerException.class,
                () -> factory.table("users").upsert(twoRows(), List.of("email"), Arrays.asList("name", null)));
        Map<String, Object> badKey = new java.util.HashMap<>();
        badKey.put(null, 1);
        NullPointerException key = assertThrows(NullPointerException.class,
                () -> factory.table("users").upsert(twoRows(), List.of("email"), badKey));

        assertTrue(unique.getMessage().contains("uniqueBy"), unique.getMessage());
        assertTrue(update.getMessage().contains("update"), update.getMessage());
        assertTrue(key.getMessage().contains("update"), key.getMessage());
    }

    @Test
    void noUniqueColumnsIsRefusedBeforeAnythingElse() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.table("users").upsert(Collections.emptyList(), Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> factory.table("users").upsert(twoRows(), Collections.emptyList(), List.of("name")));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void noRowsRunsNothingAndReturnsZero() {
        assertEquals(0, factory.table("users").upsert(Collections.emptyList(), List.of("email")));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void aRowWithoutColumnsIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.table("users").upsert(List.of(Collections.<String, Object>emptyMap()), List.of("email")));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void rowsWithDifferentColumnsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> factory.table("users")
                .upsert(Arrays.asList(row("email", "a"), row("name", "b")), List.of("email")));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void theBaseGrammarRefusesAnUpsertBeforeAnySql() {
        assertThrows(UnsupportedOperationException.class,
                () -> db.db().table("users").upsert(twoRows(), List.of("email")));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void theGrammarChecksTheDatabaseVersionBeforeCompiling() {
        db.reportsVersion("3.22.0");
        UpsertStubGrammar refusing = new UpsertStubGrammar() {
            @Override
            protected void checkUpsert(String databaseVersion) {
                throw new UnsupportedOperationException("too old: " + databaseVersion);
            }
        };

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> db.db(refusing).table("users").upsert(twoRows(), List.of("email")));

        assertEquals("too old: 3.22.0", thrown.getMessage());
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void updateOrInsertInsertsTheMergedRowWhenNothingMatches() {
        db.returns("select exists(select * from \"users\" where (\"email\" = ?)) as \"exists\"",
                List.of(row("exists", 0)));

        assertTrue(factory.table("users").updateOrInsert(row("email", "a@x"), row("name", "A")));

        assertEquals("insert", db.last().method());
        assertEquals("insert into \"users\" (\"email\", \"name\") values (?, ?)", db.last().sql());
        assertEquals(Arrays.asList("a@x", "A"), db.last().bindings());
    }

    @Test
    void updateOrInsertUpdatesOneMatchingRow() {
        db.returns("select exists(select * from \"users\" where (\"email\" = ?)) as \"exists\"",
                List.of(row("exists", 1)));

        assertTrue(factory.table("users").updateOrInsert(row("email", "a@x"), row("name", "A")));

        assertEquals("update", db.last().method());
        assertTrue(db.last().sql().startsWith("update \"users\" set \"name\" = ? where"));
        assertEquals(Arrays.asList("A", "a@x"), db.last().bindings().subList(0, 2));
    }

    @Test
    void updateOrInsertWithNothingToChangeOnlyChecks() {
        db.returns("select exists(select * from \"users\" where (\"email\" = ?)) as \"exists\"",
                List.of(row("exists", 1)));

        assertTrue(factory.table("users").updateOrInsert(row("email", "a@x")));

        assertEquals("select", db.last().method());
        assertEquals(1, db.calls.size());
    }
}
