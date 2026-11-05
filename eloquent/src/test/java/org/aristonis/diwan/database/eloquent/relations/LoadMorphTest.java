package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.MorphWithModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphWithModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphWithModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadMorphTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphWithModels.prepare(db);
        db.seed("mw_authors", Map.of("id", 1, "name", "Ann"));
        db.seed("mw_authors", Map.of("id", 2, "name", "Bob"));
        db.seed("mw_channels", Map.of("id", 1, "name", "Main"));
        db.seed("mw_posts", Map.of("id", 5, "title", "post five", "published", 1, "author_id", 1));
        db.seed("mw_posts", Map.of("id", 6, "title", "post six", "published", 0, "author_id", 2));
        db.seed("mw_videos", Map.of("id", 5, "title", "video five", "channel_id", 1));
        db.seed("mw_likes", Map.of("id", 1, "post_id", 5));
        db.seed("mw_likes", Map.of("id", 2, "post_id", 5));
        db.seed("mw_likes", Map.of("id", 3, "post_id", 6));
        db.seed("mw_comments", Map.of("id", 1, "commentable_type", "post", "commentable_id", 5));
        db.seed("mw_comments", Map.of("id", 2, "commentable_type", "video", "commentable_id", 5));
        db.seed("mw_comments", Map.of("id", 3, "commentable_type", "post", "commentable_id", 6));
        Map<String, Object> orphan = new HashMap<>();
        orphan.put("id", 4);
        orphan.put("commentable_type", null);
        orphan.put("commentable_id", null);
        db.seed("mw_comments", orphan);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private ModelCollection<Comment> loadedComments() {
        ModelCollection<Comment> comments = Comment.QUERY.on(db.db).query().orderBy("id").get();
        comments.load("commentable");
        db.clearLog();
        return comments;
    }

    private static Map<Class<? extends Model>, List<String>> relations(String postRelation, String videoRelation) {
        Map<Class<? extends Model>, List<String>> map = new HashMap<>();
        map.put(Post.class, Collections.singletonList(postRelation));
        map.put(Video.class, Arrays.asList(videoRelation));
        return map;
    }

    private static String nameOf(Comment comment, String relation) {
        Model related = comment.<Model>related("commentable").related(relation);
        return (String) related.getAttribute("name");
    }

    @Test
    void collectionLoadsEachClassItsOwnRelationWithoutReloadingTheParents() {
        ModelCollection<Comment> comments = loadedComments();
        Model firstOwner = comments.get(0).related("commentable");

        ModelCollection<Comment> returned = comments.loadMorph("commentable", relations("author", "channel"));

        assertSame(comments, returned);
        assertEquals(2, db.sql().size(), db.sql().toString());
        assertFalse(db.sql().stream().anyMatch(sql -> sql.contains("\"mw_comments\"")));
        assertEquals("Ann", nameOf(comments.get(0), "author"));
        assertEquals("Main", nameOf(comments.get(1), "channel"));
        assertEquals("Bob", nameOf(comments.get(2), "author"));
        assertSame(firstOwner, comments.get(0).related("commentable"));
    }

    @Test
    void singleModelLoadsTheRelationOfItsOwnersClass() {
        Comment comment = loadedComments().get(1);

        Model returned = comment.loadMorph("commentable", relations("author", "channel"));

        assertSame(comment, returned);
        assertEquals(1, db.sql().size());
        assertEquals("Main", nameOf(comment, "channel"));
    }

    @Test
    void collectionLoadsTheMorphToFirstWithOneQueryPerType() {
        ModelCollection<Comment> unloaded = Comment.QUERY.on(db.db).query().orderBy("id").get();
        db.clearLog();

        unloaded.loadMorph("commentable", relations("author", "channel"));

        assertEquals(4, db.sql().size(), db.sql().toString());
        assertEquals(1, db.sql().stream().filter(sql -> sql.contains("\"mw_posts\"")
                && !sql.contains("\"mw_authors\"")).count(), db.sql().toString());
        assertEquals(1, db.sql().stream().filter(sql -> sql.contains("\"mw_videos\"")
                && !sql.contains("\"mw_channels\"")).count(), db.sql().toString());
        assertEquals("Ann", nameOf(unloaded.get(0), "author"));
        assertEquals("Main", nameOf(unloaded.get(1), "channel"));
        assertEquals("Bob", nameOf(unloaded.get(2), "author"));
    }

    @Test
    void singleModelLoadsTheMorphToFirstWhenItIsNotLoaded() {
        Comment comment = Comment.QUERY.on(db.db).query().orderBy("id").get().get(1);
        assertFalse(comment.relationLoaded("commentable"));
        db.clearLog();

        Model returned = comment.loadMorph("commentable", relations("author", "channel"));

        assertSame(comment, returned);
        assertTrue(comment.relationLoaded("commentable"));
        assertEquals(2, db.sql().size(), db.sql().toString());
        assertEquals("Main", nameOf(comment, "channel"));
    }

    @Test
    void nullOwnerLoadsNothingMore() {
        Comment orphan = Comment.QUERY.on(db.db).query().orderBy("id").get().get(3);

        orphan.loadMorph("commentable", relations("author", "channel"));

        assertNull(orphan.<Model>related("commentable"));
        ModelCollection<Comment> comments = loadedComments();
        assertNull(comments.get(3).<Model>related("commentable"));
        comments.loadMorph("commentable", relations("author", "channel"));
        assertEquals(2, db.sql().size());
    }

    @Test
    void classWithoutAnEntryLoadsNothing() {
        ModelCollection<Comment> comments = loadedComments();
        Map<Class<? extends Model>, List<String>> onlyPosts = new HashMap<>();
        onlyPosts.put(Post.class, Collections.singletonList("author"));

        comments.loadMorph("commentable", onlyPosts);

        assertEquals(1, db.sql().size());
        assertFalse(comments.get(1).<Model>related("commentable").relationLoaded("channel"));
    }

    @Test
    void loadMorphCountCountsPerClassOnACollection() {
        ModelCollection<Comment> comments = loadedComments();
        Map<Class<? extends Model>, List<String>> counts = new HashMap<>();
        counts.put(Post.class, Collections.singletonList("likes"));

        ModelCollection<Comment> returned = comments.loadMorphCount("commentable", counts);

        assertSame(comments, returned);
        assertEquals(1, db.sql().size());
        Model post = comments.get(0).related("commentable");
        assertEquals(2L, ((Number) post.getAttribute("likes_count")).longValue());
        Model other = comments.get(2).related("commentable");
        assertEquals(1L, ((Number) other.getAttribute("likes_count")).longValue());
        assertNull(comments.get(1).<Model>related("commentable").getAttribute("likes_count"));
    }

    @Test
    void loadMorphCountCountsOnASingleModel() {
        Comment comment = loadedComments().get(0);
        Map<Class<? extends Model>, List<String>> counts = new HashMap<>();
        counts.put(Post.class, Collections.singletonList("likes"));

        Model returned = comment.loadMorphCount("commentable", counts);

        assertSame(comment, returned);
        Model post = comment.related("commentable");
        assertTrue(post.getAttribute("likes_count") != null);
        assertEquals(2L, ((Number) post.getAttribute("likes_count")).longValue());
    }

    @Test
    void nullArgumentsAreRefused() {
        Comment comment = loadedComments().get(0);

        assertThrows(NullPointerException.class, () -> comment.loadMorph(null, relations("author", "channel")));
        assertThrows(NullPointerException.class, () -> comment.loadMorph("commentable", null));
    }
}
