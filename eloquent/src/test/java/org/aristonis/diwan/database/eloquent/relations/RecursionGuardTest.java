package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TComment;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TPost;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TUser;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecursionGuardTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private User user(int id) {
        return User.QUERY.on(db.db).findOrFail(id);
    }

    private ModelCollection<Post> posts() {
        return Post.QUERY.on(db.db).query().orderBy("id").get();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    void attributesOnlyOnReentry() {
        User user = user(1);
        ModelCollection<Post> posts = posts();
        Post post = posts.get(0);
        post.setRelation("author", user);
        user.setRelation("posts", posts);

        Map<String, Object> map = post.toMap();

        Map<String, Object> author = mapOf(map.get("author"));
        Map<String, Object> inner = listOf(author.get("posts")).get(0);
        assertEquals("a", inner.get("title"));
        assertEquals(1, ((Number) inner.get("id")).intValue());
        assertFalse(inner.containsKey("author"));
    }

    @Test
    void sharedModelWrittenInFull() {
        User ann = user(1);
        User bob = user(2);
        ModelCollection<Post> posts = posts();
        Post post = posts.get(0);
        post.setRelation("author", user(1));
        ann.setRelation("posts", posts);
        bob.setRelation("posts", posts);

        List<Map<String, Object>> maps = Arrays.asList(ann.toMap(), bob.toMap());

        for (Map<String, Object> map : maps) {
            Map<String, Object> written = listOf(map.get("posts")).get(0);
            assertTrue(written.containsKey("author"));
            assertEquals("Ann", mapOf(written.get("author")).get("name"));
        }
    }

    // toMap() starts a fresh VisitPath each call, so this checks recovery only; the finally
    // release on one shared path is checked in SerializationPathReleaseTest.
    @Test
    void recoversAfterException() {
        User user = user(1);
        ModelCollection<Post> posts = posts();
        Post post = posts.get(0);
        post.setRelation("author", user);
        user.setRelation("posts", posts);
        user.append("no_such_accessor");

        assertThrows(IllegalStateException.class, post::toMap);
        user.withoutAppends();
        Map<String, Object> map = post.toMap();

        Map<String, Object> author = mapOf(map.get("author"));
        assertEquals("Ann", author.get("name"));
        assertFalse(listOf(author.get("posts")).get(0).containsKey("author"));
    }

    // Each of toMap(), push() and touchOwners() starts its own VisitPath, so an outer path that
    // holds the post is not seen by any of them (R5).
    @Test
    void independentCallsDoNotSeeTheOuterPath() {
        User user = user(1);
        ModelCollection<Post> posts = posts();
        Post post = posts.get(0);
        post.setRelation("author", user);
        user.setRelation("posts", posts);
        VisitPath outer = new VisitPath();
        assertTrue(outer.enter(post));

        Map<String, Object> first = post.toMap();
        Map<String, Object> second = post.toMap();

        assertTrue(post.push());
        post.touchOwners();

        assertEquals(first, second);
        assertTrue(mapOf(first.get("author")).containsKey("posts"));
        assertTrue(outer.contains(post));
        assertFalse(outer.isDone(post));
        outer.leave(post);
        assertFalse(outer.contains(post));
    }

    // R5: serialization, touch and push walk the same cyclic graph; each ends, each releases its
    // path, and one does not change what the next one sees.
    @Test
    void threeGuardsAgree() throws SQLException {
        try (TouchDb touchDb = new TouchDb()) {
            touchDb.seed("users", Map.of("id", 1, "name", "Ann", "created_at", TouchDb.OLD, "updated_at", TouchDb.OLD));
            touchDb.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a", "created_at", TouchDb.OLD,
                    "updated_at", TouchDb.OLD));
            touchDb.seed("comments", Map.of("id", 1, "post_id", 1, "body", "c", "created_at", TouchDb.OLD,
                    "updated_at", TouchDb.OLD));
            TUser user = TUser.QUERY.on(touchDb.db).findOrFail(1);
            ModelCollection<TPost> posts = TPost.QUERY.on(touchDb.db).query().get();
            TPost post = posts.get(0);
            ModelCollection<TComment> comments = TComment.QUERY.on(touchDb.db).query().get();
            TComment comment = comments.get(0);
            comment.setRelation("post", post);
            post.setRelation("comments", comments);
            post.setRelation("author", user);
            user.setRelation("posts", posts);

            Map<String, Object> before = comment.toMap();
            touchDb.clearLog();
            comment.touchOwners();
            int touchUpdates = touchDb.count("update");
            int postSaves = post.savedCalls;
            int userSaves = user.savedCalls;
            touchDb.clearLog();
            boolean pushed = comment.push();
            Map<String, Object> after = comment.toMap();

            assertEquals(2, touchUpdates);
            assertTrue(pushed);
            assertEquals(0, touchDb.count("update"));
            assertEquals(1, comment.savedCalls);
            assertEquals(postSaves + 1, post.savedCalls);
            assertEquals(userSaves + 1, user.savedCalls);
            assertEquals(before, after);
            Map<String, Object> inner = listOf(mapOf(after.get("post")).get("comments")).get(0);
            assertFalse(inner.containsKey("post"));
        }
    }

    @Test
    void independentPaths() {
        User user = user(1);
        Post post = posts().get(0);
        VisitPath first = new VisitPath();
        VisitPath second = new VisitPath();

        assertTrue(first.enter(post));
        assertTrue(second.enter(post));
        assertFalse(first.enter(post));
        first.leave(post);

        assertFalse(first.contains(post));
        assertTrue(second.contains(post));
        assertFalse(second.contains(user));
    }
}
