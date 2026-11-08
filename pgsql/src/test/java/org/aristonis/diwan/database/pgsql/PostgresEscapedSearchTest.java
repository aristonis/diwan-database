package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

class PostgresEscapedSearchTest {

    private static QueryFactory people() {
        Connection connection = PostgresServer.newDatabase();
        PostgresSchema.of(connection).create("people", t -> {
            t.id();
            t.string("name");
        });
        QueryFactory db = PostgresQuery.of(connection);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : NAMES) {
            rows.add(Map.of("name", name));
        }
        db.table("people").insert(rows);
        return db;
    }

    private static final String[] NAMES = {"50%", "500", "a_c", "abc", "back\\slash", "star*", "starX", "q?", "qZ",
            "[x]", "x", "Ali", "ali", "bang!", "bangX"};

    private static List<Object> names(Builder query) {
        return query.orderBy("id").pluck("name");
    }

    @Test
    void percentIsLiteral() {
        QueryFactory db = people();

        assertEquals(List.of("50%"), names(db.table("people").whereContains("name", "50%")));
        assertEquals(List.of("50%"), names(db.table("people").whereContains("name", "50%", true)));
        assertEquals(List.of("50%"), names(db.table("people").whereStartsWith("name", "50%")));
    }

    @Test
    void specialCharsLiteral() {
        QueryFactory db = people();
        String[][] cases = {{"_", "a_c"}, {"\\", "back\\slash"}, {"*", "star*"}, {"?", "q?"}, {"[", "[x]"},
                {"!", "bang!"}, {"%", "50%"}};

        for (String[] c : cases) {
            assertEquals(List.of(c[1]), names(db.table("people").whereContains("name", c[0])), c[0]);
            assertEquals(List.of(c[1]), names(db.table("people").whereContains("name", c[0], true)), c[0]);
        }
        assertEquals(List.of("[x]"), names(db.table("people").whereStartsWith("name", "[", true)));
        assertEquals(List.of("star*"), names(db.table("people").whereEndsWith("name", "*", true)));
        assertEquals(List.of("a_c"), names(db.table("people").whereContains("name", "a_c", true)));
    }

    @Test
    void caseModes() {
        QueryFactory db = people();

        assertEquals(List.of("Ali", "ali"), names(db.table("people").whereContains("name", "ali")));
        assertEquals(List.of("Ali", "ali"), names(db.table("people").whereContains("name", "ali", false)));
        assertEquals(List.of("ali"), names(db.table("people").whereContains("name", "ali", true)));
    }

    @Test
    void anchorsAndBooleans() {
        QueryFactory db = people();

        assertEquals(List.of("a_c", "abc", "Ali", "ali"), names(db.table("people").whereStartsWith("name", "a")));
        assertEquals(List.of("a_c", "abc", "ali"), names(db.table("people").whereStartsWith("name", "a")
                .whereNotContains("name", "Al", true)));
        assertEquals(List.of("starX", "x", "bangX"), names(db.table("people").whereEndsWith("name", "x")));
        assertEquals(List.of("x"), names(db.table("people").whereEndsWith("name", "x", true)));
        assertEquals(List.of("bangX"), names(db.table("people").whereContains("name", "bang")
                .whereNotContains("name", "!")));
        assertEquals(List.of("50%", "500", "a_c", "abc"), names(db.table("people").whereStartsWith("name", "50")
                .orWhereEndsWith("name", "c")));
        assertEquals(List.of("50%", "500"), names(db.table("people").whereStartsWith("name", "50")
                .orWhereNotEndsWith("name", "c").whereNotContains("name", "a")
                .whereNotContains("name", "q").whereNotContains("name", "s").whereNotContains("name", "[")
                .whereNotContains("name", "x").whereNotContains("name", "i").whereNotContains("name", "b")));
        assertEquals(15L, db.table("people").whereContains("name", "").count());
        assertEquals(15L, db.table("people").whereContains("name", "", true).count());
    }
}
