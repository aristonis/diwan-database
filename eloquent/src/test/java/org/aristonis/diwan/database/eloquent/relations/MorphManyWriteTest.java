package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Image;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphManyWriteTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphModels.createTables(db);
        MorphModels.register();
        MorphModels.mapAliases();
        db.seed("morph_posts", Map.of("id", 1, "title", "a"));
        db.seed("morph_videos", Map.of("id", 1, "title", "v"));
        db.seed("morph_comments", Map.of("id", 1, "body", "old", "commentable_type", "post", "commentable_id", 1));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Post post() {
        return Post.QUERY.on(db.db).findOrFail(1);
    }

    private static void assertOwnedBy(Comment comment, String type, int id) {
        assertEquals(type, comment.getAttribute("commentable_type"));
        assertEquals(id, ((Number) comment.getAttribute("commentable_id")).intValue());
    }

    @Test
    void makeSetsTheTypeAndTheIdWithoutSaving() {
        Comment made = post().comments().make(Map.of("body", "new"));

        assertFalse(made.exists());
        assertOwnedBy(made, "post", 1);
        assertEquals("new", made.getAttribute("body"));
    }

    @Test
    void createSavesWithTheTypeAndTheId() {
        Comment created = post().comments().create(Map.of("body", "new"));

        Comment stored = Comment.QUERY.on(db.db).findOrFail(created.getKey());
        assertOwnedBy(stored, "post", 1);
        assertEquals("new", stored.getAttribute("body"));
    }

    @Test
    void createOnAVideoWritesTheVideoAlias() {
        Video video = Video.QUERY.on(db.db).findOrFail(1);

        Comment created = video.comments().create(Map.of("body", "clip"));

        assertOwnedBy(Comment.QUERY.on(db.db).findOrFail(created.getKey()), "video", 1);
    }

    @Test
    void saveSetsTheTypeAndTheIdOnAnExistingModel() {
        Comment loose = Comment.QUERY.on(db.db).newModel();
        loose.setAttribute("body", "loose");

        post().comments().save(loose);

        assertOwnedBy(Comment.QUERY.on(db.db).findOrFail(loose.getKey()), "post", 1);
    }

    @Test
    void saveManySavesEveryModelWithTheType() {
        Comment one = Comment.QUERY.on(db.db).newModel();
        one.setAttribute("body", "one");
        Comment two = Comment.QUERY.on(db.db).newModel();
        two.setAttribute("body", "two");

        List<Comment> saved = post().comments().saveMany(Arrays.asList(one, two));

        assertEquals(2, saved.size());
        for (Comment comment : saved) {
            assertOwnedBy(Comment.QUERY.on(db.db).findOrFail(comment.getKey()), "post", 1);
        }
    }

    @Test
    void createManySavesEveryRecordWithTheType() {
        List<Comment> created = post().comments()
                .createMany(Arrays.asList(Map.of("body", "x"), Map.of("body", "y")));

        assertEquals(2, created.size());
        assertEquals(3, post().comments().getResults().size());
        for (Comment comment : created) {
            assertOwnedBy(comment, "post", 1);
        }
    }

    @Test
    void writingWithoutAParentKeyIsRefused() {
        Post unsaved = Post.QUERY.on(db.db).newModel();

        assertThrows(IllegalStateException.class, () -> unsaved.comments().make(Collections.emptyMap()));
    }

    @Test
    void oneReadsTheFirstRowOnTheSameColumns() {
        MorphOne<Comment> first = post().comments().one();

        assertEquals("morph_comments.commentable_type", first.getMorphType());
        assertEquals("morph_comments.commentable_id", first.getQualifiedForeignKeyName());
        assertEquals(1, first.getResults().getKey());
    }

    @Test
    void oneWritesTheTypeAndTheId() {
        Comment created = post().comments().one().create(Map.of("body", "single"));

        assertOwnedBy(Comment.QUERY.on(db.db).findOrFail(created.getKey()), "post", 1);
    }

    @Test
    void morphOneCreateSetsTheTypeAndTheId() {
        Image image = post().image().create(Map.of("url", "u"));

        Image stored = Image.QUERY.on(db.db).findOrFail(image.getKey());
        assertEquals("post", stored.getAttribute("imageable_type"));
        assertTrue(((Number) stored.getAttribute("imageable_id")).intValue() == 1);
    }
}
