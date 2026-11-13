package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The raw forms return keys of models, never create a parent, and run no statement. */
class FactoryRawRelationsTest {

    private static final String SECRET = "a-value-that-must-not-leak";

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

    /** A post that belongs to an author. */
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

    private FakeDb fake;
    private Author saved;

    @BeforeEach
    void open() {
        fake = new FakeDb();
        saved = authors().create();
        fake.calls.clear();
    }

    private Factory<Author> authors() {
        return Factory.of(Author.QUERY.on(fake.db), () -> row("name", SECRET));
    }

    private Factory<Post> posts(Object author) {
        return Factory.of(Post.QUERY.on(fake.db), () -> row("title", "t", "author_id", author));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    @Test
    void rawWithAModelValueReturnsItsKey() {
        Map<String, Object> raw = posts(saved).raw();

        assertEquals(number(saved.getKey()), number(raw.get("author_id")));
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void rawWithAForParentModelSetsTheKeyColumn() {
        Map<String, Object> raw = posts(null).forParent(saved, "author").raw();

        assertEquals(number(saved.getKey()), number(raw.get("author_id")));
        assertEquals("t", raw.get("title"));
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void rawWithAFactoryValueThrowsIllegalState() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> posts(authors()).raw());

        assertTrue(thrown.getMessage().contains(Post.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(Author.class.getName()), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(SECRET), thrown.getMessage());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void rawWithAForParentFactoryThrowsIllegalState() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> posts(null).forParent(authors(), "author").raw());

        assertTrue(thrown.getMessage().contains(Post.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(Author.class.getName()), thrown.getMessage());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void rawManyThrowsTheSameWay() {
        IllegalStateException nested = assertThrows(IllegalStateException.class, () -> posts(authors()).rawMany(2));
        IllegalStateException parent = assertThrows(IllegalStateException.class,
                () -> posts(null).forParent(authors(), "author").rawMany(2));

        assertTrue(nested.getMessage().contains(Author.class.getName()), nested.getMessage());
        assertTrue(parent.getMessage().contains(Author.class.getName()), parent.getMessage());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void rawWithAnUnsavedForParentModelThrowsIllegalState() {
        Author unsaved = Author.QUERY.on(fake.db).newModel();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> posts(null).forParent(unsaved, "author").raw());

        assertTrue(thrown.getMessage().contains(Author.class.getName()), thrown.getMessage());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void rawThrowsEvenWhenTheRecyclePoolHasTheModel() {
        Factory<Post> recycled = posts(authors()).recycle(saved);

        assertThrows(IllegalStateException.class, recycled::raw);
        assertThrows(IllegalStateException.class, () -> recycled.rawMany(2));
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }
}
