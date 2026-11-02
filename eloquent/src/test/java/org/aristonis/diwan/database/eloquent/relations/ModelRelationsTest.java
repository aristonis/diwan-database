package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelRelationsTest {

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
        User user = User.QUERY.on(db.db).findOrFail(1);
        db.clearLog();
        return user;
    }

    private Post post(int id) {
        Post post = Post.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return post;
    }

    @Test
    void loadedNullCounts() {
        Post post = post(1);

        post.setRelation("author", null);

        assertTrue(post.relationLoaded("author"));
        assertNull(post.getRelation("author"));
        assertNull(post.getRelationValue("author"));
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void getRelationOfAnUnloadedNameThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> post(1).getRelation("author"));

        assertTrue(e.getMessage().contains("author"));
        assertTrue(e.getMessage().contains(Post.class.getName()));
    }

    @Test
    void badSetRelationValueRejected() {
        Post post = post(1);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> post.setRelation("author", "secret-text"));

        assertTrue(e.getMessage().contains("java.lang.String"));
        assertFalse(e.getMessage().contains("secret-text"));
        assertFalse(post.relationLoaded("author"));
    }

    @Test
    void setUnsetAndReplaceRelations() {
        Post post = post(1);
        User author = ann();

        assertSame(post, post.setRelation("author", author));
        assertSame(author, post.getRelation("author"));
        assertSame(post, post.unsetRelation("author"));
        assertFalse(post.relationLoaded("author"));

        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("author", author);
        replacement.put("comments", null);
        post.setRelation("old", null);
        post.setRelations(replacement);

        assertEquals(Arrays.asList("author", "comments"), Arrays.asList(post.getRelations().keySet().toArray()));
        assertThrows(UnsupportedOperationException.class, () -> post.getRelations().clear());
        assertThrows(IllegalArgumentException.class, () -> post.setRelations(Map.of("x", 1)));
        assertTrue(post.relationLoaded("author"));
    }

    @Test
    void getRelationValueLoadsOnceAndStores() {
        User user = ann();

        ModelCollection<Post> posts = user.relatedMany("posts");
        ModelCollection<Post> again = user.relatedMany("posts");

        assertEquals(Arrays.asList(1, 2), posts.modelKeys());
        assertSame(posts, again);
        assertEquals(1, db.sql().size());
        assertTrue(user.relationLoaded("posts"));
    }

    @Test
    void relatedReadsOneModel() {
        Post post = post(1);

        User author = post.related("author");

        assertEquals("Ann", author.getAttribute("name"));
    }

    @Test
    void relatedOfACollectionNamesTheRelation() {
        User user = ann();

        ClassCastException single = assertThrows(ClassCastException.class, () -> user.<User>related("posts"));
        ClassCastException many = assertThrows(ClassCastException.class,
                () -> post(1).<User>relatedMany("author"));

        assertTrue(single.getMessage().contains("posts"));
        assertTrue(many.getMessage().contains("author"));
    }

    @Test
    void getAttributeDoesNotFallBackToRelations() {
        Post post = post(1);

        assertNull(post.getAttribute("author"));
        assertFalse(post.relationLoaded("author"));
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void unknownRelationValueThrows() {
        assertThrows(RelationNotFoundException.class, () -> post(1).getRelationValue("likes"));
    }

    @Test
    void withoutRelationsCopiesStateNotRelations() {
        Post post = post(1);
        post.setRelation("author", ann());
        post.setAttribute("title", "changed");

        Model copy = post.withoutRelations();

        assertNotSame(post, copy);
        assertTrue(copy instanceof Post);
        assertTrue(copy.exists());
        assertEquals("changed", copy.getAttribute("title"));
        assertTrue(copy.isDirty("title"));
        assertTrue(copy.getRelations().isEmpty());
        assertTrue(post.relationLoaded("author"));
    }

    @Test
    void freshAndReplicateAreBare() {
        Post post = post(1);
        post.setRelation("author", ann());

        Post fresh = post.<Post>fresh().orElseThrow(AssertionError::new);
        Post copy = post.replicate();

        assertTrue(fresh.getRelations().isEmpty());
        assertTrue(copy.getRelations().isEmpty());
    }

    @Test
    void newInstanceIsBareAndBoundLikeThis() {
        Post post = post(1);
        post.setRelation("author", null);

        Post made = post.newInstance();

        assertNotSame(post, made);
        assertFalse(made.exists());
        assertTrue(made.getAttributes().isEmpty());
        assertTrue(made.getRelations().isEmpty());
        assertSame(post.getQueryFactory(), made.getQueryFactory());
    }

    @Test
    void refreshWithoutLoadedRelationsRunsOneQuery() {
        Post post = post(1);

        assertSame(post, post.refresh());
        assertEquals(1, db.sql().size());
    }

    @Test
    void refreshReloadsRelations() {
        User ann = ann();
        ann.load("posts");
        db.seed("posts", Map.of("id", 3, "user_id", 1, "title", "c"));
        ann.setAttribute("name", "changed");
        db.clearLog();

        assertSame(ann, ann.refresh());

        assertEquals("Ann", ann.getAttribute("name"));
        assertEquals(3, ann.relatedMany("posts").size());
        assertEquals(2, db.sql().size());
    }
}
