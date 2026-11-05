package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphToWriteTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToModels.prepare(db);
        db.seed("morph_posts", Map.of("id", 5, "title", "hello"));
        db.seed("morph_videos", Map.of("id", 7, "title", "clip"));
        db.seed("morph_comments", Map.of("id", 1, "body", "a", "commentable_type", "post", "commentable_id", 5));
        db.seed("morph_comments", Map.of("id", 2, "body", "b"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Comment comment(int id) {
        return Comment.QUERY.on(db.db).findOrFail(id);
    }

    @Test
    void associateSetsTypeIdAndTheLoadedRelation() {
        Comment comment = comment(1);
        Video video = Video.QUERY.on(db.db).findOrFail(7);

        Model returned = comment.commentable().associate(video);

        assertSame(comment, returned);
        assertEquals("video", comment.getAttribute("commentable_type"));
        assertEquals(7, ((Number) comment.getAttribute("commentable_id")).intValue());
        assertSame(video, comment.related("commentable"));
    }

    @Test
    void associateRefusesAnOwnerWithoutKey() {
        Comment comment = comment(1);
        Video unsaved = new Video();

        assertThrows(IllegalStateException.class, () -> comment.commentable().associate(unsaved));
    }

    @Test
    void dissociateNullsBothColumnsAndTheLoadedRelation() {
        Comment comment = comment(1);

        comment.commentable().dissociate();

        assertNull(comment.getAttribute("commentable_type"));
        assertNull(comment.getAttribute("commentable_id"));
        assertTrue(comment.relationLoaded("commentable"));
        assertNull(comment.related("commentable"));
    }

    @Test
    void touchWithoutTypeRunsNoQuery() {
        Comment comment = comment(2);
        db.clearLog();

        comment.commentable().touch();

        assertEquals(Collections.emptyList(), db.sql());
        assertNull(comment(1).getAttribute("updated_at"));
        assertNull(comment(2).getAttribute("updated_at"));
    }
}
