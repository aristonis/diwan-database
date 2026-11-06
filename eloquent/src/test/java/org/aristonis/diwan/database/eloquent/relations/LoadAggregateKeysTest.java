package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.CountryModels.Country;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Key collection and chunking of {@code load*}: the query
 * count per key kind and grammar, the constraint bindings taken off the bound chunk, the budget
 * throw and key normalisation.
 */
class LoadAggregateKeysTest {

    private static final int ROWS = 2_000;
    private static final int SQLITE_CHUNK = 900;
    private static final String COUNT_CITIES = "select \"code\", (select count(*) from \"cities\" where"
            + " \"countries\".\"code\" = \"cities\".\"country_code\") as \"cities_count\" from \"countries\""
            + " where \"countries\".\"code\" in (";

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        CountryModels.register();
        SQLiteSchema.of(db.real).create("countries", t -> {
            t.string("code").primary();
            t.string("name");
        });
        SQLiteSchema.of(db.real).create("cities", t -> {
            t.id();
            t.string("country_code");
            t.string("name");
        });
        db.real.statement("insert into countries (code, name) with recursive s(n) as "
                + "(select 1 union all select n + 1 from s where n < " + ROWS + ") "
                + "select 'c' || n, 'name' from s");
        db.real.statement("insert into cities (country_code, name) select code, 'city' from countries");
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> relation(String name,
            Consumer<EloquentBuilder<?>> constraint) {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = new LinkedHashMap<>();
        parsed.put(name, constraint);
        return parsed;
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> relation(String name) {
        return relation(name, EagerLoader.NO_OP);
    }

    private static long number(Model model, String key) {
        return ((Number) model.getAttribute(key)).longValue();
    }

    private List<Integer> bindingCounts() {
        List<Integer> counts = new ArrayList<>();
        for (RelationDb.Query query : db.queries()) {
            counts.add(query.bindings().size());
        }
        return counts;
    }

    @Test
    void stringKeysChunkedOnSQLite() {
        ModelCollection<Country> countries = Country.QUERY.on(db.db).query().get();
        db.clearLog();

        AggregateLoader.load(countries, relation("cities"), "*", "count");

        assertEquals(Arrays.asList(SQLITE_CHUNK, SQLITE_CHUNK, ROWS - 2 * SQLITE_CHUNK), bindingCounts());
        assertTrue(db.sql().get(0).startsWith(COUNT_CITIES), db.sql().get(0));
        for (Country country : countries) {
            assertEquals(1, number(country, "cities_count"));
        }
    }

    @Test
    void stringKeysOneQueryOnPostgres() {
        QueryLog log = new QueryLog();
        ModelCollection<Country> countries = Country.QUERY.on(PostgresQuery.of(log.wrap(db.real))).query().get();
        log.clear();

        AggregateLoader.load(countries, relation("cities"), "*", "count");

        assertEquals(Collections.singletonList(ROWS), log.bindingCounts());
        assertTrue(log.sql().get(0).startsWith(COUNT_CITIES), log.sql().get(0));
        assertEquals(1, number(countries.get(ROWS - 1), "cities_count"));
    }

    @Test
    void integerKeysInlined() {
        db.real.statement("insert into users (id, name) with recursive s(n) as "
                + "(select 1 union all select n + 1 from s where n < " + ROWS + ") select n, 'u' from s");
        db.real.statement("insert into posts (user_id, title) values (1, 'a'), (1, 'b'), (" + ROWS + ", 'c')");
        ModelCollection<User> users = User.QUERY.on(db.db).query().get();
        db.clearLog();

        AggregateLoader.load(users, relation("posts"), "*", "count");

        assertEquals(Collections.singletonList(0), bindingCounts());
        assertTrue(db.sql().get(0).contains(" where \"users\".\"id\" in (1, 2, 3, "), db.sql().get(0));
        assertEquals(2, number(users.get(0), "posts_count"));
        assertEquals(0, number(users.get(1), "posts_count"));
        assertEquals(1, number(users.get(ROWS - 1), "posts_count"));
    }

    @Test
    void constraintBindingsShrinkTheChunk() {
        ModelCollection<Country> countries = Country.QUERY.on(db.db).query().get();
        db.clearLog();

        AggregateLoader.load(countries, relation("cities", q -> q.where("name", "city")), "*", "count");

        int keys = SQLITE_CHUNK - 1;
        assertEquals(Arrays.asList(SQLITE_CHUNK, SQLITE_CHUNK, ROWS - 2 * keys + 1), bindingCounts());
        assertEquals(1, number(countries.get(ROWS - 1), "cities_count"));
    }

    @Test
    void constraintRunsOnceForEveryChunk() {
        ModelCollection<Country> countries = Country.QUERY.on(db.db).query().get();
        AtomicInteger runs = new AtomicInteger();
        db.clearLog();

        AggregateLoader.load(countries, relation("cities", q -> runs.incrementAndGet()), "*", "count");

        assertEquals(3, db.sql().size());
        assertEquals(1, runs.get());
    }

    @Test
    void exhaustedBudgetThrowsBeforeAnyQuery() {
        ModelCollection<Country> countries = Country.QUERY.on(db.db).query().get();
        List<String> names = Collections.nCopies(SQLITE_CHUNK, "city");
        db.clearLog();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AggregateLoader.load(countries, relation("cities", q -> q.whereIn("name", names)), "*",
                        "count"));

        assertTrue(thrown.getMessage().contains("[cities]"), thrown.getMessage());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void keysNormalisedAndDeduplicated() {
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        User loaded = User.QUERY.on(db.db).findOrFail(1);
        User sameKeyAsText = User.QUERY.on(db.db).findOrFail(1);
        sameKeyAsText.forceFill(Map.of("id", "1"));
        User second = User.QUERY.on(db.db).findOrFail(2);
        db.clearLog();

        AggregateLoader.load(Arrays.asList(second, loaded, sameKeyAsText), relation("posts"), "*", "count");

        assertEquals(1, db.sql().size());
        assertTrue(db.sql().get(0).endsWith(" where \"users\".\"id\" in (1, 2)"), db.sql().get(0));
        assertEquals(2, number(loaded, "posts_count"));
        assertEquals(2, number(sameKeyAsText, "posts_count"));
        assertEquals(0, number(second, "posts_count"));
    }

    @Test
    void mixedClassesThrowBeforeAnyQuery() {
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        User user = User.QUERY.on(db.db).findOrFail(1);
        Country country = Country.QUERY.on(db.db).query().get().get(0);
        db.clearLog();

        assertThrows(IllegalArgumentException.class,
                () -> AggregateLoader.load(Arrays.asList(user, country), relation("posts"), "*", "count"));
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void unknownNameRejectedWhenNoModelHasAKey() {
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        User user = User.QUERY.on(db.db).findOrFail(1);
        user.setAttribute("id", null);
        db.clearLog();

        assertThrows(RelationNotFoundException.class,
                () -> AggregateLoader.load(Collections.singletonList(user), relation("postz"), "*", "count"));
        assertEquals(Collections.emptyList(), db.sql());
    }
}
