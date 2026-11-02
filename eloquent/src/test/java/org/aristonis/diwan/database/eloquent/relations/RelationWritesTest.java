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
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelationWritesTest {

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 5, "name", "Eve"));
        db.seed("posts", Map.of("id", 1, "title", "a"));
        if (TwoAuthors.QUERY == null || NamedAuthor.QUERY == null) {
            throw new IllegalStateException("test model entries not initialised");
        }
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private User user(int id) {
        return User.QUERY.on(db.db).findOrFail(id);
    }

    private Post post(int id) {
        return Post.QUERY.on(db.db).findOrFail(id);
    }

    private long countPosts(Object userId) {
        return Post.QUERY.on(db.db).query().where("user_id", userId).count();
    }

    @Test
    void associateDissociate() {
        Post post = post(1);
        User ann = user(1);

        Model returned = post.author().associate(ann);

        assertSame(post, returned);
        assertEquals(1L, ((Number) post.getAttribute("user_id")).longValue());
        assertSame(ann, post.getRelation("author"));

        post.author().dissociate();

        assertNull(post.getAttribute("user_id"));
        assertTrue(post.relationLoaded("author"));
        assertNull(post.getRelation("author"));
        assertEquals(0L, countPosts(1));
    }

    @Test
    void associateById() {
        Post post = post(1);
        post.setRelation("author", user(1));

        post.author().associate((Object) 5);

        assertEquals(5, post.getAttribute("user_id"));
        assertFalse(post.relationLoaded("author"));
        assertEquals(0L, countPosts(5));
    }

    @Test
    void associateByIdRefusesAModel() {
        Post post = post(1);
        Object ann = user(1);

        assertThrows(IllegalArgumentException.class, () -> post.author().associate(ann));
    }

    @Test
    void nullOwnerKeyInAssociateRefused() {
        Post post = post(1);
        User unsaved = User.QUERY.on(db.db).newModel();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> post.author().associate(unsaved));

        assertTrue(e.getMessage().contains("[id]"), e.getMessage());
        assertNull(post.getAttribute("user_id"));
        assertFalse(post.relationLoaded("author"));
    }

    @Test
    void nullParentKeyRefused() {
        User unsaved = User.QUERY.on(db.db).newModel();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> unsaved.posts().create(Map.of("title", "x")));

        assertTrue(e.getMessage().contains(User.class.getName()), e.getMessage());
        assertTrue(e.getMessage().contains("[id]"), e.getMessage());
        assertEquals(0L, Post.QUERY.on(db.db).query().where("title", "x").count());
    }

    @Test
    void saveManyNoTransaction() {
        User ann = user(1);
        Post a = Post.QUERY.on(db.db).newModel();
        a.setAttribute("title", "a1");
        Post b = Post.QUERY.on(db.db).newModel();

        assertThrows(QueryException.class, () -> ann.posts().saveMany(Arrays.asList(a, b)));

        assertTrue(a.exists());
        assertFalse(b.exists());
        assertEquals(1L, countPosts(1));
    }

    @Test
    void saveSetsForeignKeyAndSaves() {
        User ann = user(1);
        Post post = Post.QUERY.on(db.db).newModel();
        post.setAttribute("title", "s");

        Post saved = ann.posts().save(post);

        assertSame(post, saved);
        assertTrue(saved.exists());
        assertEquals(1L, ((Number) saved.getAttribute("user_id")).longValue());
        assertEquals(1L, countPosts(1));
    }

    @Test
    void makeDoesNotSave() {
        User ann = user(1);

        Post made = ann.posts().make(Map.of("title", "m"));

        assertFalse(made.exists());
        assertEquals("m", made.getAttribute("title"));
        assertEquals(1L, ((Number) made.getAttribute("user_id")).longValue());
        assertEquals(0L, countPosts(1));
    }

    @Test
    void createAndCreateManySave() {
        User ann = user(1);

        Post one = ann.posts().create(Map.of("title", "c1"));
        List<Post> many = ann.posts().createMany(Arrays.asList(Map.of("title", "c2"), Map.of("title", "c3")));

        assertTrue(one.exists());
        assertEquals(2, many.size());
        assertTrue(many.get(1).exists());
        assertEquals(3L, countPosts(1));
    }

    @Test
    void writesDoNotTouchParentRelations() {
        User ann = user(1);

        ann.posts().create(Map.of("title", "c1"));

        assertFalse(ann.relationLoaded("posts"));
        assertEquals(Collections.emptyMap(), ann.getRelations());
    }

    @Test
    void ambiguousBelongsToSetsKeyOnly() {
        TwoAuthors post = TwoAuthors.QUERY.on(db.db).findOrFail(1);

        post.writer().associate(user(1));

        assertEquals(1L, ((Number) post.getAttribute("user_id")).longValue());
        assertFalse(post.relationLoaded("writer"));
        assertFalse(post.relationLoaded("editor"));
    }

    @Test
    void ambiguousResolvedByNameSetsRelation() {
        TwoAuthors post = TwoAuthors.QUERY.on(db.db).findOrFail(1);
        BelongsTo<User> editor = relationAs(post.relation("editor"));

        editor.associate(user(1));

        assertTrue(post.relationLoaded("editor"));
        assertFalse(post.relationLoaded("writer"));
    }

    @Test
    void fourArgBelongsToNamesRelation() {
        NamedAuthor post = NamedAuthor.QUERY.on(db.db).findOrFail(1);
        User ann = user(1);

        post.writer().associate(ann);

        assertSame(ann, post.getRelation("writer"));
        post.writer().dissociate();
        assertNull(post.getRelation("writer"));
    }

    @SuppressWarnings("unchecked")
    private static BelongsTo<User> relationAs(Relation<?, ?> relation) {
        // Safe: the test model declares "editor" as BelongsTo<User>.
        return (BelongsTo<User>) relation;
    }

    /** A post with two relations on the same key: the registry cannot pick a name. */
    static final class TwoAuthors extends Model {
        static final ModelQuery<TwoAuthors> QUERY = ModelQuery.of(TwoAuthors.class, TwoAuthors::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TwoAuthors.class).add("writer", TwoAuthors::writer).add("editor", TwoAuthors::editor);
        }

        public BelongsTo<User> writer() {
            return belongsTo(User.class, "user_id");
        }

        public BelongsTo<User> editor() {
            return belongsTo(User.class, "user_id");
        }
    }

    /** A post naming its relation in the 4-arg form, outside any relation map. */
    static final class NamedAuthor extends Model {
        static final ModelQuery<NamedAuthor> QUERY = ModelQuery.of(NamedAuthor.class, NamedAuthor::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        public BelongsTo<User> writer() {
            return belongsTo(User.class, "user_id", null, "writer");
        }
    }
}
