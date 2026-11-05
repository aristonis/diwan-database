package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.MorphWithModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphWithModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphWithModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MorphWithTest {

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
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<Comment> comments() {
        db.clearLog();
        return Comment.QUERY.on(db.db).query().orderBy("id");
    }

    private static Model owner(Comment comment) {
        return comment.related("commentable");
    }

    private static String nameOf(Model model, String relation) {
        Model related = model.related(relation);
        return related == null ? null : (String) related.getAttribute("name");
    }

    @Test
    void eachClassLoadsOnlyItsOwnRelation() {
        List<Comment> loaded = comments().withMorph("commentable",
                o -> o.morphWith(Post.class, "author").morphWith(Video.class, "channel")).get();

        assertEquals(5, db.sql().size(), db.sql().toString());
        assertEquals("Ann", nameOf(owner(loaded.get(0)), "author"));
        assertEquals("Main", nameOf(owner(loaded.get(1)), "channel"));
        assertEquals("Bob", nameOf(owner(loaded.get(2)), "author"));
        assertFalse(owner(loaded.get(1)).relationLoaded("author"));
        assertFalse(owner(loaded.get(0)).relationLoaded("channel"));
    }

    @Test
    void morphWithCountAddsTheCountToOneClassOnly() {
        List<Comment> loaded = comments().withMorph("commentable", o -> o.morphWithCount(Post.class, "likes")).get();

        assertEquals(2L, ((Number) owner(loaded.get(0)).getAttribute("likes_count")).longValue());
        assertEquals(1L, ((Number) owner(loaded.get(2)).getAttribute("likes_count")).longValue());
        assertNull(owner(loaded.get(1)).getAttribute("likes_count"));
    }

    @Test
    void constrainFiltersOneClassAndLeavesTheOtherAlone() {
        List<Comment> loaded = comments().withMorph("commentable",
                o -> o.constrain(Post.class, q -> q.where("published", 1))).get();

        assertEquals("post five", owner(loaded.get(0)).getAttribute("title"));
        assertNull(loaded.get(2).related("commentable"));
        assertEquals("video five", owner(loaded.get(1)).getAttribute("title"));
    }

    @Test
    void keysGivenAsAliasesWorkLikeClasses() {
        List<Comment> loaded = comments().withMorph("commentable",
                o -> o.morphWith("post", "author").morphWith("video", "channel")).get();

        assertEquals("Ann", nameOf(owner(loaded.get(0)), "author"));
        assertEquals("Main", nameOf(owner(loaded.get(1)), "channel"));
    }

    @Test
    void unknownAliasThrowsAtRegistrationWithNothingRun() {
        db.clearLog();
        MorphEagerOptions options = new MorphEagerOptions();

        ClassMorphViolationException thrown = assertThrows(ClassMorphViolationException.class,
                () -> options.morphWith("photo", "author"));

        assertTrue(thrown.getMessage().contains("photo"), thrown.getMessage());
        assertEquals(0, db.sql().size());
        assertThrows(ClassMorphViolationException.class, () -> options.morphWithCount("photo", "likes"));
        assertThrows(ClassMorphViolationException.class, () -> options.constrain("photo", q -> {
        }));
    }

    @Test
    void unknownAliasThroughWithMorphThrowsBeforeTheQuery() {
        EloquentBuilder<Comment> query = comments();

        assertThrows(ClassMorphViolationException.class,
                () -> query.withMorph("commentable", o -> o.morphWith("photo", "author")));
        assertEquals(0, db.sql().size());
    }

    @Test
    void wildcardAndNonModelKeysAreRefused() {
        MorphEagerOptions options = new MorphEagerOptions();

        assertThrows(IllegalArgumentException.class, () -> options.morphWith("*", "author"));
        assertThrows(IllegalArgumentException.class, () -> options.morphWith(String.class, "author"));
        assertThrows(IllegalArgumentException.class, () -> options.morphWith(42, "author"));
        assertThrows(NullPointerException.class, () -> options.morphWith(null, "author"));
    }

    @Test
    void perClassEagerLoadsDoNotLeakToOtherClasses() {
        List<Comment> loaded = comments().withMorph("commentable",
                o -> o.constrain(Post.class, q -> q.with("author"))).get();

        assertEquals("Ann", nameOf(owner(loaded.get(0)), "author"));
        assertInstanceOf(Video.class, owner(loaded.get(1)));
        assertFalse(owner(loaded.get(1)).relationLoaded("author"));
    }

    @Test
    void withMorphThenWithKeepsTheOptions() {
        List<Comment> loaded = comments().withMorph("commentable", o -> o.morphWith(Post.class, "author"))
                .with("commentable").get();

        assertEquals("Ann", nameOf(owner(loaded.get(0)), "author"));
    }

    @Test
    void withThenWithMorphKeepsTheOptions() {
        List<Comment> loaded = comments().with("commentable")
                .withMorph("commentable", o -> o.morphWith(Post.class, "author")).get();

        assertEquals("Ann", nameOf(owner(loaded.get(0)), "author"));
    }

    @Test
    void plainConstraintThenWithMorphRunsBothParts() {
        List<String> calls = new ArrayList<>();
        Consumer<EloquentBuilder<?>> plain = q -> calls.add("plain");

        List<Comment> loaded = comments().with("commentable", plain)
                .withMorph("commentable", o -> o.morphWith(Post.class, "author")).get();

        assertTrue(calls.contains("plain"));
        assertEquals("Ann", nameOf(owner(loaded.get(0)), "author"));
    }

    @Test
    void withMorphTwiceMergesTheOptions() {
        List<Comment> loaded = comments().withMorph("commentable", o -> o.morphWith(Post.class, "author"))
                .withMorph("commentable", o -> o.morphWith(Video.class, "channel")).get();

        assertEquals("Ann", nameOf(owner(loaded.get(0)), "author"));
        assertEquals("Main", nameOf(owner(loaded.get(1)), "channel"));
    }

    @Test
    void nestedMergeKeepsTheOptions() {
        db.clearLog();

        List<Post> posts = Post.QUERY.on(db.db).query().orderBy("id").with("comments.commentable")
                .with("comments", q -> q.withMorph("commentable", o -> o.morphWith(Post.class, "author"))).get();

        Comment comment = (Comment) ((List<?>) posts.get(0).getRelation("comments")).get(0);
        assertEquals("Ann", nameOf(owner(comment), "author"));
    }

    @Test
    void combineChainsOptionsAndPlainConstraintsInOrder() {
        List<String> calls = new ArrayList<>();
        Consumer<EloquentBuilder<?>> first = MorphEagerConstraint.of(o -> o.morphWith(Post.class, "author"),
                q -> calls.add("a"));
        Consumer<EloquentBuilder<?>> second = MorphEagerConstraint.of(o -> o.morphWith(Post.class, "likes"),
                q -> calls.add("b"));

        Consumer<EloquentBuilder<?>> merged = MorphEagerConstraint.combine(first, second);
        merged.accept(null);

        assertEquals(List.of("a", "b"), calls);
        MorphEagerOptions options = ((MorphEagerConstraint) merged).options();
        assertEquals(List.of("author", "likes"), options.relationsFor(Post.class));
        assertEquals(List.of("author"), ((MorphEagerConstraint) first).options().relationsFor(Post.class));
    }

    @Test
    void combineOfPlainConstraintsIsAndThen() {
        List<String> calls = new ArrayList<>();
        Consumer<EloquentBuilder<?>> a = q -> calls.add("a");
        Consumer<EloquentBuilder<?>> b = q -> calls.add("b");

        Consumer<EloquentBuilder<?>> merged = MorphEagerConstraint.combine(a, b);
        merged.accept(null);

        assertEquals(List.of("a", "b"), calls);
        assertFalse(merged instanceof MorphEagerConstraint);
    }

    @Test
    void repeatedOptionsForOneClassAppendAndChain() {
        List<String> calls = new ArrayList<>();
        MorphEagerOptions options = new MorphEagerOptions().morphWith(Post.class, "author")
                .morphWith("post", "likes").constrain(Post.class, q -> calls.add("one"))
                .constrain("post", q -> calls.add("two"));

        options.constraintFor(Post.class).accept(null);

        assertEquals(List.of("author", "likes"), options.relationsFor(Post.class));
        assertEquals(List.of("one", "two"), calls);
        assertEquals(List.of(), options.relationsFor(Video.class));
        assertNull(options.constraintFor(Video.class));
        assertSame(options, options.morphWithCount(Video.class));
    }
}
