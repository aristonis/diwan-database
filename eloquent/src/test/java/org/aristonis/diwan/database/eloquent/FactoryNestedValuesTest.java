package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Nested Factory and Model values, and how make and insert treat parents and children, on SQLite. */
class FactoryNestedValuesTest {

    private static final String SECRET = "a-value-that-must-not-leak";

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

    /** A comment of a post. */
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

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        database.sql("create table authors (id integer primary key autoincrement, name text)");
        database.sql("create table posts (id integer primary key autoincrement, author_id integer, title text)");
        database.sql("create table comments (id integer primary key autoincrement, post_id integer, body text)");
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

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private Factory<Author> authors() {
        return Factory.of(Author.QUERY.on(database.db), () -> map("name", "ann"));
    }

    private Factory<Post> posts(Object author) {
        return Factory.of(Post.QUERY.on(database.db), () -> map("title", "t", "author_id", author));
    }

    private Factory<Comment> comments() {
        return Factory.of(Comment.QUERY.on(database.db), () -> map("body", "b"));
    }

    private Factory<Tag> tags() {
        return Factory.of(Tag.QUERY.on(database.db), () -> map("label", "l"));
    }

    @Test
    void aFactoryValueCreatesTheParentAndStoresItsKey() {
        Post post = posts(authors()).create();

        assertEquals(1L, count("authors"));
        assertEquals(number(database.scalar("select id from authors")), number(post.getAttribute("author_id")));
        assertEquals(number(post.getAttribute("author_id")), number(database.scalar("select author_id from posts")));
    }

    @Test
    void aModelValueStoresItsKey() {
        Author author = authors().create();

        Post post = posts(author).create();

        assertEquals(number(author.getKey()), number(post.getAttribute("author_id")));
        assertEquals(1L, count("authors"));
    }

    @Test
    void aSupplierReturningAFactoryIsExpanded() {
        Supplier<Object> lazyAuthor = this::authors;

        Post post = posts(lazyAuthor).create();

        assertEquals(1L, count("authors"));
        assertEquals(number(database.scalar("select id from authors")), number(post.getAttribute("author_id")));
    }

    @Test
    void aFunctionReturningAModelIsExpanded() {
        Author existing = authors().create();
        Function<Map<String, Object>, Object> pick = attributes -> existing;

        Post post = posts(pick).create();

        assertEquals(number(existing.getKey()), number(post.getAttribute("author_id")));
        assertEquals(1L, count("authors"));
    }

    @Test
    void aFactoryValueOverriddenByAStateCreatesNoParentRow() {
        posts(authors()).state(map("author_id", 77L)).create();

        assertEquals(0L, count("authors"));
        assertEquals(77L, number(database.scalar("select author_id from posts")));
    }

    @Test
    void aFactoryValueOverriddenByForParentCreatesNoExtraRow() {
        Factory<Author> chosen = authors().state(map("name", "chosen"));

        Post post = posts(authors()).forParent(chosen, "author").create();

        assertEquals(1L, count("authors"));
        assertEquals("chosen", database.scalar("select name from authors"));
        assertEquals(number(database.scalar("select id from authors")), number(post.getAttribute("author_id")));
    }

    @Test
    void aClassCastExceptionFromAFunctionIsStillIllegalArgument() {
        Function<Object, Object> wrong = attributes -> (String) attributes;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> posts(wrong).state(map("title", SECRET)).create());

        assertTrue(thrown.getMessage().contains("author_id"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(SECRET), thrown.getMessage());
        assertInstanceOf(ClassCastException.class, thrown.getCause());
        assertEquals(0L, count("posts"));
    }

    @Test
    void aNullValueStaysNull() {
        Post post = posts(null).create();

        assertNull(post.getAttribute("author_id"));
        assertNull(database.scalar("select author_id from posts"));
        assertEquals(0L, count("authors"));
    }

    @Test
    void aFactoryValueIsCreatedBeforeTheModelRow() {
        List<String> seen = new ArrayList<>();

        posts(authors()).afterMaking(post -> seen.add(count("authors") + "/" + count("posts"))).create();

        assertEquals(List.of("1/0"), seen);
        assertEquals(1L, count("posts"));
    }

    @Test
    void makeWithAFactoryValueCreatesTheParentRowAndNoChild() {
        Post post = posts(authors()).has(comments(), "comments").make();

        assertFalse(post.exists());
        assertNotNullKey(post.getAttribute("author_id"));
        assertEquals(1L, count("authors"));
        assertEquals(0L, count("posts"));
        assertEquals(0L, count("comments"));
    }

    @Test
    void makeWithForParentCreatesTheParentRowAndNoHasChild() {
        Post post = posts(null).forParent(authors(), "author").has(comments(), "comments").make();

        assertFalse(post.exists());
        assertEquals(number(database.scalar("select id from authors")), number(post.getAttribute("author_id")));
        assertEquals(1L, count("authors"));
        assertEquals(0L, count("posts"));
        assertEquals(0L, count("comments"));
    }

    @Test
    void insertWithForParentCreatesTheParentFirst() {
        posts(null).forParent(authors(), "author").insert();

        assertEquals(1L, count("authors"));
        assertEquals(1L, count("posts"));
        assertEquals(number(database.scalar("select id from authors")),
                number(database.scalar("select author_id from posts")));
    }

    @Test
    void insertSkipsHasHasAttachedAndAfterCreating() {
        AtomicInteger hooked = new AtomicInteger();

        posts(null).has(comments(), "comments").hasAttached(tags(), "tags")
                .afterCreating(post -> hooked.incrementAndGet()).insert();

        assertEquals(1L, count("posts"));
        assertEquals(0L, count("comments"));
        assertEquals(0L, count("tags"));
        assertEquals(0L, count("post_tag"));
        assertEquals(0, hooked.get());
    }

    private static void assertNotNullKey(Object key) {
        assertTrue(key != null && number(key) > 0, String.valueOf(key));
    }
}
