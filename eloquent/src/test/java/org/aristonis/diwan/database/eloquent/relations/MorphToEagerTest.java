package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.TextComment;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphToEagerTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToModels.prepare(db);
        db.seed("morph_authors", Map.of("id", 1, "name", "Ann"));
        db.seed("morph_authors", Map.of("id", 2, "name", "Bob"));
        db.seed("morph_posts", Map.of("id", 5, "title", "post five", "author_id", 1));
        db.seed("morph_posts", Map.of("id", 6, "title", "post six", "author_id", 2));
        db.seed("morph_videos", Map.of("id", 5, "title", "video five", "author_id", 2));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private void comment(int id, String type, Integer commentableId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("body", "c" + id);
        row.put("commentable_type", type);
        row.put("commentable_id", commentableId);
        db.seed("morph_comments", row);
    }

    private List<Comment> comments() {
        List<Comment> comments = Comment.QUERY.on(db.db).all();
        db.clearLog();
        return comments;
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> paths(String... names) {
        Map<String, Consumer<EloquentBuilder<?>>> paths = new LinkedHashMap<>();
        for (String name : names) {
            paths.put(name, query -> {
            });
        }
        return paths;
    }

    private static String title(Comment comment) {
        Model owner = comment.related("commentable");
        return (String) owner.getAttribute("title");
    }

    @Test
    void mixedCommentsRunOneQueryPerTypeAndAllMatch() {
        comment(1, "post", 5);
        comment(2, "video", 5);
        comment(3, "post", 6);
        comment(4, "post", 5);
        List<Comment> comments = comments();

        new RelationAccessImpl().load(comments, paths("commentable"));

        assertEquals(2, db.sql().size());
        assertTrue(db.sql().get(0).contains("\"morph_posts\""), db.sql().get(0));
        assertTrue(db.sql().get(1).contains("\"morph_videos\""), db.sql().get(1));
        assertEquals("post five", title(comments.get(0)));
        assertEquals("video five", title(comments.get(1)));
        assertEquals("post six", title(comments.get(2)));
        assertEquals("post five", title(comments.get(3)));
    }

    @Test
    void sameIdOnTwoTypesLoadsEachToItsOwnChild() {
        comment(1, "post", 5);
        comment(2, "video", 5);
        List<Comment> comments = comments();

        new RelationAccessImpl().load(comments, paths("commentable"));

        assertInstanceOf(Post.class, comments.get(0).related("commentable"));
        assertInstanceOf(Video.class, comments.get(1).related("commentable"));
    }

    @Test
    void childrenWithoutTypeOrIdGetNullAndStayLoaded() {
        comment(1, "post", 5);
        comment(2, null, 5);
        comment(3, "post", null);
        List<Comment> comments = comments();

        new RelationAccessImpl().load(comments, paths("commentable"));

        assertEquals(1, db.sql().size());
        assertTrue(comments.get(1).relationLoaded("commentable"));
        assertNull(comments.get(1).related("commentable"));
        assertTrue(comments.get(2).relationLoaded("commentable"));
        assertNull(comments.get(2).related("commentable"));
    }

    @Test
    void childrenWithoutAnyOwnerRunNoQuery() {
        comment(1, null, null);
        List<Comment> comments = comments();

        new RelationAccessImpl().load(comments, paths("commentable"));

        assertEquals(Collections.emptyList(), db.sql());
        assertNull(comments.get(0).related("commentable"));
    }

    @Test
    void missingOwnerStaysNull() {
        comment(1, "post", 99);
        List<Comment> comments = comments();

        new RelationAccessImpl().load(comments, paths("commentable"));

        assertNull(comments.get(0).related("commentable"));
    }

    @Test
    void idStoredAsTextMatchesNumericKey() {
        db.seed("morph_text_comments", Map.of("id", 1, "commentable_type", "post", "commentable_id", "5"));
        db.seed("morph_text_comments", Map.of("id", 2, "commentable_type", "video", "commentable_id", "5"));
        List<TextComment> comments = TextComment.QUERY.on(db.db).all();
        db.clearLog();

        new RelationAccessImpl().load(comments, paths("commentable"));

        assertEquals("post five", comments.get(0).<Model>related("commentable").getAttribute("title"));
        assertEquals("video five", comments.get(1).<Model>related("commentable").getAttribute("title"));
    }

    @Test
    void nestedPathLoadsPerResolvedClass() {
        comment(1, "post", 5);
        comment(2, "video", 5);
        List<Comment> comments = comments();

        new RelationAccessImpl().load(comments, paths("commentable", "commentable.author"));

        assertEquals(4, db.sql().size());
        Model post = comments.get(0).related("commentable");
        Model video = comments.get(1).related("commentable");
        assertEquals("Ann", post.<Model>related("author").getAttribute("name"));
        assertEquals("Bob", video.<Model>related("author").getAttribute("name"));
    }

    @Test
    void unknownTypeThrowsBeforeAnyQuery() {
        comment(1, "post", 5);
        comment(2, "photo", 1);
        List<Comment> comments = comments();

        ClassMorphViolationException thrown = assertThrows(ClassMorphViolationException.class,
                () -> new RelationAccessImpl().load(comments, paths("commentable")));

        assertTrue(thrown.getMessage().contains("photo"), thrown.getMessage());
        assertEquals(Collections.emptyList(), db.sql());
    }
}
