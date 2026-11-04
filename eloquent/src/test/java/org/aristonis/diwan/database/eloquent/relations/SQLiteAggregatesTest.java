package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.ChaperoneTest.Member;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TComment;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TPost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aggregates, load aggregates, touches, push and chaperone executed on SQLite: values and driver
 * types, {@code loadCount}, the touch UPDATE and the push writes.
 */
class SQLiteAggregatesTest {

    private static final String OLD = TouchDb.OLD;

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        TouchDb.TouchModels.register();
        if (Member.QUERY == null) {
            throw new IllegalStateException("a model entry is not initialised");
        }
        db.real.statement("alter table users add column created_at varchar(32)");
        db.real.statement("alter table users add column updated_at varchar(32)");
        db.real.statement("alter table posts add column votes integer");
        db.real.statement("alter table posts add column created_at varchar(32)");
        db.real.statement("alter table posts add column updated_at varchar(32)");
        db.real.statement("alter table comments add column topic_id integer");
        db.real.statement("alter table comments add column created_at varchar(32)");
        db.real.statement("alter table comments add column updated_at varchar(32)");
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("users", Map.of("id", 3, "name", "Cid"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a", "votes", 3));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b", "votes", 5));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c", "votes", 10));
        db.seed("profiles", Map.of("id", 1, "user_id", 1, "bio", "hi"));
        db.seed("comments", Map.of("id", 1, "post_id", 1, "body", "x"));
        for (String table : List.of("users", "posts", "comments")) {
            db.real.statement("update \"" + table + "\" set created_at = '" + OLD + "', updated_at = '" + OLD + "'");
        }
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    private String cell(String sql) {
        Object value = db.real.selectOne(sql).orElseThrow(IllegalStateException::new).get("v");
        return value == null ? null : value.toString();
    }

    private String updatedAt(String table, long id) {
        return cell("select updated_at as v from \"" + table + "\" where id = " + id);
    }

    // ---- aggregate values and driver types

    @Test
    void countKeepsDriverType() {
        ModelCollection<User> users = User.QUERY.on(db.db).query().withCount("posts").orderBy("id").get();

        Object first = users.get(0).getAttribute("posts_count");
        assertTrue(first instanceof Long || first instanceof Integer, String.valueOf(first));
        assertEquals(2L, number(users.get(0), "posts_count"));
        assertEquals(1L, number(users.get(1), "posts_count"));
        assertEquals(0L, number(users.get(2), "posts_count"));
    }

    @Test
    void emptyRelationValues() {
        User cid = User.QUERY.on(db.db).query().withMax("posts", "votes").withSum("posts", "votes")
                .withCount("posts").findOrFail(3);

        assertNull(cid.getAttribute("posts_max_votes"));
        assertNull(cid.getAttribute("posts_sum_votes"));
        assertEquals(0L, number(cid, "posts_count"));
    }

    @Test
    void allFiveAggregatesRunInOneSelectWithTheirValues() {
        db.clearLog();

        User ann = User.QUERY.on(db.db).query().withCount("posts").withMax("posts", "votes")
                .withMin("posts", "votes").withSum("posts", "votes").withAvg("posts", "votes")
                .withExists("profile").findOrFail(1);

        assertEquals(1, db.sql().size());
        assertEquals(2L, number(ann, "posts_count"));
        assertEquals(5L, number(ann, "posts_max_votes"));
        assertEquals(3L, number(ann, "posts_min_votes"));
        assertEquals(8L, number(ann, "posts_sum_votes"));
        assertEquals(4.0, ((Number) ann.getAttribute("posts_avg_votes")).doubleValue(), 0.0);
        assertEquals(Boolean.TRUE, ann.getAttribute("profile_exists"));
    }

    @Test
    void existsBoolean() {
        Object raw = db.real.selectOne("select exists(select * from profiles where user_id = 1) as v")
                .orElseThrow(IllegalStateException::new).get("v");
        assertTrue(raw instanceof Number, "the driver gives 1/0: " + raw);

        ModelCollection<User> users = User.QUERY.on(db.db).query().withExists("profile").orderBy("id").get();

        assertEquals(Boolean.TRUE, users.get(0).getAttribute("profile_exists"));
        assertEquals(Boolean.FALSE, users.get(1).getAttribute("profile_exists"));
        assertEquals(Boolean.FALSE, users.get(2).getAttribute("profile_exists"));
    }

