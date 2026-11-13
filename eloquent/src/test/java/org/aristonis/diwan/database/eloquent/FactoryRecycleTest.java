package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.factory.RecycleMode;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The recycle pool: which model feeds a nested value, in which mode, and what it never touches. */
class FactoryRecycleTest {

    private static final int PICKS = 12;
    private static final long SEED = 42L;

    /** An author with posts. */
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

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Author.class).add("posts", Author::posts);
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class, "author_id");
        }
    }

    /** A post with an author. */
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
            return RelationMap.of(Post.class).add("author", Post::author);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }
    }

    /** A comment with an author and a post. */
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
    }

    private SqliteModelDb database;

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        database.sql("create table authors (id integer primary key autoincrement, name text)");
        database.sql("create table posts (id integer primary key autoincrement, author_id integer, title text)");
        database.sql("create table comments (id integer primary key autoincrement, post_id integer,"
                + " author_id integer, body text)");
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

    private Factory<Comment> comments() {
        return Factory.of(Comment.QUERY.on(database.db),
                () -> map("body", "b", "author_id", authors(), "post_id", posts()));
    }

    private List<Author> savedAuthors(int number) {
        List<Author> saved = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            saved.add(authors().create());
        }
        return saved;
    }

    private List<Long> postAuthors() {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : database.rows("select author_id from posts order by id")) {
            ids.add(((Number) row.get("author_id")).longValue());
        }
        return ids;
    }

    private static long key(Model model) {
        return ((Number) model.getKey()).longValue();
    }

    private static List<Long> keys(List<Author> models) {
        List<Long> keys = new ArrayList<>();
        for (Author model : models) {
            keys.add(key(model));
        }
        return keys;
    }

    @Test
    void recycleOfVarargsAndListFillsThePoolByExactClass() {
        List<Author> pool = savedAuthors(2);
        Post post = posts().create();
        long authorsBefore = count("authors");

        posts().recycle(pool.get(0)).create();
        posts().recycle(Collections.singletonList(pool.get(1))).create();
        posts().recycle(post).create();

        List<Long> used = postAuthors();
        assertEquals(key(pool.get(0)), used.get(1));
        assertEquals(key(pool.get(1)), used.get(2));
        assertEquals(authorsBefore + 1, count("authors"));
    }

    @Test
    void theRecycledModelFeedsANestedFactoryValueWithoutAnExtraRow() {
        Author author = authors().create();

        posts().recycle(author).createMany(3);

        assertEquals(1L, count("authors"));
        assertEquals(Arrays.asList(key(author), key(author), key(author)), postAuthors());
    }

    @Test
    void theRecycledModelFeedsForParentWithoutAnExtraRow() {
        Author author = authors().create();

        Factory.of(Post.QUERY.on(database.db), () -> map("title", "t"))
                .forParent(authors(), "author").recycle(author).createMany(2);

        assertEquals(1L, count("authors"));
        assertEquals(Arrays.asList(key(author), key(author)), postAuthors());
    }

    @Test
    void inTurnGivesTheModelsInOrderAndWraps() {
        List<Author> pool = savedAuthors(2);
        long first = key(pool.get(0));
        long second = key(pool.get(1));

        posts().recycle(pool.get(0), pool.get(1)).createMany(5);

        assertEquals(Arrays.asList(first, second, first, second, first), postAuthors());
    }

    @Test
    void inTurnRestartsOnEachTerminalCall() {
        List<Author> pool = savedAuthors(2);
        Factory<Post> factory = posts().recycle(pool.get(0), pool.get(1));

        factory.createMany(1);
        factory.createMany(1);

        assertEquals(Arrays.asList(key(pool.get(0)), key(pool.get(0))), postAuthors());
    }

    @Test
    void firstAlwaysGivesTheFirstModel() {
        List<Author> pool = savedAuthors(2);

        posts().recycle(pool.get(0), pool.get(1)).recycleMode(RecycleMode.FIRST).createMany(3);

        long first = key(pool.get(0));
        assertEquals(Arrays.asList(first, first, first), postAuthors());
    }

    @Test
    void aSeededRandomGivesTheSamePicksOnTwoCalls() {
        List<Author> pool = savedAuthors(4);
        Factory<Post> factory = posts().recycle(pool.toArray(new Model[0])).recycleRandom(SEED);

        factory.createMany(PICKS);
        factory.createMany(PICKS);

        List<Long> all = postAuthors();
        assertEquals(all.subList(0, PICKS), all.subList(PICKS, 2 * PICKS));
    }

    @Test
    void aSeededRandomGivesTheSamePicksOnTwoRuns() {
        List<Author> pool = savedAuthors(4);

        posts().recycle(pool).recycleRandom(SEED).createMany(PICKS);
        posts().recycle(pool).recycleRandom(SEED).createMany(PICKS);

        List<Long> all = postAuthors();
        assertEquals(all.subList(0, PICKS), all.subList(PICKS, 2 * PICKS));
        assertTrue(keys(pool).containsAll(all), all.toString());
    }

    @Test
    void anUnseededRandomPicksOnlyPoolMembers() {
        List<Author> pool = savedAuthors(2);

        posts().recycle(pool).recycleRandom().createMany(PICKS);

        assertTrue(keys(pool).containsAll(postAuthors()), postAuthors().toString());
        assertEquals(2L, count("authors"));
    }

    @Test
    void anEmptyRecycleCreatesTheParents() {
        posts().recycle().createMany(2);

        assertEquals(2L, count("authors"));
    }

    @Test
    void anEmptyPoolInRandomModeCreatesTheParents() {
        posts().recycleRandom().createMany(2);

        assertEquals(2L, count("authors"));
    }

    @Test
    void twoClassesInOnePool() {
        Author author = authors().create();
        Post post = posts().recycle(author).create();

        Comment comment = comments().recycle(author, post).create();

        assertEquals(1L, count("authors"));
        assertEquals(1L, count("posts"));
        assertEquals(key(author), ((Number) comment.getAttribute("author_id")).longValue());
        assertEquals(key(post), ((Number) comment.getAttribute("post_id")).longValue());
    }

    @Test
    void hasChildrenKeepTheirParentColumn() {
        Author recycled = authors().create();

        Author parent = authors().has(posts(), "posts").recycle(recycled).create();

        assertEquals(2L, count("authors"));
        assertEquals(Collections.singletonList(key(parent)), postAuthors());
        assertNotEquals(key(recycled), postAuthors().get(0));
    }

    @Test
    void theRecycleDoesNotLeakToALaterCall() {
        Author author = authors().create();
        Factory<Post> base = posts();

        base.recycle(author).create();
        base.create();

        assertEquals(2L, count("authors"));
        assertEquals(key(author), postAuthors().get(0));
        assertNotEquals(key(author), postAuthors().get(1));
    }

    @Test
    void recycleModeKeepsASetSeed() {
        List<Author> pool = savedAuthors(4);

        posts().recycle(pool).recycleRandom(SEED).recycleMode(RecycleMode.RANDOM).createMany(PICKS);
        posts().recycle(pool).recycleRandom(SEED).createMany(PICKS);

        List<Long> all = postAuthors();
        assertEquals(all.subList(0, PICKS), all.subList(PICKS, 2 * PICKS));
    }

    @Test
    void repeatedRecycleCallsMergeAndKeepOneInstance() {
        List<Author> pool = savedAuthors(2);
        long first = key(pool.get(0));
        long second = key(pool.get(1));

        posts().recycle(pool.get(0)).recycle(pool.get(1), pool.get(0)).createMany(4);

        assertEquals(Arrays.asList(first, second, first, second), postAuthors());
    }

    @Test
    void aNestedFactorysOwnRecycleIsIgnored() {
        Author author = authors().create();
        Factory<Post> outer = Factory.of(Post.QUERY.on(database.db),
                () -> map("title", "t", "author_id", authors().recycle(author)));

        Post post = outer.create();

        assertEquals(2L, count("authors"));
        assertNotEquals(key(author), ((Number) post.getAttribute("author_id")).longValue());
    }
}
