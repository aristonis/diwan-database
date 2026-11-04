package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code Model.push()}: self first, then every loaded relation, once per model per call. */
class PushTest {

    private RelationDb db;
    private final List<String> journal = new ArrayList<>();

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        PushModels.register();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("comments", Map.of("id", 1, "post_id", 1, "body", "c1"));
        db.seed("comments", Map.of("id", 2, "post_id", 1, "body", "c2"));
        db.seed("comments", Map.of("id", 3, "post_id", 2, "body", "c3"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private PUser user(int id) {
        return track("user" + id, PUser.QUERY.on(db.db).findOrFail(id));
    }

    private PPost post(int id) {
        return track("post" + id, PPost.QUERY.on(db.db).findOrFail(id));
    }

    private ModelCollection<PComment> commentsOf(int postId) {
        ModelCollection<PComment> comments = PComment.QUERY.on(db.db).where("post_id", postId).orderBy("id").get();
        for (PComment comment : comments) {
            track("comment" + comment.getKey(), comment);
        }
        return comments;
    }

    private PComment newComment(String body) {
        PComment comment = track("new:" + body, PComment.QUERY.on(db.db).newModel());
        comment.setAttribute("body", body);
        return comment;
    }

    private <M extends Tracked> M track(String tag, M model) {
        model.tag = tag;
        model.journal = journal;
        return model;
    }

    private Object column(String table, long id, String column) {
        return db.real.selectOne("select \"" + column + "\" from \"" + table + "\" where \"id\" = ?", id)
                .orElseThrow(IllegalStateException::new).get(column);
    }

    private long count(String table) {
        Object value = db.real.selectOne("select count(*) as \"n\" from \"" + table + "\"")
                .orElseThrow(IllegalStateException::new).get("n");
        return ((Number) value).longValue();
    }

    @Test
    void savesSelfThenRelationsInOrder() {
        PPost post = post(1);
        post.setAttribute("title", "a2");
        PUser author = user(1);
        author.setAttribute("name", "Ann2");
        ModelCollection<PComment> comments = commentsOf(1);
        comments.get(0).setAttribute("body", "c1b");
        PComment draft = newComment("draft");
        draft.setAttribute("post_id", 1);
        post.setRelation("author", author);
        post.setRelation("comments", comments);
        post.setRelation("draft", draft);

        boolean pushed = post.push();

        assertTrue(pushed);
        assertEquals(Arrays.asList("post1", "user1", "comment1", "comment2", "new:draft"), journal);
        assertEquals("a2", column("posts", 1, "title"));
        assertEquals("Ann2", column("users", 1, "name"));
        assertEquals("c1b", column("comments", 1, "body"));
        assertTrue(draft.exists());
        assertEquals(4L, count("comments"));
    }

    @Test
    void selfFailureStopsEarly() {
        PPost post = post(1);
        post.vetoes = true;
        PUser author = user(1);
        author.setAttribute("name", "Ann2");
        post.setRelation("author", author);

        boolean pushed = post.push();

        assertFalse(pushed);
        assertTrue(journal.isEmpty());
        assertEquals(0, author.savingCalls);
        assertEquals("Ann", column("users", 1, "name"));
        assertTrue(author.isDirty());
    }

    @Test
    void firstFailureStopsKeepsEarlier() {
        PPost post = post(1);
        post.setAttribute("title", "a2");
        PUser author = user(1);
        author.setAttribute("name", "Ann2");
        ModelCollection<PComment> comments = commentsOf(1);
        comments.get(0).setAttribute("body", "c1b");
        comments.get(1).setAttribute("body", "c2b");
        comments.get(1).vetoes = true;
        PComment draft = newComment("draft");
        post.setRelation("author", author);
        post.setRelation("comments", comments);
        post.setRelation("draft", draft);

        boolean pushed = post.push();

        assertFalse(pushed);
        assertEquals(Arrays.asList("post1", "user1", "comment1"), journal);
        assertEquals("a2", column("posts", 1, "title"));
        assertEquals("Ann2", column("users", 1, "name"));
        assertEquals("c1b", column("comments", 1, "body"));
        assertEquals("c2", column("comments", 2, "body"));
        assertFalse(draft.exists());
        assertEquals(0, draft.savingCalls);
    }

    @Test
    void noForeignKeySet() {
        PPost post = post(1);
        PComment draft = newComment("orphan");
        post.setRelation("comments", draft);

        assertTrue(post.push());

        assertTrue(draft.exists());
        assertNull(draft.getAttribute("post_id"));
        assertNull(column("comments", ((Number) draft.getKey()).longValue(), "post_id"));
    }

    @Test
    void nullRelationSkipped() {
        PPost post = post(1);
        post.setRelation("author", null);
        post.setRelation("comments", commentsOf(1));

        assertTrue(post.push());

        assertEquals(Arrays.asList("post1", "comment1", "comment2"), journal);
    }

    @Test
    void noRelationsIsSave() {
        PPost post = post(1);
        post.setAttribute("title", "a2");
        PComment fresh = newComment("alone");

        assertTrue(post.push());
        assertTrue(fresh.push());

        assertEquals(Arrays.asList("post1", "new:alone"), journal);
        assertEquals("a2", column("posts", 1, "title"));
        assertTrue(fresh.exists());
        assertFalse(post.isDirty());
    }

    @Test
    void cycleTerminates() {
        PUser user = user(1);
        ModelCollection<PPost> posts = PPost.QUERY.on(db.db).where("user_id", 1).orderBy("id").get();
        PPost post = track("post1", posts.get(0));
        PPost other = track("post2", posts.get(1));
        post.setRelation("author", user);
        other.setRelation("author", user);
        user.setRelation("posts", posts);

        assertTrue(post.push());

        assertEquals(Arrays.asList("post1", "user1", "post2"), journal);
        assertEquals(1, post.saves);
        assertEquals(1, user.saves);
        assertEquals(1, other.saves);
    }

    @Test
    void sharedInstanceTwice() {
        PPost post = post(1);
        PComment comment = commentsOf(1).get(0);
        comment.setAttribute("body", "changed");
        post.setRelation("first", comment);
        post.setRelation("second", comment);

        assertTrue(post.push());

        assertEquals(1, comment.saves);
        assertEquals(Arrays.asList("post1", "comment1"), journal);
        assertEquals("changed", column("comments", 1, "body"));
    }

    @Test
    void diamondSavesEachOnce() {
        PComment root = commentsOf(1).get(0);
        PPost post = post(1);
        PUser user = user(1);
        post.setRelation("author", user);
        root.setRelation("post", post);
        root.setRelation("writer", user);

        assertTrue(root.push());

        assertEquals(Arrays.asList("comment1", "post1", "user1"), journal);
        assertEquals(1, user.saves);
        assertEquals(1, user.savingCalls);
    }

    @Test
    void defaultPlaceholderIsInserted() {
        PUser bob = user(2);
        bob.load("profile");
        Object placeholder = bob.getRelations().get("profile");
        assertTrue(placeholder instanceof PProfile);
        assertFalse(((Model) placeholder).exists());

        assertTrue(bob.push());

        assertTrue(((Model) placeholder).exists());
        assertEquals(1L, count("profiles"));
        long id = ((Number) ((Model) placeholder).getKey()).longValue();
        assertEquals("none", column("profiles", id, "bio"));
        assertEquals(2L, ((Number) column("profiles", id, "user_id")).longValue());
    }

    @Test
    void hooksRunPerModel() {
        PPost post = post(1);
        PUser author = user(1);
        ModelCollection<PComment> comments = commentsOf(1);
        post.setRelation("author", author);
        post.setRelation("comments", comments);

        assertTrue(post.push());

        for (Tracked model : Arrays.<Tracked>asList(post, author, comments.get(0), comments.get(1))) {
            assertEquals(1, model.savingCalls, model.tag);
            assertEquals(1, model.saves, model.tag);
        }
    }

    @Test
    void guardReleasedOnException() {
        PPost post = post(1);
        ModelCollection<PComment> comments = commentsOf(1);
        comments.get(0).explodes = true;
        post.setRelation("comments", comments);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, post::push);
        assertEquals("boom", thrown.getMessage());
        comments.get(0).explodes = false;
        journal.clear();

        assertTrue(post.push());

        assertEquals(Arrays.asList("post1", "comment1", "comment2"), journal);
    }

    @Test
    void nestedDepth() {
        PUser user = user(1);
        ModelCollection<PPost> posts = PPost.QUERY.on(db.db).where("user_id", 1).orderBy("id").get();
        track("post1", posts.get(0)).setRelation("comments", commentsOf(1));
        track("post2", posts.get(1)).setRelation("comments", commentsOf(2));
        PComment deep = (PComment) ((ModelCollection<?>) posts.get(1).getRelations().get("comments")).get(0);
        deep.setAttribute("body", "deep");
        user.setRelation("posts", posts);

        assertTrue(user.push());

        assertEquals(Arrays.asList("user1", "post1", "comment1", "comment2", "post2", "comment3"), journal);
        assertEquals("deep", column("comments", 3, "body"));
    }

    @Test
    void pushWithTouchingChildrenUpdatesOwnerPerChild() throws SQLException {
        try (TouchDb touchDb = new TouchDb()) {
            Map<String, Object> stamps = Map.of("created_at", TouchDb.OLD, "updated_at", TouchDb.OLD);
            Map<String, Object> postRow = new java.util.HashMap<>(stamps);
            postRow.put("id", 1);
            postRow.put("title", "a");
            touchDb.seed("posts", postRow);
            for (int id = 1; id <= 2; id++) {
                Map<String, Object> commentRow = new java.util.HashMap<>(stamps);
                commentRow.put("id", id);
                commentRow.put("post_id", 1);
                commentRow.put("body", "c" + id);
                touchDb.seed("comments", commentRow);
            }
            TouchDb.TPost post = TouchDb.TPost.QUERY.on(touchDb.db).findOrFail(1);
            ModelCollection<TouchDb.TComment> comments =
                    TouchDb.TComment.QUERY.on(touchDb.db).where("post_id", 1).orderBy("id").get();
            for (TouchDb.TComment comment : comments) {
                comment.setAttribute("body", comment.getAttribute("body") + "b");
                comment.setRelation("post", post);
            }
            post.setRelation("comments", comments);
            touchDb.clearLog();

            assertTrue(post.push());

            // Each comment's save runs its own touch walk (Laravel): one posts UPDATE per child,
            // each also running the linked post's saved(), on top of the post's own clean save.
            int postUpdates = touchDb.count("update \"posts\"");
            assertEquals(2, postUpdates);
            assertEquals(1 + postUpdates, post.savedCalls);
        }
    }

    // ---- models

    /** Registers the push test models before a relation names them. */
    static final class PushModels {

        private PushModels() {
        }

        static void register() {
            List<ModelQuery<?>> entries = Arrays.asList(PUser.QUERY, PPost.QUERY, PComment.QUERY, PProfile.QUERY);
            if (entries.contains(null)) {
                throw new IllegalStateException("a model entry is not initialised");
            }
        }
    }

    /** A model that records its saves in the test's journal and can veto or throw on save. */
    abstract static class Tracked extends Model {
        String tag;
        List<String> journal;
        boolean vetoes;
        boolean explodes;
        int savingCalls;
        int saves;

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean saving() {
            savingCalls++;
            if (explodes) {
                throw new IllegalStateException("boom");
            }
            return !vetoes;
        }

        @Override
        protected void saved() {
            saves++;
            if (journal != null) {
                journal.add(tag);
            }
        }
    }

    /** A user: has many posts, has one profile with a default. */
    static final class PUser extends Tracked {
        static final ModelQuery<PUser> QUERY = ModelQuery.of(PUser.class, PUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(PUser.class).add("posts", PUser::posts).add("profile", PUser::profile);
        }

        public HasMany<PPost> posts() {
            return hasMany(PPost.class, "user_id");
        }

        public HasOne<PProfile> profile() {
            return hasOne(PProfile.class, "user_id").withDefault(Map.of("bio", "none"));
        }
    }

    /** A post: belongs to its author, has many comments. */
    static final class PPost extends Tracked {
        static final ModelQuery<PPost> QUERY = ModelQuery.of(PPost.class, PPost::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(PPost.class).add("author", PPost::author).add("comments", PPost::comments);
        }

        public BelongsTo<PUser> author() {
            return belongsTo(PUser.class, "user_id");
        }

        public HasMany<PComment> comments() {
            return hasMany(PComment.class, "post_id");
        }
    }

    /** A comment: belongs to its post. */
    static final class PComment extends Tracked {
        static final ModelQuery<PComment> QUERY = ModelQuery.of(PComment.class, PComment::new);

        @Override
        protected String table() {
            return "comments";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(PComment.class).add("post", PComment::post);
        }

        public BelongsTo<PPost> post() {
            return belongsTo(PPost.class, "post_id");
        }
    }

    /** A profile: belongs to its user. */
    static final class PProfile extends Tracked {
        static final ModelQuery<PProfile> QUERY = ModelQuery.of(PProfile.class, PProfile::new);

        @Override
        protected String table() {
            return "profiles";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(PProfile.class).add("user", PProfile::user);
        }

        public BelongsTo<PUser> user() {
            return belongsTo(PUser.class, "user_id");
        }
    }
}
