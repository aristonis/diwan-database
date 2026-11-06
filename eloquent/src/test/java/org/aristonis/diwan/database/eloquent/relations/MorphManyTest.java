package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphManyTest {

    private static final String LAZY = "select * from \"morph_comments\" where"
            + " \"morph_comments\".\"commentable_type\" = ? and \"morph_comments\".\"commentable_id\" = ?"
            + " and \"morph_comments\".\"commentable_id\" is not null";

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
        db.seed("morph_comments", Map.of("id", 1, "body", "p1a", "commentable_type", "post", "commentable_id", 1));
        db.seed("morph_comments", Map.of("id", 2, "body", "p1b", "commentable_type", "post", "commentable_id", 1));
        db.seed("morph_comments", Map.of("id", 3, "body", "v1", "commentable_type", "video", "commentable_id", 1));
        db.seed("morph_comments", Map.of("id", 4, "body", "p2", "commentable_type", "post", "commentable_id", 2));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Post post(int id) {
        Post post = Post.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return post;
    }

    @Test
    void lazyQueryBindsTheMorphClassAndTheKey() {
        Post first = post(1);

        ModelCollection<Comment> comments = first.comments().getResults();

        assertEquals(Arrays.asList(1, 2), comments.modelKeys());
        assertEquals(Collections.singletonList(LAZY), db.sql());
        assertEquals(Arrays.asList("post", 1), db.queries().get(0).bindings());
    }

    @Test
    void sameIdOnAnotherTypeIsNotMixedIn() {
        Video video = Video.QUERY.on(db.db).findOrFail(1);

        ModelCollection<Comment> comments = video.comments().getResults();

        assertEquals(Collections.singletonList(3), comments.modelKeys());
    }

    @Test
    void eachParentGetsOnlyItsOwnRows() {
        Post second = post(2);
        Post third = post(3);

        assertEquals(Collections.singletonList(4), second.comments().getResults().modelKeys());
        assertTrue(third.comments().getResults().isEmpty());
    }

    @Test
    void eagerLoadRunsOneQueryWithTheTypeBound() {
        db.clearLog();

        ModelCollection<Post> posts = Post.QUERY.on(db.db).with("comments").get();

        assertEquals(2, db.sql().size());
        String eager = db.sql().get(1);
        assertTrue(eager.contains("\"morph_comments\".\"commentable_type\" = ?"), eager);
        assertTrue(db.queries().get(1).bindings().contains("post"));
        assertEquals(Arrays.asList(1, 2), posts.get(0).<Comment>relatedMany("comments").modelKeys());
        assertEquals(Collections.singletonList(4), posts.get(1).<Comment>relatedMany("comments").modelKeys());
        assertTrue(posts.get(2).<Comment>relatedMany("comments").isEmpty());
    }

    @Test
    void relationKnowsItsColumnsAndTheMorphClass() {
        Post first = post(1);
        MorphMany<Comment> comments = first.comments();

        assertEquals("morph_comments.commentable_type", comments.getMorphType());
        assertEquals("commentable_type", comments.getMorphTypeName());
        assertEquals("morph_comments.commentable_id", comments.getQualifiedForeignKeyName());
        assertEquals("post", comments.getMorphClass());
        assertSame(first, comments.getParent());
    }

    @Test
    void morphClassIsReadWhenTheQueryRuns() {
        Post first = post(1);
        MorphMany<Comment> comments = first.comments();

        MorphMap.resetForTests();

        assertThrows(ClassMorphViolationException.class, comments::getResults);
    }

    @Test
    void unsavedParentRunsNoQuery() {
        Post unsaved = Post.QUERY.on(db.db).newModel();
        db.clearLog();

        assertTrue(unsaved.comments().getResults().isEmpty());
        assertEquals(Collections.emptyList(), db.sql());
    }
}
