package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.eloquent.relations.CountryModels.City;
import org.aristonis.diwan.database.eloquent.relations.CountryModels.Country;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Profile;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Relations on a throwaway PostgreSQL: lazy SQL and string keys in one query.
 * Skipped when the server binaries are missing, and failing when {@code DIWAN_PG_REQUIRED} is set.
 */
class PostgresRelationsTest {

    private static final int COUNTRIES = 2_000;

    private final QueryLog log = new QueryLog();
    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void open() {
        connection = PostgresServer.newDatabase();
        db = PostgresQuery.of(log.wrap(connection));
        RelationModels.register();
        CountryModels.register();
        connection.statement("create table users (id bigserial primary key, name varchar(255) not null)");
        connection.statement("create table posts (id bigserial primary key, user_id bigint, title varchar(255) not null)");
        connection.statement("create table comments (id bigserial primary key, post_id bigint, body varchar(255) not null)");
        connection.statement("create table profiles (id bigserial primary key, user_id bigint, bio varchar(255) not null)");
        connection.statement("create table countries (code varchar(255) primary key, name varchar(255) not null)");
        connection.statement("create table cities (id bigserial primary key, country_code varchar(255), name varchar(255) not null)");
    }

    @Test
    void lazyRelationsUseLaravelSql() {
        connection.statement("insert into users (id, name) values (1, 'Ann')");
        connection.statement("insert into profiles (id, user_id, bio) values (7, 1, 'hi')");
        connection.statement("insert into posts (id, user_id, title) values (1, 1, 'p')");
        connection.statement("insert into comments (post_id, body) values (1, 'x'), (1, 'y')");
        Post post = Post.QUERY.on(db).findOrFail(1);
        User user = User.QUERY.on(db).findOrFail(1);
        log.clear();

        ModelCollection<Comment> comments = post.relatedMany("comments");
        User author = post.related("author");
        Profile profile = user.related("profile");

        assertEquals(2, comments.size());
        assertEquals(1L, author.getKey());
        assertEquals(7L, profile.getKey());
        assertEquals("select * from \"comments\" where \"comments\".\"post_id\" = ?"
                + " and \"comments\".\"post_id\" is not null", log.sql().get(0));
        assertEquals("select * from \"users\" where \"users\".\"id\" = ? limit 1", log.sql().get(1));
        assertEquals("select * from \"profiles\" where \"profiles\".\"user_id\" = ?"
                + " and \"profiles\".\"user_id\" is not null limit 1", log.sql().get(2));
    }

    @Test
    void stringKeysChunkedAt10000() {
        connection.statement("insert into countries (code, name) select 'c' || g, 'name' "
                + "from generate_series(1, " + COUNTRIES + ") g");
        connection.statement("insert into cities (country_code, name) select code, 'city' from countries");
        log.clear();

        ModelCollection<Country> countries = Country.QUERY.on(db).with("cities").get();

        assertEquals(COUNTRIES, countries.size());
        assertEquals(2, log.sql().size());
        assertTrue(log.sql().get(1).startsWith("select * from \"cities\""), log.sql().get(1));
        assertEquals(COUNTRIES, log.bindingCounts().get(1));
        for (Country country : countries) {
            ModelCollection<City> cities = country.relatedMany("cities");
            assertEquals(1, cities.size());
            assertEquals(country.getKey(), cities.get(0).getAttribute("country_code"));
        }
    }

    @Test
    void numericLookingTextKeysBindAsText() {
        connection.statement("insert into countries (code, name) values ('123', 'n'), ('c9', 'm')");
        connection.statement("insert into cities (country_code, name) values ('123', 'x'), ('c9', 'y')");
        log.clear();

        ModelCollection<Country> countries = Country.QUERY.on(db).with("cities").orderBy("code").get();

        assertEquals(1, countries.get(0).relatedMany("cities").size());
        assertEquals("x", countries.get(0).<City>relatedMany("cities").get(0).getAttribute("name"));
        assertEquals(1, countries.get(1).relatedMany("cities").size());
    }
}
