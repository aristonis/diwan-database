package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.exception.InvalidRelationException;
import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.HasManyThrough;
import org.aristonis.diwan.database.eloquent.relations.MorphTo;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** A link to a relation of the wrong kind or name fails with a clear message before any row is written. */
class FactoryRelationErrorsTest {

    private static final String SECRET = "a-value-that-must-not-leak";

    /** An author with posts and comments through its posts. */
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
            return RelationMap.of(Author.class).add("posts", Author::posts).add("comments", Author::comments);
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class, "author_id");
        }

        public HasManyThrough<Comment> comments() {
            return hasManyThrough(Comment.class, Post.class);
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

    /** A comment on any model. */
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
            return RelationMap.of(Comment.class).add("commentable", Comment::commentable);
        }

        public MorphTo<Model> commentable() {
            return morphTo("commentable");
        }
    }

    /** A model whose relation map is built for another class. */
    static final class Broken extends Model {
        static final ModelQuery<Broken> QUERY = ModelQuery.of(Broken.class, Broken::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Author.class);
        }
    }

    private FakeDb fake;

    @BeforeEach
    void open() {
        fake = new FakeDb();
    }

    private Factory<Author> authors() {
        return Factory.of(Author.QUERY.on(fake.db), () -> row("name", SECRET));
    }

    private Factory<Post> posts() {
        return Factory.of(Post.QUERY.on(fake.db), () -> row("title", SECRET));
    }

    private Factory<Comment> comments() {
        return Factory.of(Comment.QUERY.on(fake.db), () -> row("body", SECRET));
    }

    private void assertMessage(IllegalArgumentException thrown, Class<?> model, String relation, Class<?> kind) {
        String message = thrown.getMessage();
        assertTrue(message.contains(model.getName()), message);
        assertTrue(message.contains(relation), message);
        assertTrue(message.contains(kind.getName()), message);
        assertFalse(message.contains(SECRET), message);
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void hasOnABelongsToThrowsIllegalArgument() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> posts().has(authors(), "author").create());

        assertMessage(thrown, Post.class, "author", BelongsTo.class);
    }

    @Test
    void hasOnAMorphToThrowsIllegalArgument() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> comments().has(posts(), "commentable").create());

        assertMessage(thrown, Comment.class, "commentable", MorphTo.class);
    }

    @Test
    void hasOnAThroughRelationThrowsIllegalArgument() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> authors().has(comments(), "comments").create());

        assertMessage(thrown, Author.class, "comments", HasManyThrough.class);
    }

    @Test
    void hasAttachedOnAHasManyThrowsIllegalArgument() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> authors().hasAttached(posts(), "posts").create());

        assertMessage(thrown, Author.class, "posts", HasMany.class);
    }

    @Test
    void forParentOnAHasManyThrowsIllegalArgument() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> authors().forParent(posts(), "posts").create());

        assertMessage(thrown, Author.class, "posts", HasMany.class);
    }

    @Test
    void anUnknownRelationNameThrowsRelationNotFound() {
        assertThrows(RelationNotFoundException.class, () -> posts().forParent(authors(), "nobody").create());
        assertThrows(RelationNotFoundException.class, () -> authors().has(posts(), "nobody").create());
        assertThrows(RelationNotFoundException.class, () -> authors().hasAttached(posts(), "nobody").create());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void anInvalidRelationsMapThrowsInvalidRelation() {
        Factory<Broken> broken = Factory.of(Broken.QUERY.on(fake.db), () -> row("name", SECRET));

        assertThrows(InvalidRelationException.class, () -> broken.forParent(authors(), "author").create());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void noMessageCarriesAnAttributeValue() {
        List<String> messages = new ArrayList<>();
        Consumer<Executable> collect = action ->
                messages.add(assertThrows(RuntimeException.class, action).getMessage());

        collect.accept(() -> posts().has(authors(), "author").create());
        collect.accept(() -> authors().hasAttached(posts(), "posts").create());
        collect.accept(() -> authors().forParent(posts(), "posts").create());
        collect.accept(() -> posts().forParent(authors(), "nobody").create());
        collect.accept(() -> posts().forParent(Author.QUERY.on(fake.db).newModel(), "author").create());

        assertEquals(5, messages.size());
        for (String message : messages) {
            assertFalse(message.contains(SECRET), message);
        }
    }

    @Test
    void hasLinksAreCheckedOnCreateOnlyAndNotOnMake() {
        Factory<Post> bad = posts().has(authors(), "author");

        assertDoesNotThrow(() -> {
            bad.make();
        });
        assertDoesNotThrow(() -> bad.insert());
        assertEquals(1, fake.calls.size());
        assertThrows(IllegalArgumentException.class, bad::create);
        assertEquals(1, fake.calls.size());
    }

    @Test
    void forParentLinksAreCheckedOnEveryTerminal() {
        Factory<Author> bad = authors().forParent(posts(), "posts");

        assertThrows(IllegalArgumentException.class, bad::make);
        assertThrows(IllegalArgumentException.class, bad::create);
        assertThrows(IllegalArgumentException.class, bad::createQuietly);
        assertThrows(IllegalArgumentException.class, bad::insert);
        assertThrows(IllegalArgumentException.class, bad::raw);
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void aBadLinkInANestedFactoryIsCheckedBeforeTheFirstRow() {
        Factory<Author> outer = authors().has(posts().has(authors(), "author"), "posts");

        assertThrows(IllegalArgumentException.class, outer::create);
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void anUnsavedForParentModelThrowsIllegalState() {
        Author unsaved = Author.QUERY.on(fake.db).newModel();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> posts().forParent(unsaved, "author").create());

        assertTrue(thrown.getMessage().contains(Author.class.getName()), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(SECRET), thrown.getMessage());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }
}
