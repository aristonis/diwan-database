package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.query.SearchOptions;
import org.aristonis.diwan.database.query.SearchWhere;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

@SuppressWarnings("removal")
class EscapedSearchDbTest {

    @TempDir
    Path folder;

    private QueryFactory people() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("search.db"));
        Connection connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        Schema schema = SQLiteSchema.of(connection);
        schema.create("people", t -> {
            t.id();
            t.string("name");
        });
        QueryFactory db = SQLiteQuery.of(connection);
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

    @Test
    void defaultOptionsMatchTheFlaglessForm() {
        QueryFactory db = people();
        SearchOptions defaults = SearchOptions.DEFAULT;

        assertEquals(names(db.table("people").whereContains("name", "ali")),
                names(db.table("people").whereContains("name", "ali", defaults)));
        assertEquals(names(db.table("people").whereStartsWith("name", "a")),
                names(db.table("people").whereStartsWith("name", "a", defaults)));
        assertEquals(names(db.table("people").whereEndsWith("name", "x")),
                names(db.table("people").whereEndsWith("name", "x", defaults)));
        assertEquals(List.of("Ali", "ali"), names(db.table("people").whereContains("name", "ali", defaults)));
    }

    @Test
    void caseSensitiveOptionsMatchOnlyTheSameCase() {
        QueryFactory db = people();
        SearchOptions sensitive = SearchOptions.caseSensitive();

        assertEquals(List.of("ali"), names(db.table("people").whereContains("name", "ali", sensitive)));
        assertEquals(List.of("x"), names(db.table("people").whereEndsWith("name", "x", sensitive)));
        assertEquals(List.of("a_c", "abc", "ali"), names(db.table("people").whereStartsWith("name", "a", sensitive)
                .whereNotContains("name", "Al", sensitive)));
    }

    @Test
    void optionsFormsTreatPercentUnderscoreAndBangLiterally() {
        QueryFactory db = people();
        String[][] cases = {{"50%", "50%"}, {"a_c", "a_c"}, {"!", "bang!"}, {"[", "[x]"}, {"*", "star*"}};

        for (SearchOptions options : new SearchOptions[] {SearchOptions.DEFAULT, SearchOptions.caseSensitive()}) {
            for (String[] c : cases) {
                assertEquals(List.of(c[1]), names(db.table("people").whereContains("name", c[0], options)), c[0]);
            }
        }
    }

    @Test
    void optionsAndBooleanFormsCompileTheSameSqlAndBindings() {
        QueryFactory db = people();

        for (boolean caseSensitive : new boolean[] {false, true}) {
            Builder flag = db.table("people").whereContains("name", "a", caseSensitive)
                    .orWhereNotStartsWith("name", "b", caseSensitive);
            Builder options = db.table("people")
                    .whereContains("name", "a", SearchOptions.DEFAULT.withCaseSensitive(caseSensitive))
                    .orWhereNotStartsWith("name", "b", SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));

            assertEquals(flag.toSql(), options.toSql());
            assertEquals(flag.getBindings(), options.getBindings());
        }
    }

    @Test
    void grammarBooleanAndOptionsPatternsAgree() {
        SQLiteQueryGrammar grammar = new SQLiteQueryGrammar("");

        for (boolean caseSensitive : new boolean[] {false, true}) {
            String flag = grammar.searchPattern("a*[_", SearchWhere.Anchor.CONTAINS, caseSensitive);
            String options = grammar.searchPattern("a*[_", SearchWhere.Anchor.CONTAINS,
                    SearchOptions.DEFAULT.withCaseSensitive(caseSensitive));

            assertEquals(flag, options);
        }
        assertEquals("*a[*][[]_*", grammar.searchPattern("a*[_", SearchWhere.Anchor.CONTAINS, true));
    }
}
