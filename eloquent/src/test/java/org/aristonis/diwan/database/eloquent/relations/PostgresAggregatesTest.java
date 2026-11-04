package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.eloquent.relations.ChaperoneTest.Member;
import org.aristonis.diwan.database.eloquent.relations.CountryModels.Country;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TComment;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aggregates, load aggregates, touches, push and chaperone executed on a throwaway PostgreSQL:
 * driver types, {@code loadCount} over 2,000 string keys in one query and the
 * touch and push writes. Skipped when the server binaries are missing, and failing when
 * {@code DIWAN_PG_REQUIRED} is set. Timestamp columns are text, as the model binds text.
 */
class PostgresAggregatesTest {

    private static final String OLD = TouchDb.OLD;
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
        TouchDb.TouchModels.register();
        if (Member.QUERY == null) {
            throw new IllegalStateException("a model entry is not initialised");
        }
        connection.statement("create table users (id bigserial primary key, name varchar(255),"
                + " created_at varchar(32), updated_at varchar(32))");
        connection.statement("create table posts (id bigserial primary key, user_id bigint, title varchar(255),"
                + " votes bigint, created_at varchar(32), updated_at varchar(32))");
        connection.statement("create table comments (id bigserial primary key, post_id bigint, topic_id bigint,"
                + " body varchar(255), created_at varchar(32), updated_at varchar(32))");
        connection.statement("create table profiles (id bigserial primary key, user_id bigint, bio varchar(255))");
        connection.statement("create table countries (code varchar(255) primary key, name varchar(255) not null)");
        connection.statement("create table cities (id bigserial primary key, country_code varchar(255),"
                + " name varchar(255) not null)");
        connection.statement("insert into users (id, name, created_at, updated_at) values"
                + " (1, 'Ann', '" + OLD + "', '" + OLD + "'), (2, 'Bob', '" + OLD + "', '" + OLD + "'),"
                + " (3, 'Cid', '" + OLD + "', '" + OLD + "')");
        connection.statement("insert into posts (id, user_id, title, votes, created_at, updated_at) values"
                + " (1, 1, 'a', 3, '" + OLD + "', '" + OLD + "'), (2, 1, 'b', 5, '" + OLD + "', '" + OLD + "'),"
                + " (3, 2, 'c', 10, '" + OLD + "', '" + OLD + "')");
        connection.statement("insert into profiles (id, user_id, bio) values (1, 1, 'hi')");
        connection.statement("insert into comments (id, post_id, body, created_at, updated_at) values"
                + " (1, 1, 'x', '" + OLD + "', '" + OLD + "')");
    }

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    private Object cell(String sql) {
        return connection.selectOne(sql).orElseThrow(IllegalStateException::new).get("v");
    }

    private Object updatedAt(String table, long id) {
        return cell("select updated_at as v from \"" + table + "\" where id = " + id);
    }

    // ---- aggregate values and driver types

    @Test
    void countAndEmptyRelationValues() {
        ModelCollection<User> users = User.QUERY.on(db).query().withCount("posts").withMax("posts", "votes")
                .withSum("posts", "votes").orderBy("id").get();

        assertEquals(Long.class, users.get(0).getAttribute("posts_count").getClass());
        assertEquals(2L, number(users.get(0), "posts_count"));
        assertEquals(0L, number(users.get(2), "posts_count"));
        assertNull(users.get(2).getAttribute("posts_max_votes"));
        assertNull(users.get(2).getAttribute("posts_sum_votes"));
        assertEquals(5L, number(users.get(0), "posts_max_votes"));
    }

    @Test
    void sumKeepsDriverType() {
        Object raw = cell("select sum(votes) as v from posts where user_id = 1");
        Object rawAvg = cell("select avg(votes) as v from posts where user_id = 1");

        User ann = User.QUERY.on(db).query().withSum("posts", "votes").withAvg("posts", "votes").findOrFail(1);

        Object sum = ann.getAttribute("posts_sum_votes");
        assertTrue(sum instanceof BigDecimal || sum instanceof Long, String.valueOf(sum));
        assertEquals(raw.getClass(), sum.getClass());
        assertEquals(8L, ((Number) sum).longValue());
        assertEquals(rawAvg.getClass(), ann.getAttribute("posts_avg_votes").getClass());
        assertEquals(4.0, ((Number) ann.getAttribute("posts_avg_votes")).doubleValue(), 0.0);
    }

    @Test
    void existsBoolean() {
        Object raw = cell("select exists(select * from profiles where user_id = 1) as v");
        assertEquals(Boolean.class, raw.getClass());

        ModelCollection<User> users = User.QUERY.on(db).query().withExists("profile").orderBy("id").get();

        assertEquals(Boolean.TRUE, users.get(0).getAttribute("profile_exists"));
        assertEquals(Boolean.FALSE, users.get(1).getAttribute("profile_exists"));
        assertEquals(Boolean.FALSE, users.get(2).getAttribute("profile_exists"));
    }

    @Test
    void allAggregatesRunInOneSelect() {
        log.clear();

        User.QUERY.on(db).query().withCount("posts").withMax("posts", "votes").withMin("posts", "votes")
                .withSum("posts", "votes").withAvg("posts", "votes").withExists("profile").findOrFail(1);

        assertEquals(1, log.sql().size());
    }

    // ---- load aggregates

    @Test
    void loadCountOverStringKeysRunsOneQuery() {
        connection.statement("insert into countries (code, name) select 'c' || g, 'name' "
                + "from generate_series(1, " + COUNTRIES + ") g");
        connection.statement("insert into cities (country_code, name) select code, 'city' from countries");
        ModelCollection<Country> countries = Country.QUERY.on(db).query().get();
        log.clear();

        countries.loadCount("cities");

        assertEquals(COUNTRIES, countries.size());
        assertEquals(Collections.singletonList(COUNTRIES), log.bindingCounts());
        assertEquals(1, log.sql().size());
        assertEquals(1L, number(countries.get(COUNTRIES - 1), "cities_count"));
    }

    @Test
    void loadCountAndLoadExistsOnAModel() {
        User ann = User.QUERY.on(db).findOrFail(1);

        ann.loadCount("posts");
        ann.loadExists("profile");

        assertEquals(2L, number(ann, "posts_count"));
        assertEquals(Boolean.TRUE, ann.getAttribute("profile_exists"));
    }

    // ---- touch and push

    @Test
    void savingADirtyChildExecutesTheTouchUpdate() {
        TComment comment = TComment.QUERY.on(db).findOrFail(1);
        comment.setAttribute("body", "changed");

        assertTrue(comment.save());

        assertNotEquals(OLD, updatedAt("posts", 1));
        assertEquals(OLD, updatedAt("posts", 2));
        assertEquals("changed", cell("select body as v from comments where id = 1"));
    }

    @Test
    void pushWritesTheModelAndItsLoadedRelations() {
        User ann = User.QUERY.on(db).query().with("posts").findOrFail(1);
        ann.setAttribute("name", "Ann2");
        ModelCollection<Post> posts = ann.relatedMany("posts");
        posts.get(0).setAttribute("title", "a2");

        assertTrue(ann.push());

        assertEquals("Ann2", cell("select name as v from users where id = 1"));
        assertEquals("a2", cell("select title as v from posts where id = 1"));
        assertEquals("b", cell("select title as v from posts where id = 2"));
    }

    // ---- chaperone

    @Test
    void chaperoneRunsTheSameQueriesWithAndWithoutIt() {
        Member.QUERY.on(db).query().with("notes").orderBy("id").get();
        List<String> plain = log.sql();
        log.clear();

        Member.QUERY.on(db).query().with("linkedNotes").orderBy("id").get();
        List<String> chaperoned = log.sql();

        assertEquals(2, plain.size());
        assertEquals(plain, chaperoned);
    }
}
