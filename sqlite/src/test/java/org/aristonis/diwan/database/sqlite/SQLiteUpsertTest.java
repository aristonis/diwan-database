package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Expression;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Upsert, insertOrIgnore and updateOrInsert on a real SQLite database, plus the version gate. */
class SQLiteUpsertTest {

    @TempDir
    Path folder;

    private QueryFactory db;

    @BeforeEach
    void createTable() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("upsert.db"));
        Connection connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("users", t -> {
            t.id();
            t.string("email").unique();
            t.string("name");
            t.integer("votes").defaultValue(0);
        });
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void theGrammarWritesInsertOrIgnoreAndTheOnConflictForms() {
        SQLiteQueryGrammar grammar = new SQLiteQueryGrammar("");
        Builder query = db.table("users");
        List<Map<String, Object>> rows = List.of(row("email", "a", "name", "A"));

        assertEquals("insert or ignore into \"users\" (\"email\", \"name\") values (?, ?)",
                grammar.compileInsertOrIgnore(query, rows));
        assertEquals("insert into \"users\" (\"email\", \"name\") values (?, ?) on conflict (\"email\") "
                + "do update set \"name\" = \"excluded\".\"name\"",
                grammar.compileUpsert(query, rows, List.of("email"), List.of("name"), Map.of()));
        assertEquals("insert into \"users\" (\"email\", \"name\") values (?, ?) on conflict (\"email\") do nothing",
                grammar.compileUpsert(query, rows, List.of("email"), List.of(), Map.of()));
        assertEquals("insert into \"users\" (\"email\", \"name\") values (?, ?) on conflict (\"email\") "
                + "do update set \"votes\" = ?, \"name\" = lower(\"name\")",
                grammar.compileUpsert(query, rows, List.of("email"), List.of(),
                        row("votes", 3, "name", Expression.raw("lower(\"name\")"))));
    }

    @Test
    void aSecondUpsertUpdatesTheExistingRowsInsteadOfDuplicating() {
        List<Map<String, Object>> first = List.of(row("email", "a@x", "name", "A", "votes", 1),
                row("email", "b@x", "name", "B", "votes", 2));
        List<Map<String, Object>> second = List.of(row("email", "a@x", "name", "A2", "votes", 10),
                row("email", "c@x", "name", "C", "votes", 3));

        db.table("users").upsert(first, List.of("email"));
        db.table("users").upsert(second, List.of("email"));

        assertEquals(3L, db.table("users").count());
        assertEquals("A2", db.table("users").where("email", "a@x").value("name").orElseThrow());
        assertEquals(10, ((Number) db.table("users").where("email", "a@x").value("votes").orElseThrow()).intValue());
        assertEquals("B", db.table("users").where("email", "b@x").value("name").orElseThrow());
    }

    @Test
    void upsertWithNamedColumnsChangesOnlyThose() {
        db.table("users").upsert(List.of(row("email", "a@x", "name", "A", "votes", 1)), List.of("email"));

        db.table("users").upsert(List.of(row("email", "a@x", "name", "Z", "votes", 7)), List.of("email"),
                List.of("votes"));

        assertEquals("A", db.table("users").where("email", "a@x").value("name").orElseThrow());
        assertEquals(7, ((Number) db.table("users").where("email", "a@x").value("votes").orElseThrow()).intValue());
    }

    @Test
    void upsertWithAValueMapBindsAfterTheRowValues() {
        db.table("users").upsert(List.of(row("email", "a@x", "name", "A", "votes", 1)), List.of("email"));

        db.table("users").upsert(List.of(row("email", "a@x", "name", "Z", "votes", 7)), List.of("email"),
                row("votes", 40, "name", Expression.raw("upper(excluded.name)")));

        assertEquals("Z", db.table("users").where("email", "a@x").value("name").orElseThrow());
        assertEquals(40, ((Number) db.table("users").where("email", "a@x").value("votes").orElseThrow()).intValue());
    }

    @Test
    void anEmptyUpdateKeepsTheExistingRow() {
        db.table("users").insert(row("email", "a@x", "name", "A"));

        db.table("users").upsert(List.of(row("email", "a@x", "name", "Z")), List.of("email"), List.<String>of());

        assertEquals("A", db.table("users").where("email", "a@x").value("name").orElseThrow());
    }

    @Test
    void uniqueColumnsWithoutAUniqueIndexReachTheCallerAsAQueryException() {
        assertThrows(QueryException.class, () -> db.table("users")
                .upsert(List.of(row("email", "a@x", "name", "A")), List.of("name")));
    }

    @Test
    void insertOrIgnoreSkipsDuplicatesAndCountsTheRowsInserted() {
        db.table("users").insert(row("email", "a@x", "name", "A"));

        int added = db.table("users").insertOrIgnore(List.of(row("email", "a@x", "name", "Z"),
                row("email", "b@x", "name", "B")));

        assertEquals(1, added);
        assertEquals(2L, db.table("users").count());
        assertEquals("A", db.table("users").where("email", "a@x").value("name").orElseThrow());
        assertEquals(0, db.table("users").insertOrIgnore(row("email", "b@x", "name", "Q")));
    }

    @Test
    void updateOrInsertInsertsThenUpdatesTheSameRow() {
        assertTrue(db.table("users").updateOrInsert(row("email", "a@x"), row("name", "A", "votes", 1)));
        assertTrue(db.table("users").updateOrInsert(row("email", "a@x"), row("votes", 5)));
        assertTrue(db.table("users").updateOrInsert(row("email", "a@x")));

        assertEquals(1L, db.table("users").count());
        assertEquals(5, ((Number) db.table("users").where("email", "a@x").value("votes").orElseThrow()).intValue());
        assertEquals("A", db.table("users").where("email", "a@x").value("name").orElseThrow());
    }

    @Test
    void updateOrInsertUpdatesOnlyOneOfSeveralMatches() {
        db.table("users").insert(List.of(row("email", "a@x", "name", "same", "votes", 0),
                row("email", "b@x", "name", "same", "votes", 0)));

        db.table("users").updateOrInsert(row("name", "same"), row("votes", 9));

        assertEquals(1L, db.table("users").where("votes", 9).count());
    }

    @Test
    void theVersionGateAcceptsSqlite324AndLaterAndFailsClosedOtherwise() {
        SQLiteQueryGrammar grammar = new SQLiteQueryGrammar("");

        grammar.checkUpsert("3.24.0");
        grammar.checkUpsert("3.45.1");
        UnsupportedOperationException old = assertThrows(UnsupportedOperationException.class,
                () -> grammar.checkUpsert("3.22.0"));
        assertEquals("Upsert needs SQLite 3.24 or later (Android API 30+); this database is 3.22.0", old.getMessage());
        UnsupportedOperationException unreadable = assertThrows(UnsupportedOperationException.class,
                () -> grammar.checkUpsert("junk"));
        assertEquals("Cannot read the SQLite version 'junk'; upsert needs SQLite 3.24 or later",
                unreadable.getMessage());
    }

    @Test
    void sqliteVersionComparesEachPartAsAWholeNumber() {
        assertTrue(SqliteVersion.atLeast("3.45.1", 3, 24, 0));
        assertTrue(SqliteVersion.atLeast("3.24", 3, 24, 0));
        assertTrue(SqliteVersion.atLeast("4.0", 3, 24, 0));
        assertTrue(SqliteVersion.atLeast("3.45.1-rc1", 3, 45, 1));
        assertFalse(SqliteVersion.atLeast("3.9.9", 3, 24, 0));
        assertFalse(SqliteVersion.atLeast("3.22.0", 3, 24, 0));
        assertFalse(SqliteVersion.atLeast("3.45.0", 3, 45, 1));
        assertFalse(SqliteVersion.atLeast("junk", 3, 24, 0));
        assertFalse(SqliteVersion.atLeast("3.x", 3, 24, 0));
        assertFalse(SqliteVersion.atLeast("", 3, 24, 0));
        assertFalse(SqliteVersion.atLeast(null, 3, 24, 0));
        assertTrue(SqliteVersion.isReadable("3.45.1-rc1"));
        assertTrue(SqliteVersion.isReadable("3"));
        assertFalse(SqliteVersion.isReadable("junk"));
        assertFalse(SqliteVersion.isReadable("3.x"));
        assertFalse(SqliteVersion.isReadable(""));
        assertFalse(SqliteVersion.isReadable(null));
    }
}
