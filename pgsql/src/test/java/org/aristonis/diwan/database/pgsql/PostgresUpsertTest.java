package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Expression;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

/** Upsert, insertOrIgnore and updateOrInsert against a real PostgreSQL server. */
class PostgresUpsertTest {

    private static QueryFactory users() {
        Connection connection = PostgresServer.newDatabase();
        PostgresSchema.of(connection).create("users", t -> {
            t.id();
            t.string("email").unique();
            t.string("name");
            t.integer("votes").defaultValue(0);
        });
        return PostgresQuery.of(connection);
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static int votes(QueryFactory db, String email) {
        return ((Number) db.table("users").where("email", email).value("votes").orElseThrow()).intValue();
    }

    @Test
    void theGrammarWritesOnConflictDoNothingAndDoUpdate() {
        PostgresQueryGrammar grammar = new PostgresQueryGrammar("");
        Builder query = PostgresQuery.of(PostgresServer.newDatabase()).table("users");
        List<Map<String, Object>> rows = List.of(row("email", "a", "name", "A"));

        assertEquals("insert into \"users\" (\"email\", \"name\") values (?, ?) on conflict do nothing",
                grammar.compileInsertOrIgnore(query, rows));
        assertEquals("insert into \"users\" (\"email\", \"name\") values (?, ?) on conflict (\"email\") "
                + "do update set \"name\" = \"excluded\".\"name\"",
                grammar.compileUpsert(query, rows, List.of("email"), List.of("name"), Map.of()));
        assertEquals("insert into \"users\" (\"email\", \"name\") values (?, ?) on conflict (\"email\") do nothing",
                grammar.compileUpsert(query, rows, List.of("email"), List.of(), Map.of()));
    }

    @Test
    void aSecondUpsertUpdatesTheExistingRowsInsteadOfDuplicating() {
        QueryFactory db = users();

        db.table("users").upsert(List.of(row("email", "a@x", "name", "A", "votes", 1),
                row("email", "b@x", "name", "B", "votes", 2)), List.of("email"));
        db.table("users").upsert(List.of(row("email", "a@x", "name", "A2", "votes", 10),
                row("email", "c@x", "name", "C", "votes", 3)), List.of("email"));

        assertEquals(3L, db.table("users").count());
        assertEquals("A2", db.table("users").where("email", "a@x").value("name").orElseThrow());
        assertEquals(10, votes(db, "a@x"));
        assertEquals("B", db.table("users").where("email", "b@x").value("name").orElseThrow());
    }

    @Test
    void upsertWithAValueMapBindsAfterTheRowValues() {
        QueryFactory db = users();
        db.table("users").insert(row("email", "a@x", "name", "A", "votes", 1));

        db.table("users").upsert(List.of(row("email", "a@x", "name", "Z", "votes", 7)), List.of("email"),
                row("votes", 40, "name", Expression.raw("upper(excluded.name)")));

        assertEquals("Z", db.table("users").where("email", "a@x").value("name").orElseThrow());
        assertEquals(40, votes(db, "a@x"));
    }

    @Test
    void anEmptyUpdateKeepsTheExistingRow() {
        QueryFactory db = users();
        db.table("users").insert(row("email", "a@x", "name", "A"));

        db.table("users").upsert(List.of(row("email", "a@x", "name", "Z")), List.of("email"), List.<String>of());

        assertEquals("A", db.table("users").where("email", "a@x").value("name").orElseThrow());
    }

    @Test
    void uniqueColumnsWithoutAUniqueIndexReachTheCallerAsAQueryException() {
        QueryFactory db = users();

        assertThrows(QueryException.class, () -> db.table("users")
                .upsert(List.of(row("email", "a@x", "name", "A")), List.of("name")));
    }

    @Test
    void insertOrIgnoreSkipsDuplicatesAndCountsTheRowsInserted() {
        QueryFactory db = users();
        db.table("users").insert(row("email", "a@x", "name", "A"));

        int added = db.table("users").insertOrIgnore(List.of(row("email", "a@x", "name", "Z"),
                row("email", "b@x", "name", "B")));

        assertEquals(1, added);
        assertEquals(2L, db.table("users").count());
        assertEquals("A", db.table("users").where("email", "a@x").value("name").orElseThrow());
    }

    @Test
    void updateOrInsertInsertsThenUpdatesTheSameRow() {
        QueryFactory db = users();

        db.table("users").updateOrInsert(row("email", "a@x"), row("name", "A", "votes", 1));
        db.table("users").updateOrInsert(row("email", "a@x"), row("votes", 5));

        assertEquals(1L, db.table("users").count());
        assertEquals(5, votes(db, "a@x"));
    }
}
