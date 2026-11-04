package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.TouchScope;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code withoutTouching} and {@code withoutTouchingOn}: which owner touches a scope stops. */
class WithoutTouchingTest {

    /** A touched owner with no relations of its own. Not final: {@link SpecialPost} extends it. */
    static class TouchPost extends Model {
        static final ModelQuery<TouchPost> QUERY = ModelQuery.of(TouchPost.class, TouchPost::new);

        @Override
        protected String table() {
            return "wt_posts";
        }
    }

    /** A subclass of the touched owner, on its own table. */
    static final class SpecialPost extends TouchPost {
        static final ModelQuery<SpecialPost> SPECIAL = ModelQuery.of(SpecialPost.class, SpecialPost::new);

        @Override
        protected String table() {
            return "wt_special_posts";
        }
    }

    /** A second touched owner class. */
    static final class TouchUser extends Model {
        static final ModelQuery<TouchUser> QUERY = ModelQuery.of(TouchUser.class, TouchUser::new);

        @Override
        protected String table() {
            return "wt_users";
        }
    }

    /** Touches its post, its special post and its user on save and delete. */
    static final class TouchComment extends Model {
        static final ModelQuery<TouchComment> QUERY = ModelQuery.of(TouchComment.class, TouchComment::new);

        @Override
        protected String table() {
            return "wt_comments";
        }

        @Override
        protected Set<String> touches() {
            return new LinkedHashSet<>(Arrays.asList("post", "special", "user"));
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TouchComment.class).add("post", TouchComment::post)
                    .add("special", TouchComment::special).add("user", TouchComment::user);
        }

        public BelongsTo<TouchPost> post() {
            return belongsTo(TouchPost.class, "post_id");
        }

        public BelongsTo<SpecialPost> special() {
            return belongsTo(SpecialPost.class, "special_id");
        }

