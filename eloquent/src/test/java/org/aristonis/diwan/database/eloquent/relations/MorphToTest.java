package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphToTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToModels.prepare(db);
        db.seed("morph_posts", Map.of("id", 5, "title", "hello"));
        db.seed("morph_comments", Map.of("id", 1, "body", "a", "commentable_type", "post", "commentable_id", 5));
        db.seed("morph_comments", Map.of("id", 2, "body", "b", "commentable_id", 5));
        db.seed("morph_comments", Map.of("id", 3, "body", "c", "commentable_type", "post"));
        db.seed("morph_comments", Map.of("id", 4, "body", "d", "commentable_type", "", "commentable_id", 5));
        db.seed("morph_comments", Map.of("id", 5, "body", "e", "commentable_type", "photo", "commentable_id", 1));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Comment comment(int id) {
        Comment comment = Comment.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return comment;
    }

    @Test
    void lazyQueryFindsTheOwnerOfTheAliasedClass() {
        Model owner = comment(1).commentable().getResults();

        assertInstanceOf(Post.class, owner);
        assertEquals("hello", owner.getAttribute("title"));
        assertEquals(Collections.singletonList(
                "select * from \"morph_posts\" where \"morph_posts\".\"id\" = ? limit 1"), db.sql());
        assertEquals(5, ((Number) db.queries().get(0).bindings().get(0)).intValue());
    }

    @Test
    void relationNameLoadsTheOwnerAndCachesIt() {
        Comment comment = comment(1);

        Model owner = comment.related("commentable");

        assertInstanceOf(Post.class, owner);
        assertTrue(comment.relationLoaded("commentable"));
    }

    @Test
    void nullTypeReturnsNullWithoutQuery() {
        assertNull(comment(2).commentable().getResults());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void emptyTypeReturnsNullWithoutQuery() {
        assertNull(comment(4).commentable().getResults());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void nullIdReturnsNullWithoutQuery() {
        assertNull(comment(3).commentable().getResults());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void defaultIsReturnedWithoutQueryWhenThereIsNoOwner() {
        Model owner = comment(2).commentable().withDefault().getResults();

        assertNotNull(owner);
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void defaultFilledWithAttributes() {
        Model owner = comment(3).commentable().withDefault(Map.of("body", "none")).getResults();

        assertEquals("none", owner.getAttribute("body"));
    }

    @Test
    void unknownAliasNamesItWhenTheMapIsRequired() {
        Comment comment = comment(5);

        ClassMorphViolationException thrown =
                assertThrows(ClassMorphViolationException.class, () -> comment.commentable());

        assertTrue(thrown.getMessage().contains("photo"), thrown.getMessage());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void keysAreTheGivenOnes() {
        MorphTo<Model> relation = comment(1).commentable();

        assertEquals("commentable_type", relation.getMorphType());
        assertEquals("commentable_id", relation.getForeignKeyName());
        assertTrue(relation.getOwnerKeyName().isEmpty());
        assertTrue(relation.isPolymorphic());
    }
}
