package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HasManyTest {

    private static final String LAZY = "select * from \"posts\" where \"posts\".\"user_id\" = ?"
            + " and \"posts\".\"user_id\" is not null";

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private User user(int id) {
        User user = User.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return user;
    }

    @Test
    void lazyQueryFiltersByTheParentKey() {
        User ann = user(1);

        ModelCollection<Post> posts = ann.posts().getResults();

        assertEquals(Arrays.asList(1, 2), posts.modelKeys());
        assertEquals(Collections.singletonList(LAZY), db.sql());
        assertEquals(Collections.singletonList(1), db.queries().get(0).bindings());
    }

    @Test
    void nullParentKeyRunsNoQuery() {
        User unsaved = User.QUERY.on(db.db).newModel();
        db.clearLog();

        assertTrue(unsaved.posts().getResults().isEmpty());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void forwardersKeepLaravelOrder() {
        User ann = user(1);

        ann.posts().where("title", "b").orderByDesc("id").get();

        assertEquals(Collections.singletonList(LAZY + " and \"title\" = ? order by \"id\" desc"), db.sql());
        assertEquals(Arrays.asList(1, "b"), db.queries().get(0).bindings());
    }

    @Test
    void getQueryIsConstrained() {
        User ann = user(1);

        assertEquals(LAZY, ann.posts().getQuery().toSql());
    }

    @Test
    void constraintsAppliedOnce() {
        User ann = user(1);
        HasMany<Post> posts = ann.posts();

        posts.getQuery();
        posts.where("title", "a");
        posts.getQuery();
        ModelCollection<Post> found = posts.get();

        assertEquals(Collections.singletonList(1), found.modelKeys());
        assertEquals(Collections.singletonList(LAZY + " and \"title\" = ?"), db.sql());
    }

    @Test
    void forwarderReturnsSubtype() {
        User ann = user(1);
        HasMany<Post> posts = ann.posts();

        HasMany<Post> same = posts.where("title", "x");
        Post created = posts.where("title", "x").create(Map.of("title", "x"));

        assertSame(posts, same);
        assertEquals(1L, ((Number) created.getAttribute("user_id")).longValue());
    }

    @Test
    void relationKnowsItsKeysAndModels() {
        User ann = user(1);
        HasMany<Post> posts = ann.posts();

        assertEquals("posts.user_id", posts.getQualifiedForeignKeyName());
        assertEquals("user_id", posts.getForeignKeyName());
        assertEquals("id", posts.getLocalKeyName());
        assertSame(ann, posts.getParent());
        assertTrue(posts.getRelated() instanceof Post);
        assertNull(posts.getRelationName());
        assertEquals("posts", ann.relation("posts").getRelationName());
    }

    @Test
    void takeLimitsTheRows() {
        User ann = user(1);

        ModelCollection<Post> posts = ann.posts().orderBy("id").take(1).get();

        assertEquals(Collections.singletonList(1), posts.modelKeys());
        assertEquals(Collections.singletonList(LAZY + " order by \"id\" asc limit 1"), db.sql());
    }
}
