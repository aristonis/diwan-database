package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.factory.RecycleMode;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a terminal call shares inside itself and what two calls never share, on SQLite. */
class FactoryRunStateTest {

    private static final String SECRET = "a-value-that-must-not-leak";
    private static final long SEED = 7L;
    private static final int PICKS = 8;

    /** An author. */
    static final class Author extends Model {
        static final ModelQuery<Author> QUERY = ModelQuery.of(Author.class, Author::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A post with an author, comments and tags. */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("author", Post::author).add("comments", Post::comments)
                    .add("tags", Post::tags);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }

        public HasMany<Comment> comments() {
            return hasMany(Comment.class, "post_id");
        }

        public BelongsToMany<Tag> tags() {
            return belongsToMany(Tag.class, "post_tag", "post_id", "tag_id");
        }
    }

    /** A comment of a post, written by an author. */
    static final class Comment extends Model {
        static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Comment.class).add("author", Comment::author);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }
    }

    /** A tag. */
    static final class Tag extends Model {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private SqliteModelDb database;
    private List<String> log;

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        log = new ArrayList<>();
        database.sql("create table authors (id integer primary key autoincrement, name text)");
        database.sql("create table posts (id integer primary key autoincrement, author_id integer, title text)");
        database.sql("create table comments (id integer primary key autoincrement, post_id integer,"
                + " author_id integer, body text)");
        database.sql("create table tags (id integer primary key autoincrement, label text)");
        database.sql("create table post_tag (post_id integer, tag_id integer)");
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private long count(String table) {
        return ((Number) database.scalar("select count(*) from " + table)).longValue();
    }

    private Factory<Author> authors() {
        return Factory.of(Author.QUERY.on(database.db), () -> map("name", "ann"));
    }

    private Factory<Post> posts() {
        return Factory.of(Post.QUERY.on(database.db), () -> map("title", "t", "author_id", authors()));
    }

    private Factory<Post> barePosts() {
        return Factory.of(Post.QUERY.on(database.db), () -> map("title", "t"));
    }

    private Factory<Comment> comments() {
        return Factory.of(Comment.QUERY.on(database.db), () -> map("body", "b", "author_id", authors()));
    }

    private Factory<Comment> bareComments() {
        return Factory.of(Comment.QUERY.on(database.db), () -> map("body", "b"));
    }

    private Factory<Tag> tags() {
        return Factory.of(Tag.QUERY.on(database.db), () -> map("label", "l"));
    }

    private List<Author> savedAuthors(int number) {
        List<Author> saved = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            saved.add(authors().create());
        }
        return saved;
    }

    private List<Long> column(String table, String column) {
        List<Long> values = new ArrayList<>();
        for (Map<String, Object> row : database.rows("select " + column + " from " + table + " order by id")) {
            values.add(((Number) row.get(column)).longValue());
        }
        return values;
    }

    private static long key(Model model) {
        return ((Number) model.getKey()).longValue();
    }

    private static List<Long> authorsOf(List<Post> posts) {
        List<Long> keys = new ArrayList<>();
        for (Post post : posts) {
            keys.add(((Number) post.getAttribute("author_id")).longValue());
        }
        return keys;
    }

    @Test
    void theInTurnCursorContinuesThroughAHasChildCreate() {
        List<Author> pool = savedAuthors(2);
        long first = key(pool.get(0));
        long second = key(pool.get(1));

        posts().recycle(pool.get(0), pool.get(1)).has(comments(), "comments").createMany(2);

        assertEquals(Arrays.asList(first, first), column("posts", "author_id"));
        assertEquals(Arrays.asList(second, second), column("comments", "author_id"));
    }

    @Test
    void theCursorRestartsOnTheNextTerminalCall() {
        List<Author> pool = savedAuthors(2);
        Factory<Post> factory = posts().recycle(pool.get(0), pool.get(1)).has(comments(), "comments");

        factory.create();
        factory.create();

        assertEquals(Arrays.asList(key(pool.get(0)), key(pool.get(0))), column("posts", "author_id"));
        assertEquals(Arrays.asList(key(pool.get(1)), key(pool.get(1))), column("comments", "author_id"));
    }

    @Test
    void aSeededRandomRepeatsPerCall() {
        List<Author> pool = savedAuthors(4);
        Factory<Post> factory = posts().recycle(pool).recycleRandom(SEED).has(comments(), "comments");

        factory.createMany(PICKS);
        factory.createMany(PICKS);

        List<Long> postPicks = column("posts", "author_id");
        List<Long> commentPicks = column("comments", "author_id");
        assertEquals(postPicks.subList(0, PICKS), postPicks.subList(PICKS, 2 * PICKS));
        assertEquals(commentPicks.subList(0, PICKS), commentPicks.subList(PICKS, 2 * PICKS));
    }

    @Test
    void sequencesRestartOnEachCall() {
        Factory<Post> factory = barePosts().sequence(map("title", "a"), map("title", "b"));

        factory.createMany(3);
        factory.createMany(3);

        assertEquals(6L, count("posts"));
        assertEquals(Arrays.asList("a", "b", "a", "a", "b", "a"), titles());
    }

    private List<String> titles() {
        List<String> titles = new ArrayList<>();
        for (Map<String, Object> row : database.rows("select title from posts order by id")) {
            titles.add((String) row.get("title"));
        }
        return titles;
    }

    @Test
    void aHasChildBatchGetsItsOwnForParentUnlessARecycledModelMatches() {
        Factory<Comment> children = bareComments().forParent(authors(), "author").count(2);

        barePosts().has(children, "comments").createMany(2);

        List<Long> commentAuthors = column("comments", "author_id");
        assertEquals(2L, count("authors"));
        assertEquals(commentAuthors.get(0), commentAuthors.get(1));
        assertEquals(commentAuthors.get(2), commentAuthors.get(3));
        assertNotEquals(commentAuthors.get(0), commentAuthors.get(2));
    }

    @Test
    void aRecycledModelFeedsTheForParentOfAHasChildBatch() {
        Author existing = authors().create();
        Factory<Comment> children = bareComments().forParent(authors(), "author").count(2);

        barePosts().has(children, "comments").recycle(existing).createMany(2);

        assertEquals(1L, count("authors"));
        assertEquals(Arrays.asList(key(existing), key(existing), key(existing), key(existing)),
                column("comments", "author_id"));
    }

    @Test
    void theFactoryHoldsNoRunStateTwoCallsInAnyOrderGiveTheSameResult() {
        List<Author> pool = savedAuthors(2);
        Factory<Post> turn = posts().recycle(pool);
        Factory<Post> first = turn.recycleMode(RecycleMode.FIRST);

        List<Long> turnOne = authorsOf(turn.createMany(3));
        List<Long> firstOne = authorsOf(first.createMany(3));
        List<Long> turnTwo = authorsOf(turn.createMany(3));

        assertEquals(turnOne, turnTwo);
        assertEquals(Arrays.asList(key(pool.get(0)), key(pool.get(1)), key(pool.get(0))), turnOne);
        assertEquals(Arrays.asList(key(pool.get(0)), key(pool.get(0)), key(pool.get(0))), firstOne);
    }

    private String observe(Factory<Post> factory) {
        long[] before = {count("authors"), count("comments"), count("tags"), count("post_tag")};
        Post post = factory.create();
        long[] after = {count("authors"), count("comments"), count("tags"), count("post_tag")};
        long[] delta = new long[before.length];
        for (int i = 0; i < before.length; i++) {
            delta[i] = after[i] - before[i];
        }
        String seen = post.getAttribute("title") + " " + Arrays.toString(delta) + " " + log;
        log.clear();
        return seen;
    }

    @Test
    void afterEachFluentCallTwoCallsShareNoState() {
        Author existing = authors().create();
        Map<String, Function<Factory<Post>, Factory<Post>>> fluent = new LinkedHashMap<>();
        fluent.put("has", f -> f.has(comments(), "comments"));
        fluent.put("hasAttached", f -> f.hasAttached(tags(), "tags"));
        fluent.put("forParent", f -> f.forParent(authors(), "author"));
        fluent.put("state", f -> f.state((soFar, parent) -> map("title", "changed")));
        fluent.put("afterMaking", f -> f.afterMaking((post, parent) -> log.add("making")));
        fluent.put("afterCreating", f -> f.afterCreating((post, parent) -> log.add("creating")));
        fluent.put("recycle", f -> f.recycle(existing));
        fluent.put("recycleMode", f -> f.recycle(existing).recycleMode(RecycleMode.FIRST));
        fluent.put("recycleRandom", f -> f.recycle(existing).recycleRandom());
        fluent.put("recycleRandomSeed", f -> f.recycle(existing).recycleRandom(SEED));
        fluent.put("withoutParents", f -> f.withoutParents());

        for (Map.Entry<String, Function<Factory<Post>, Factory<Post>>> entry : fluent.entrySet()) {
            Factory<Post> original = posts();
            String originalBefore = observe(original);

            Factory<Post> changed = entry.getValue().apply(original);
            String firstCall = observe(changed);
            String secondCall = observe(changed);

            assertEquals(originalBefore, observe(original), entry.getKey() + " changed the original");
            assertEquals(firstCall, secondCall, entry.getKey() + " keeps state between calls");
        }
    }

    @Test
    void anUnsavedRecycledModelPickedForAFactoryValueThrowsIllegalState() {
        Author unsaved = authors().state(map("name", SECRET)).make();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> posts().recycle(unsaved).create());

        assertTrue(thrown.getMessage().contains(Author.class.getName()), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(SECRET), thrown.getMessage());
        assertEquals(0L, count("posts"));
        assertEquals(0L, count("authors"));
    }

    @Test
    void anUnsavedRecycledModelPickedForForParentThrowsIllegalState() {
        Author unsaved = authors().state(map("name", SECRET)).make();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> barePosts().forParent(authors(), "author").recycle(unsaved).create());

        assertTrue(thrown.getMessage().contains(Author.class.getName()), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(SECRET), thrown.getMessage());
        assertEquals(0L, count("posts"));
        assertEquals(0L, count("authors"));
    }
}
