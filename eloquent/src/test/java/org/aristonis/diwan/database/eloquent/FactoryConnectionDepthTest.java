package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nested factories write to the connection of the outer factory unless they have their own, and a
 * cycle of factories stops at a depth limit instead of overflowing the stack. The tests run on the
 * stub connection, which answers every insert with the id 1.
 */
class FactoryConnectionDepthTest {

    private static final String SECRET = "a-value-that-must-not-leak";

    /** The depth the factories allow; the cycle tests count rows against it. */
    private static final int DEPTH_LIMIT = 32;

    /**
     * The ping rows written before the limit stops a cycle that runs through a has link: a ping is
     * saved at the depths 0, 2, 4 and so on up to 32, and each ping has one level of pongs between
     * it and the next ping, so the count is half the limit plus the first row.
     */
    private static final int PINGS_BEFORE_THE_LIMIT = DEPTH_LIMIT / 2 + 1;

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

    /** A post with an author and tags. */
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
            return RelationMap.of(Post.class).add("author", Post::author).add("tags", Post::tags);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }

        public BelongsToMany<Tag> tags() {
            return belongsToMany(Tag.class, "post_tag", "post_id", "tag_id");
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

    /** One side of a cycle: it holds a pong key and has pongs. */
    static final class Ping extends Model {
        static final ModelQuery<Ping> QUERY = ModelQuery.of(Ping.class, Ping::new);

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
            return RelationMap.of(Ping.class).add("pongs", Ping::pongs);
        }

        public HasMany<Pong> pongs() {
            return hasMany(Pong.class, "ping_id");
        }
    }

    /** The other side of a cycle: it holds a ping key. */
    static final class Pong extends Model {
        static final ModelQuery<Pong> QUERY = ModelQuery.of(Pong.class, Pong::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private FakeDb outer;
    private FakeDb other;
    private FakeDb third;

    @BeforeEach
    void open() {
        outer = new FakeDb();
        other = new FakeDb();
        third = new FakeDb();
    }

    private static long inserts(FakeDb fake) {
        return fake.calls.stream().filter(call -> call.method().equals("insert")).count();
    }

    private static Factory<Author> authors(FakeDb fake) {
        return Factory.of(Author.QUERY.on(fake.db), () -> row("name", "ann"));
    }

    private static Factory<Post> posts(FakeDb fake) {
        return Factory.of(Post.QUERY.on(fake.db), () -> row("title", "t"));
    }

    private static Factory<Tag> tags(FakeDb fake) {
        return Factory.of(Tag.QUERY.on(fake.db), () -> row("label", "l"));
    }

    private static Factory<Ping> pingsThroughValues(FakeDb fake) {
        return Factory.of(Ping.QUERY.on(fake.db), () -> row("pong_id", pongsThroughValues(fake)));
    }

    private static Factory<Pong> pongsThroughValues(FakeDb fake) {
        return Factory.of(Pong.QUERY.on(fake.db), () -> row("ping_id", pingsThroughValues(fake)));
    }

    private static Factory<Ping> pingsThroughHas(FakeDb fake) {
        return Factory.of(Ping.QUERY.on(fake.db), () -> row("name", SECRET)).has(pongsWithAPing(fake), "pongs");
    }

    private static Factory<Pong> pongsWithAPing(FakeDb fake) {
        return Factory.of(Pong.QUERY.on(fake.db), () -> row("other_ping_id", pingsThroughHas(fake)));
    }

    @Test
    void aNestedHasChildWithoutAConnectionUsesTheOuterConnection() {
        Factory<Author> factory = authors(other).has(posts(other), "posts").connection(outer.connection);

        factory.create();

        assertEquals(2L, inserts(outer));
        assertTrue(other.calls.isEmpty(), other.calls.toString());
    }

    @Test
    void aNestedFactoryWithItsOwnConnectionKeepsIt() {
        Factory<Post> ownConnection = posts(other).connection(third.connection);

        authors(other).has(ownConnection, "posts").connection(outer.connection).create();

        assertEquals(1L, inserts(outer));
        assertEquals(1L, inserts(third));
        assertTrue(other.calls.isEmpty(), other.calls.toString());
    }

    @Test
    void aForParentFactoryAndANestedValueInheritTheOuterConnection() {
        Factory<Post> post = Factory.of(Post.QUERY.on(other.db), () -> row("title", "t", "editor_id", authors(other)))
                .forParent(authors(other), "author");

        post.connection(outer.connection).create();

        assertEquals(3L, inserts(outer));
        assertTrue(other.calls.isEmpty(), other.calls.toString());
    }

    @Test
    void anExistingModelGivenToForParentKeepsItsOwnConnection() {
        Author existing = authors(other).create();
        other.calls.clear();

        posts(outer).forParent(existing, "author").create();

        assertEquals(1L, inserts(outer));
        assertTrue(other.calls.isEmpty(), other.calls.toString());
    }

    @Test
    void aHasAttachedExistingModelKeepsItsOwnConnection() {
        Tag existing = tags(other).create();
        other.calls.clear();

        posts(outer).hasAttached(Collections.singletonList(existing), row(), "tags").create();

        assertEquals(2L, inserts(outer));
        assertTrue(outer.sql().stream().anyMatch(sql -> sql.contains("post_tag")), outer.sql().toString());
        assertTrue(other.calls.isEmpty(), other.calls.toString());
    }

    @Test
    void twoFactoriesWhoseValuesCreateEachOtherThrowAtTheDepthLimit() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> pingsThroughValues(outer).create());

        String message = thrown.getMessage();
        assertTrue(message.contains(Ping.class.getName()) || message.contains(Pong.class.getName()), message);
        assertTrue(message.contains(String.valueOf(DEPTH_LIMIT)), message);
        assertFalse(message.contains(SECRET), message);
        assertEquals(0L, inserts(outer));
    }

    @Test
    void aCycleThroughHasWritesOneRowPerSavedLevelBeforeTheException() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> pingsThroughHas(outer).create());

        String message = thrown.getMessage();
        assertTrue(message.contains(Ping.class.getName()) || message.contains(Pong.class.getName()), message);
        assertTrue(message.contains(String.valueOf(DEPTH_LIMIT)), message);
        assertFalse(message.contains(SECRET), message);
        assertEquals(PINGS_BEFORE_THE_LIMIT, inserts(outer));
    }

    @Test
    void rowsAlreadyWrittenStay() {
        assertThrows(IllegalStateException.class, () -> pingsThroughHas(outer).create());

        assertEquals(PINGS_BEFORE_THE_LIMIT, inserts(outer));
        assertTrue(outer.sql().stream().noneMatch(sql -> sql.startsWith("delete") || sql.contains("rollback")),
                outer.sql().toString());
    }
}