        public BelongsTo<TouchUser> user() {
            return belongsTo(TouchUser.class, "user_id");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        createTables(db.real);
        seed(db.real);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    /**
     * Creates the touch tables on a connection.
     *
     * @param real the connection
     */
    static void createTables(Connection real) {
        register();
        for (String owner : Arrays.asList("wt_posts", "wt_special_posts", "wt_users")) {
            SQLiteSchema.of(real).create(owner, t -> {
                t.id();
                t.string("title").nullable();
                t.timestamps();
            });
        }
        SQLiteSchema.of(real).create("wt_comments", t -> {
            t.id();
            t.integer("post_id").nullable();
            t.integer("special_id").nullable();
            t.integer("user_id").nullable();
            t.string("body").nullable();
            t.timestamps();
        });
    }

    // Initialises every entry, so each class is registered before a relation names it.
    private static void register() {
        if (Arrays.asList(TouchPost.QUERY, SpecialPost.SPECIAL, TouchUser.QUERY, TouchComment.QUERY).contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /**
     * Seeds owners 1 and 2 of each class with no {@code updated_at}, and comment {@code n}
     * pointing at owners {@code n}.
     *
     * @param real the connection
     */
    static void seed(Connection real) {
        for (int id = 1; id <= 2; id++) {
            for (String owner : Arrays.asList("wt_posts", "wt_special_posts", "wt_users")) {
                SQLiteQuery.of(real).table(owner).insert(Map.of("id", id, "title", "t" + id));
            }
            SQLiteQuery.of(real).table("wt_comments")
                    .insert(Map.of("id", id, "post_id", id, "special_id", id, "user_id", id, "body", "b"));
        }
    }

    /**
     * Returns whether an owner row has an {@code updated_at} value.
     *
     * @param real the connection
     * @param table the owner table
     * @param id the owner key
     * @return whether the row was touched
     */
    static boolean touched(Connection real, String table, int id) {
        return SQLiteQuery.of(real).table(table).where("id", id).value("updated_at").isPresent();
    }

    private TouchComment dirtyComment(int id) {
        TouchComment comment = TouchComment.QUERY.on(db.db).findOrFail(id);
        comment.setAttribute("body", "changed");
        return comment;
    }

    @Test
    void savingOutsideAScopeTouchesEveryOwner() {
        dirtyComment(1).save();

        assertTrue(touched(db.real, "wt_posts", 1));
        assertTrue(touched(db.real, "wt_special_posts", 1));
        assertTrue(touched(db.real, "wt_users", 1));
    }

    @Test
    void listsTheTouchedClass() {
        TouchComment comment = dirtyComment(1);

        boolean saved = Model.withoutTouching(TouchPost.class, () -> comment.save());

        assertTrue(saved);
        assertFalse(comment.isDirty());
        assertFalse(touched(db.real, "wt_posts", 1));
        assertTrue(touched(db.real, "wt_users", 1));
    }

    @Test
    void subclassIgnored() {
        TouchComment comment = dirtyComment(1);

        Model.withoutTouching(TouchPost.class, () -> {
            comment.save();
        });

        assertFalse(touched(db.real, "wt_posts", 1));
        assertFalse(touched(db.real, "wt_special_posts", 1));
        assertTrue(touched(db.real, "wt_users", 1));
    }

    @Test
    void severalClasses() {
        TouchComment comment = dirtyComment(1);

        String result = Model.withoutTouchingOn(Arrays.asList(TouchPost.class, TouchUser.class), () -> {
            comment.save();
            return "done";
        });

        assertEquals("done", result);
        assertFalse(touched(db.real, "wt_posts", 1));
        assertFalse(touched(db.real, "wt_special_posts", 1));
        assertFalse(touched(db.real, "wt_users", 1));
    }

    @Test
    void emptyClassListIgnoresNothing() {
        TouchComment comment = dirtyComment(1);

        Model.withoutTouchingOn(Collections.emptyList(), () -> comment.save());

        assertTrue(touched(db.real, "wt_posts", 1));
    }

    @Test
    void ownTouchUnaffected() {
        TouchPost post = TouchPost.QUERY.on(db.db).findOrFail(1);

        boolean saved = Model.withoutTouching(TouchPost.class, () -> post.touch());

        assertTrue(saved);
        assertTrue(touched(db.real, "wt_posts", 1));
    }

    @Test
    void restoredAfterException() {
        TouchComment comment = dirtyComment(1);
        IllegalStateException thrown = new IllegalStateException("boom");

        IllegalStateException caught = assertThrows(IllegalStateException.class,
                () -> Model.withoutTouching(TouchPost.class, () -> {
                    comment.save();
                    throw thrown;
                }));

        assertSame(thrown, caught);
        assertFalse(TouchScope.isActive());
        assertFalse(touched(db.real, "wt_posts", 1));
        dirtyComment(2).save();
        assertTrue(touched(db.real, "wt_posts", 2));
    }

    @Test
    void nestedScopes() {
        Model.withoutTouching(TouchUser.class, () -> {
            Model.withoutTouching(TouchPost.class, () -> {
                dirtyComment(1).save();
            });
            dirtyComment(2).save();
            assertTrue(TouchScope.isActive());
        });

        assertFalse(TouchScope.isActive());
        assertFalse(touched(db.real, "wt_posts", 1));
        assertFalse(touched(db.real, "wt_users", 1));
        assertTrue(touched(db.real, "wt_posts", 2));
        assertFalse(touched(db.real, "wt_users", 2));
    }

    @Test
    void nestedScopeOverTheSameClassKeepsTheOuterOne() {
        Model.withoutTouching(TouchPost.class, () -> {
            Model.withoutTouching(TouchPost.class, () -> {
                dirtyComment(1).save();
            });
            dirtyComment(2).save();
        });

        assertFalse(touched(db.real, "wt_posts", 1));
        assertFalse(touched(db.real, "wt_posts", 2));
    }

    @Test
    void deleteInsideTheScopeDoesNotTouch() {
        TouchComment comment = TouchComment.QUERY.on(db.db).findOrFail(1);

        boolean deleted = Model.withoutTouching(TouchPost.class, () -> comment.delete());

        assertTrue(deleted);
        assertFalse(touched(db.real, "wt_posts", 1));
        assertTrue(touched(db.real, "wt_users", 1));
    }

    @Test
    void supplierOverloadReturnsTheValue() {
        TouchComment comment = dirtyComment(1);

        // An expression lambda with a value resolves to the Supplier form (JLS 15.12.2.5).
        Boolean saved = Model.withoutTouching(TouchPost.class, () -> comment.save());

        assertEquals(Boolean.TRUE, saved);
    }

    @Test
    void runnableOverloadRunsTheBlock() {
        boolean[] ran = {false};

        // A block lambda with no value can only be a Runnable.
        Model.withoutTouching(TouchPost.class, () -> {
            ran[0] = true;
        });

        assertTrue(ran[0]);
        assertFalse(TouchScope.isActive());
    }

    @Test
    void nullArgumentsAreRefusedBeforeAnyScope() {
        Runnable noop = () -> { };

        assertThrows(NullPointerException.class, () -> Model.withoutTouching(null, noop));
        assertThrows(NullPointerException.class, () -> Model.withoutTouching(TouchPost.class, (Runnable) null));
        assertThrows(NullPointerException.class,
                () -> Model.withoutTouching(TouchPost.class, (java.util.function.Supplier<Object>) null));
        assertThrows(NullPointerException.class, () -> Model.withoutTouchingOn(null, () -> 1));
        assertThrows(NullPointerException.class,
                () -> Model.withoutTouchingOn(Collections.<Class<? extends Model>>singletonList(null), () -> 1));
        assertFalse(TouchScope.isActive());
    }
}
