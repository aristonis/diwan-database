package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelationSerializationTest {

    private static final int POST_COUNT = 1_000;

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private User ann() {
        return User.QUERY.on(db.db).findOrFail(1);
    }

    private ModelCollection<Post> posts() {
        return Post.QUERY.on(db.db).query().orderBy("id").get();
    }

    /** A model with no registry whose relations keep their names as keys. */
    static final class RawKeys extends Model {
        @Override
        protected boolean snakeRelationKeys() {
            return false;
        }
    }

    @Test
    void cycleTerminates() {
        User user = ann();
        ModelCollection<Post> posts = posts();
        Post post = posts.get(0);
        post.setRelation("author", user);
        user.setRelation("posts", posts);

        Map<String, Object> map = post.toMap();

        @SuppressWarnings("unchecked")
        Map<String, Object> author = (Map<String, Object>) map.get("author");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inner = (List<Map<String, Object>>) author.get("posts");
        assertEquals("Ann", author.get("name"));
        assertEquals("a", inner.get(0).get("title"));
        assertFalse(inner.get(0).containsKey("author"));
        assertEquals(2, inner.size());
    }

    @Test
    void snakeKeys() {
        User user = ann();
        user.setRelation("blogPosts", posts());

        Map<String, Object> map = user.toMap();

        assertTrue(map.containsKey("blog_posts"));
        assertFalse(map.containsKey("blogPosts"));
    }

    @Test
    void snakeKeysOffKeepsName() {
        RawKeys model = new RawKeys();
        model.setRelation("blogPosts", null);

        Map<String, Object> map = model.toMap();

        assertTrue(map.containsKey("blogPosts"));
        assertEquals(null, map.get("blogPosts"));
    }

    @Test
    void hiddenRelation() {
        User user = ann();
        user.setRelation("posts", posts());
        user.makeHidden("posts");

        assertFalse(user.toMap().containsKey("posts"));
    }

    @Test
    void visibleListDropsUnlistedRelation() {
        User user = ann();
        user.setRelation("posts", posts());
        user.setVisible(Arrays.asList("name"));

        assertEquals(Arrays.asList("name"), Arrays.asList(user.toMap().keySet().toArray()));
    }

    @Test
    void relationsFollowAttributesAndNullStaysNull() {
        Post post = posts().get(0);
        post.setRelation("author", null);

        Map<String, Object> map = post.toMap();

        Object[] keys = map.keySet().toArray();
        assertEquals("author", keys[keys.length - 1]);
        assertEquals(null, map.get("author"));
    }

    @Test
    void relationOverwritesSameNamedAttribute() {
        Post post = posts().get(0);
        post.setAttribute("author", "column");
        User user = ann();
        post.setRelation("author", user);

        assertEquals("column", post.getAttribute("author"));
        @SuppressWarnings("unchecked")
        Map<String, Object> author = (Map<String, Object>) post.toMap().get("author");
        assertEquals("Ann", author.get("name"));
    }

    @Test
    void sharedInstanceSerialisedInFull() {
        User user = ann();
        user.setRelation("profile", null);
        ModelCollection<Post> posts = posts();
        posts.get(0).setRelation("author", user);
        posts.get(1).setRelation("author", user);
        User holder = ann();
        holder.setRelation("posts", posts);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) holder.toMap().get("posts");

        assertTrue(((Map<?, ?>) list.get(0).get("author")).containsKey("profile"));
        assertTrue(((Map<?, ?>) list.get(1).get("author")).containsKey("profile"));
    }

    @Test
    void toJsonIncludesRelations() {
        User user = ann();
        user.setRelation("blogPosts", posts());

        String json = user.toJson();

        assertTrue(json.contains("\"blog_posts\":["), json);
        assertTrue(json.contains("\"title\":\"b\""), json);
    }

    @Test
    void thousandTwoWayTerminates() {
        for (int id = 3; id <= POST_COUNT; id++) {
            db.seed("posts", Map.of("id", id, "user_id", 1, "title", "t"));
        }
        User user = ann();
        ModelCollection<Post> posts = posts();
        user.setRelation("posts", posts);
        for (Post post : posts) {
            post.setRelation("author", user);
        }

        int maps = assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            int total = 0;
            for (Post post : posts) {
                total += countMaps(post.toMap());
            }
            return total;
        });

        // Per post: itself, its author, the author's posts, and each other post's re-entered author.
        int perPost = 1 + 1 + POST_COUNT + (POST_COUNT - 1);
        assertEquals(POST_COUNT * perPost, maps);
    }

    private static int countMaps(Object value) {
        if (value instanceof Map) {
            int total = 1;
            for (Object inner : ((Map<?, ?>) value).values()) {
                total += countMaps(inner);
            }
            return total;
        }
        if (value instanceof List) {
            int total = 0;
            for (Object inner : (List<?>) value) {
                total += countMaps(inner);
            }
            return total;
        }
        return 0;
    }
}
