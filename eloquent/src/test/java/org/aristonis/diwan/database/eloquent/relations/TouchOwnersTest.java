package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TComment;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TPost;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TTopic;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TouchOwnersTest {

    private static final String UPDATE_POSTS = "update \"posts\"";
    private static final String UPDATE_USERS = "update \"users\"";
    private static final String UPDATE_TOPICS = "update \"topics\"";
    private static final String UPDATE_COMMENTS = "update \"comments\"";

    private TouchDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new TouchDb();
        db.seed("users", row("id", 1, "name", "Ann"));
        db.seed("posts", row("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", row("id", 2, "user_id", 1, "title", "b"));
        db.seed("topics", row("id", 1, "user_id", null, "title", "t"));
        db.seed("comments", row("id", 1, "post_id", 1, "topic_id", null, "body", "x"));
        db.seed("comments", row("id", 2, "post_id", 1, "topic_id", 1, "body", "y"));
        db.seed("comments", row("id", 3, "post_id", 1, "topic_id", 1, "body", "z"));
        db.seed("comments", row("id", 4, "post_id", 1, "topic_id", 1, "body", "w"));
    }

    @AfterEach
    void close() throws SQLException {
        ModelQuery.preventLazyLoading(false);
        db.close();
    }

    // Seeded rows carry the old timestamps, so a touch is visible as a changed value.
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        row.put("created_at", TouchDb.OLD);
        row.put("updated_at", TouchDb.OLD);
        return row;
    }

    private TUser user(int id) {
        return TUser.QUERY.on(db.db).findOrFail(id);
    }

    private TPost post(int id) {
        return TPost.QUERY.on(db.db).findOrFail(id);
    }

    private TTopic topic(int id) {
        return TTopic.QUERY.on(db.db).findOrFail(id);
    }

    private TComment comment(int id) {
        return TComment.QUERY.on(db.db).findOrFail(id);
    }

    private ModelCollection<TComment> only(TComment comment) {
        return Access.eloquent().newCollection(TComment.QUERY.on(db.db).query(),
                Collections.singletonList(comment));
    }

    private ModelCollection<TComment> topicComments() {
        return TComment.QUERY.on(db.db).query().where("topic_id", 1).orderBy("id").get();
    }

    private static void touch(TComment comment) {
        Relation.ensureInitialised();
        Access.relations().touchOwners(comment, new VisitPath());
    }

    private static void touch(TTopic topic) {
        Relation.ensureInitialised();
        Access.relations().touchOwners(topic, new VisitPath());
    }

    // ---- triggers (the save and delete hooks)

    @Test
    void insertTouchesOwner() {
        TComment comment = TComment.QUERY.on(db.db).newModel();
        comment.forceFill(Map.of("post_id", 2, "body", "new"));
        db.clearLog();

        assertTrue(comment.save());

        assertEquals(1, db.count(UPDATE_POSTS));
        assertTrue(db.sql().contains("update \"posts\" set \"updated_at\" = ? where \"posts\".\"id\" = ?"));
        assertEquals(0, db.selects());
        assertNotEquals(TouchDb.OLD, db.updatedAt("posts", 2));
        assertEquals(TouchDb.OLD, db.updatedAt("posts", 1));
    }

    @Test
    void updateTouchesOwner() {
        TComment comment = comment(1);
        comment.setAttribute("body", "changed");
        db.clearLog();

        assertTrue(comment.save());

        assertEquals(1, db.count(UPDATE_POSTS));
        assertEquals(0, db.selects());
        assertNotEquals(TouchDb.OLD, db.updatedAt("posts", 1));
    }

    @Test
    void cleanSaveNoTouch() {
        TComment comment = comment(1);
        db.clearLog();

        assertTrue(comment.save());

        assertTrue(db.sql().isEmpty());
        assertEquals(TouchDb.OLD, db.updatedAt("posts", 1));
    }

    @Test
    void vetoedSaveNoTouch() {
        TComment comment = comment(1);
        comment.vetoes = true;
        comment.setAttribute("body", "changed");
        db.clearLog();

        assertFalse(comment.save());

        assertTrue(db.sql().isEmpty());
        assertEquals(TouchDb.OLD, db.updatedAt("posts", 1));
    }

    @Test
    void deleteTouchesBeforeDelete() {
        TComment comment = comment(1);
        db.clearLog();

        assertTrue(comment.delete());

        List<String> sql = db.sql();
        assertEquals(2, sql.size());
        assertTrue(sql.get(0).startsWith(UPDATE_POSTS));
        assertTrue(sql.get(1).startsWith("delete from \"comments\""));
        assertNotEquals(TouchDb.OLD, db.updatedAt("posts", 1));
    }

    @Test
    void vetoedDeleteNoTouch() {
        TComment vetoed = comment(1);
        vetoed.vetoes = true;
        TComment unsaved = TComment.QUERY.on(db.db).newModel();
        unsaved.setAttribute("post_id", 1);
        db.clearLog();

        assertFalse(vetoed.delete());
        assertFalse(unsaved.delete());

        assertTrue(db.sql().isEmpty());
        assertEquals(TouchDb.OLD, db.updatedAt("posts", 1));
    }

    // ---- the touch itself

    @Test
    void hasManyTouchesChildren() {
        TTopic topic = topic(1);
        db.clearLog();

        touch(topic);

        assertEquals(Collections.singletonList("update \"comments\" set \"updated_at\" = ? where "
                + "\"comments\".\"topic_id\" = ? and \"comments\".\"topic_id\" is not null"), db.sql());
        assertNotEquals(TouchDb.OLD, db.updatedAt("comments", 2));
        assertEquals(TouchDb.OLD, db.updatedAt("comments", 1));
    }

    @Test
    void recursesIntoLoadedOwner() {
        TComment comment = comment(1);
        TPost post = post(1);
        comment.setRelation("post", post);
        db.clearLog();

        touch(comment);

        assertEquals(Arrays.asList(UPDATE_POSTS, UPDATE_USERS), prefixes(db.sql()));
        assertEquals(1, post.savedCalls);
        assertEquals(0, comment.savedCalls);
        assertNotEquals(TouchDb.OLD, db.updatedAt("users", 1));
    }

    @Test
    void noLazyLoad() {
        TComment comment = comment(1);
        db.clearLog();

        touch(comment);

        assertEquals(Collections.singletonList(UPDATE_POSTS), prefixes(db.sql()));
        assertFalse(comment.relationLoaded("post"));
        assertEquals(TouchDb.OLD, db.updatedAt("users", 1));
    }

    @Test
    void loadedNullStops() {
        TComment comment = comment(1);
        comment.setRelation("post", null);
        db.clearLog();

        touch(comment);

        assertEquals(Collections.singletonList(UPDATE_POSTS), prefixes(db.sql()));
    }

    @Test
    void recursesIntoLoadedCollection() {
        TTopic topic = topic(1);
        ModelCollection<TComment> comments = topicComments();
        topic.setRelation("comments", comments);
        db.clearLog();

        touch(topic);

        // The topic's comments, then per loaded comment its post and its (unloaded) topic.
        assertEquals(1, db.count(UPDATE_COMMENTS));
        assertEquals(comments.size(), db.count(UPDATE_POSTS));
        assertEquals(comments.size(), db.count(UPDATE_TOPICS));
        assertEquals(0, db.selects());
        for (TComment comment : comments) {
            assertEquals(0, comment.savedCalls);
        }
        assertEquals(0, topic.savedCalls);
    }

    @Test
    void cycleTerminates() {
        TTopic topic = topic(1);
        TComment comment = comment(2);
        topic.setRelation("comments", only(comment));
        comment.setRelation("topic", topic);
        db.clearLog();

        touch(comment);

        // HasMany always updates, since a loaded value may be partial; the walk into the done comment is skipped.
        assertEquals(Arrays.asList(UPDATE_POSTS, UPDATE_TOPICS, UPDATE_COMMENTS), prefixes(db.sql()));
        assertEquals(1, topic.savedCalls);
        assertEquals(0, comment.savedCalls);
    }

    @Test
    void sharedOwnerTouchedOnce() {
        TTopic topic = topic(1);
        ModelCollection<TComment> comments = topicComments();
        TPost shared = post(1);
        for (TComment comment : comments) {
            comment.setRelation("post", shared);
            comment.setRelation("topic", topic);
        }
        topic.setRelation("comments", comments);
        db.clearLog();

        touch(topic);

        // The shared loaded post is touched and walked once, so one posts UPDATE, one
        // saved() and one author UPDATE. The loaded topic is the walked root: no topics UPDATE.
        assertEquals(1, db.count(UPDATE_COMMENTS));
        assertEquals(1, db.count(UPDATE_POSTS));
        assertEquals(1, db.count(UPDATE_USERS));
        assertEquals(0, db.count(UPDATE_TOPICS));
        assertEquals(0, db.selects());
        assertEquals(1, shared.savedCalls);
        assertEquals(0, topic.savedCalls);
    }

    @Test
    void diamondWalksSharedOwnerOnce() {
        TComment comment = comment(2);
        TPost post = post(1);
        TTopic topic = topic(1);
        TUser user = user(1);
        topic.setAttribute("user_id", 1);
        post.setRelation("author", user);
        topic.setRelation("owner", user);
        comment.setRelation("post", post);
        comment.setRelation("topic", topic);
        db.clearLog();

        touch(comment);

        // comment -> post -> user and comment -> topic -> user: the shared user is touched and
        // walked (saved()) once; the topic's BelongsTo edge to it, same key, sends no second UPDATE.
        assertEquals(Arrays.asList(UPDATE_POSTS, UPDATE_USERS, UPDATE_TOPICS, UPDATE_COMMENTS),
                prefixes(db.sql()));
        assertEquals(1, post.savedCalls);
        assertEquals(1, topic.savedCalls);
        assertEquals(1, user.savedCalls);
    }

    @Test
    void unloadedSharedOwnerUpdatedPerChild() {
        TTopic topic = topic(1);
        ModelCollection<TComment> comments = topicComments();
        topic.setRelation("comments", comments);
        db.clearLog();

        touch(topic);

        // "post" is not loaded on the comments, so the owner is unknown: one UPDATE per comment,
        // all on the same posts row.
        assertEquals(comments.size(), db.count(UPDATE_POSTS));
        assertEquals(0, db.selects());
    }

    @Test
    void partialLoadedHasManyStillUpdatesSiblings() {
        TComment comment = comment(2);
        TTopic topic = topic(1);
        comment.setRelation("topic", topic);
        topic.setRelation("comments", only(comment));
        db.clearLog();

        touch(comment);

        // Only comment 2 is loaded and it is done, but comments 3 and 4 share the topic.
        assertEquals(1, db.count(UPDATE_COMMENTS));
        assertNotEquals(TouchDb.OLD, db.updatedAt("comments", 3));
        assertNotEquals(TouchDb.OLD, db.updatedAt("comments", 4));
        assertEquals(0, comment.savedCalls);
    }

    @Test
    void staleLoadedBelongsToOwnerStillUpdatesCurrentRow() {
        TTopic topic = topic(1);
        ModelCollection<TComment> comments = topicComments();
        TPost first = post(1);
        for (TComment comment : comments) {
            comment.setRelation("post", first);
        }
        comments.get(comments.size() - 1).setAttribute("post_id", 2);
        topic.setRelation("comments", comments);
        db.clearLog();

        touch(topic);

        // Two comments point at the walked post 1 (one UPDATE); the moved one updates post 2.
        assertEquals(2, db.count(UPDATE_POSTS));
        assertNotEquals(TouchDb.OLD, db.updatedAt("posts", 2));
        assertEquals(1, first.savedCalls);
    }

    @Test
    void inMemoryOwnerStale() {
        TComment comment = comment(1);
        TPost post = post(1);
        comment.setRelation("post", post);
        Object before = post.getAttribute("updated_at");
        db.clearLog();

        touch(comment);

        assertEquals(before, post.getAttribute("updated_at"));
        assertFalse(post.isDirty());
        assertNotEquals(TouchDb.OLD, db.updatedAt("posts", 1));
    }

    @Test
    void worksWithLazyLoadingPrevented() {
        TTopic topic = topic(1);
        ModelCollection<TComment> comments = topicComments();
        topic.setRelation("comments", comments);
        db.clearLog();
        ModelQuery.preventLazyLoading(true);

        assertDoesNotThrow(() -> touch(topic));

        assertEquals(0, db.selects());
    }

    @Test
    void hookSavingOwnerEnds() {
        TComment comment = comment(1);
        TPost post = post(1);
        post.savesInHook = true;
        comment.setRelation("post", post);
        comment.setAttribute("body", "changed");
        db.clearLog();

        assertTrue(comment.save());

        // The hook's save is clean (raw touch), so it runs saved() once more and no touch.
        assertEquals(2, post.savedCalls);
        assertEquals(1, db.count(UPDATE_POSTS));
        assertEquals(1, db.count(UPDATE_USERS));
    }

    @Test
    void pathReleasedOnException() {
        TComment comment = comment(1);
        TPost post = post(1);
        post.throwsInHook = true;
        comment.setRelation("post", post);
        VisitPath path = new VisitPath();

        Relation.ensureInitialised();
        assertThrows(IllegalStateException.class, () -> Access.relations().touchOwners(comment, path));

        assertFalse(path.contains(comment));
        assertTrue(path.isDone(comment));
    }

    @Test
    void doneModelNotWalkedAgainOnSamePath() {
        TComment comment = comment(1);
        VisitPath path = new VisitPath();
        Relation.ensureInitialised();
        Access.relations().touchOwners(comment, path);
        db.clearLog();

        Access.relations().touchOwners(comment, path);

        assertTrue(db.sql().isEmpty());
    }

    // The statement starts, by table, so the order of touches can be compared.
    private static List<String> prefixes(List<String> sql) {
        String[] known = {UPDATE_POSTS, UPDATE_USERS, UPDATE_TOPICS, UPDATE_COMMENTS};
        List<String> out = new ArrayList<>();
        for (String text : sql) {
            String found = text;
            for (String prefix : known) {
                if (text.startsWith(prefix)) {
                    found = prefix;
                }
            }
            out.add(found);
        }
        return out;
    }
}