    @Test
    void existsOnHasManyIsBoolean() {
        ModelCollection<User> users = User.QUERY.on(db.db).query().withExists("posts").orderBy("id").get();

        assertEquals(Boolean.TRUE, users.get(1).getAttribute("posts_exists"));
        assertEquals(Boolean.FALSE, users.get(2).getAttribute("posts_exists"));
    }

    // ---- load aggregates

    @Test
    void loadCountOnACollectionRunsOneSelect() {
        ModelCollection<User> users = User.QUERY.on(db.db).query().orderBy("id").get();
        db.clearLog();

        users.loadCount("posts");

        assertEquals(1, db.sql().size());
        assertEquals(2L, number(users.get(0), "posts_count"));
        assertEquals(1L, number(users.get(1), "posts_count"));
        assertEquals(0L, number(users.get(2), "posts_count"));
    }

    @Test
    void loadCountOnAModelAndLoadExistsFillTheModel() {
        User ann = User.QUERY.on(db.db).findOrFail(1);

        assertSame(ann, ann.loadCount("posts"));
        ann.loadExists("profile");

        assertEquals(2L, number(ann, "posts_count"));
        assertEquals(Boolean.TRUE, ann.getAttribute("profile_exists"));
        assertFalse(ann.isDirty("posts_count"));
    }

    // ---- touch

    @Test
    void savingADirtyChildExecutesTheTouchUpdate() {
        TComment comment = TComment.QUERY.on(db.db).findOrFail(1);
        comment.setAttribute("body", "changed");

        assertTrue(comment.save());

        assertNotEquals(OLD, updatedAt("posts", 1));
        assertNotEquals(OLD, updatedAt("comments", 1));
        assertEquals("changed", cell("select body as v from comments where id = 1"));
        assertEquals(OLD, updatedAt("posts", 2));
    }

    @Test
    void cleanSaveTouchesNothing() {
        TComment comment = TComment.QUERY.on(db.db).findOrFail(1);

        comment.save();

        assertEquals(OLD, updatedAt("posts", 1));
    }

    @Test
    void touchOwnersExecutesTheUpdate() {
        TComment comment = TComment.QUERY.on(db.db).findOrFail(1);

        comment.touchOwners();

        assertNotEquals(OLD, updatedAt("posts", 1));
    }

    @Test
    void withoutTouchingSkipsTheUpdate() {
        TComment comment = TComment.QUERY.on(db.db).findOrFail(1);
        comment.setAttribute("body", "quiet");

        TComment.withoutTouching(TPost.class, () -> {
            comment.save();
        });

        assertEquals(OLD, updatedAt("posts", 1));
        assertEquals("quiet", cell("select body as v from comments where id = 1"));
    }

    // ---- push

    @Test
    void pushWritesTheModelAndItsLoadedRelations() {
        User ann = User.QUERY.on(db.db).query().with("posts").findOrFail(1);
        ann.setAttribute("name", "Ann2");
        ModelCollection<Post> posts = ann.relatedMany("posts");
        posts.get(0).setAttribute("title", "a2");
        posts.get(1).setAttribute("title", "b2");

        assertTrue(ann.push());

        assertEquals("Ann2", cell("select name as v from users where id = 1"));
        assertEquals("a2", cell("select title as v from posts where id = 1"));
        assertEquals("b2", cell("select title as v from posts where id = 2"));
        assertEquals("c", cell("select title as v from posts where id = 3"));
    }

    // ---- chaperone

    @Test
    void chaperoneRunsTheSameQueriesWithAndWithoutIt() {
        Member.QUERY.on(db.db).query().with("notes").orderBy("id").get();
        List<String> plain = db.sql();
        db.clearLog();

        Member.QUERY.on(db.db).query().with("linkedNotes").orderBy("id").get();
        List<String> chaperoned = db.sql();

        assertEquals(2, plain.size());
        assertEquals(plain.size(), chaperoned.size());
        assertEquals(plain, chaperoned);
    }
}
