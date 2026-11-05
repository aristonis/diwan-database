package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphExistenceTest {

    private static final String TYPE = "\"morph_comments\".\"commentable_type\" = ?";

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphModels.createTables(db);
        MorphModels.register();
        MorphModels.mapAliases();
        db.seed("morph_posts", Map.of("id", 1, "title", "a"));
        db.seed("morph_posts", Map.of("id", 2, "title", "b"));
        db.seed("morph_posts", Map.of("id", 3, "title", "c"));
        db.seed("morph_videos", Map.of("id", 1, "title", "v"));
        db.seed("morph_comments", Map.of("id", 1, "body", "p1", "commentable_type", "post", "commentable_id", 1));
        db.seed("morph_comments", Map.of("id", 2, "body", "p1b", "commentable_type", "post", "commentable_id", 1));
        db.seed("morph_comments", Map.of("id", 3, "body", "p2", "commentable_type", "post", "commentable_id", 2));
        // A video comment whose id equals post 3's id: it must not make post 3 look commented.
        db.seed("morph_comments", Map.of("id", 4, "body", "v3", "commentable_type", "video", "commentable_id", 3));
        db.seed("morph_comments", Map.of("id", 5, "body", "reply", "commentable_type", "comment",
                "commentable_id", 1));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<Post> posts() {
        return Post.QUERY.on(db.db).query();
    }

    private EloquentBuilder<Comment> comments() {
        return Comment.QUERY.on(db.db).query();
    }

    @Test
    void hasAddsTheTypeConditionBeforeTheKeyJoin() {
        EloquentBuilder<Post> query = posts().has("comments");

        assertEquals("select * from \"morph_posts\" where exists (select * from \"morph_comments\" where " + TYPE
                + " and \"morph_posts\".\"id\" = \"morph_comments\".\"commentable_id\")", query.toSql());
        assertEquals(Collections.singletonList("post"), query.getBindings());
    }

    @Test
    void hasIgnoresRowsOfAnotherTypeWithTheSameId() {
        ModelCollection<Post> found = posts().has("comments").get();

        assertEquals(Arrays.asList(1, 2), found.modelKeys());
    }

    @Test
    void doesntHaveFindsTheParentWhoseIdIsUsedByAnotherType() {
        ModelCollection<Post> found = posts().doesntHave("comments").get();

        assertEquals(Collections.singletonList(3), found.modelKeys());
    }

    @Test
    void whereHasKeepsTheTypeAndTheConstraint() {
        EloquentBuilder<Post> query = posts().whereHas("comments", q -> q.where("body", "p2"));

        assertTrue(query.toSql().contains(TYPE), query.toSql());
        assertEquals(Arrays.asList("post", "p2"), query.getBindings());
        assertEquals(Collections.singletonList(2), query.get().modelKeys());
    }

    @Test
    void whereRelationKeepsTheType() {
        EloquentBuilder<Post> query = posts().whereRelation("comments", "body", "p1");

        assertTrue(query.toSql().contains(TYPE), query.toSql());
        assertEquals(Collections.singletonList(1), query.get().modelKeys());
    }

    @Test
    void withCountCountsOnlyTheParentsType() {
        ModelCollection<Post> found = posts().withCount("comments").get();

        assertTrue(posts().withCount("comments").toSql().contains(TYPE));
        assertEquals(2, ((Number) found.get(0).getAttribute("comments_count")).intValue());
        assertEquals(1, ((Number) found.get(1).getAttribute("comments_count")).intValue());
        assertEquals(0, ((Number) found.get(2).getAttribute("comments_count")).intValue());
    }

    @Test
    void selfRelationPutsTheTypeOnTheAliasedInnerTable() {
        EloquentBuilder<Comment> query = comments().has("replies");

        assertEquals("select * from \"morph_comments\" where exists (select * from \"morph_comments\" as"
                + " \"laravel_reserved_0\" where \"laravel_reserved_0\".\"commentable_type\" = ?"
                + " and \"morph_comments\".\"id\" = \"laravel_reserved_0\".\"commentable_id\")", query.toSql());
        assertEquals(Collections.singletonList("comment"), query.getBindings());
    }

    @Test
    void selfRelationFindsOnlyCommentsWithReplies() {
        ModelCollection<Comment> found = comments().whereHas("replies", q -> q.where("body", "reply")).get();

        assertEquals(Collections.singletonList(1), found.modelKeys());
    }
}
