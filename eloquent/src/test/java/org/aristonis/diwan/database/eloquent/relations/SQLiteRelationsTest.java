package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.CountryModels.City;
import org.aristonis.diwan.database.eloquent.relations.CountryModels.Country;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Profile;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Relations on SQLite: lazy SQL and string keys chunked at 900. */
class SQLiteRelationsTest {

    private static final int COUNTRIES = 2_000;

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
                + "(select 1 union all select n + 1 from s where n < " + COUNTRIES + ") "
                + "select 'c' || n, 'name' from s");
        db.real.statement("insert into cities (country_code, name) select code, 'city' from countries");
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void lazyRelationsUseLaravelSql() {
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("profiles", Map.of("id", 7, "user_id", 1, "bio", "hi"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "p"));
        db.seed("comments", Map.of("id", 1, "post_id", 1, "body", "x"));
        db.seed("comments", Map.of("id", 2, "post_id", 1, "body", "y"));
        Post post = Post.QUERY.on(db.db).findOrFail(1);
        User user = User.QUERY.on(db.db).findOrFail(1);
        db.clearLog();

        ModelCollection<Comment> comments = post.relatedMany("comments");
        User author = post.related("author");
        Profile profile = user.related("profile");

        assertEquals(2, comments.size());
        assertEquals(1, author.getKey());
        assertEquals(7, profile.getKey());
        assertEquals("select * from \"comments\" where \"comments\".\"post_id\" = ?"
                + " and \"comments\".\"post_id\" is not null", db.sql().get(0));
        assertEquals("select * from \"users\" where \"users\".\"id\" = ? limit 1", db.sql().get(1));
        assertEquals("select * from \"profiles\" where \"profiles\".\"user_id\" = ?"
                + " and \"profiles\".\"user_id\" is not null limit 1", db.sql().get(2));
    }

    @Test
    void stringKeysChunkedAt900() {
        db.clearLog();

        ModelCollection<Country> countries = Country.QUERY.on(db.db).with("cities").get();

        assertEquals(COUNTRIES, countries.size());
        assertEquals(1 + 3, db.sql().size());
        assertTrue(db.sql().get(0).startsWith("select * from \"countries\""), db.sql().get(0));
        assertEquals(900, db.queries().get(1).bindings().size());
        assertEquals(900, db.queries().get(2).bindings().size());
        assertEquals(200, db.queries().get(3).bindings().size());
        for (Country country : countries) {
            ModelCollection<City> cities = country.relatedMany("cities");
            assertEquals(1, cities.size());
            assertEquals(country.getKey(), cities.get(0).getAttribute("country_code"));
        }
    }

    @Test
    void constrainedStringKeysStayUnderTheBindingLimit() {
        db.clearLog();

        ModelCollection<Country> countries = Country.QUERY.on(db.db)
                .with("cities", q -> q.where("name", "city")).get();

        assertEquals(COUNTRIES, countries.size());
        assertEquals(Collections.nCopies(3, true),
                db.queries().subList(1, 4).stream().map(q -> q.bindings().size() <= 900).toList());
        assertEquals(1, countries.get(1999).relatedMany("cities").size());
    }

    @Test
    void numericLookingTextKeysBindAsStrings() {
        db.real.statement("insert into countries (code, name) values ('123', 'n')");
        db.real.statement("insert into cities (country_code, name) values ('123', 'x')");
        db.clearLog();

        ModelCollection<Country> countries = Country.QUERY.on(db.db).query().whereIn("code", Arrays.asList("123", "c1"))
                .with("cities").orderBy("code").get();

        assertEquals(Arrays.asList("123", "c1"), countries.modelKeys());
        assertEquals(1, countries.get(0).relatedMany("cities").size());
        assertEquals(Arrays.asList("123", "c1"), db.queries().get(1).bindings());
        assertEquals(String.class, db.queries().get(1).bindings().get(0).getClass());
    }
}
