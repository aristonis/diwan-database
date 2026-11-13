package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** withoutParents turns nested Factory values into null by all, by model class and by attribute name. */
class FactoryWithoutParentsTest {

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

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Comment.class).add("post", Comment::post);
        }

        public BelongsTo<Post> post() {
            return belongsTo(Post.class, "post_id");
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

    @Test
    void withoutParentsNullsEveryNestedFactoryValue() {
        Comment comment = comments().withoutParents().create();

        assertNull(comment.getAttribute("author_id"));
        assertNull(comment.getAttribute("post_id"));
        assertNull(database.scalar("select author_id from comments"));
        assertNull(database.scalar("select post_id from comments"));
    }

    @Test
    void withoutParentsByClassNullsOnlyTheListedClasses() {
        Comment comment = comments().withoutParents(Author.class).create();

        assertNull(comment.getAttribute("author_id"));
        assertNotNull(comment.getAttribute("post_id"));
        assertEquals(1L, count("posts"));
    }

    @Test
    void withoutParentsByAttributeNullsOnlyTheListedKeys() {
        Comment comment = comments().withoutParents("post_id").create();

        assertNull(comment.getAttribute("post_id"));
        assertNotNull(comment.getAttribute("author_id"));
        assertEquals(0L, count("posts"));
    }

    @Test
    void chainingBothKindsNullsBoth() {
        Comment comment = comments().withoutParents(Author.class).withoutParents("post_id").create();

        assertNull(comment.getAttribute("author_id"));
        assertNull(comment.getAttribute("post_id"));
        assertEquals(0L, count("authors"));
        assertEquals(0L, count("posts"));
    }

    @Test
    void forParentAndModelValuesAreUntouched() {
        Author saved = authors().create();
        Factory<Comment> factory = Factory.of(Comment.QUERY.on(database.db),
                () -> map("body", "b", "author_id", saved));

        Comment comment = factory.forParent(posts(), "post").withoutParents().create();

        assertEquals(((Number) saved.getKey()).longValue(), ((Number) comment.getAttribute("author_id")).longValue());
        assertNotNull(comment.getAttribute("post_id"));
        assertEquals(1L, count("posts"));
    }

    @Test
    void noParentRowIsCreatedForANulledValue() {
        comments().withoutParents().createMany(3);

        assertEquals(3L, count("comments"));
        assertEquals(0L, count("authors"));
        assertEquals(0L, count("posts"));
    }

    @Test
    void aNestedFactoryKeepsItsOwnWithoutParents() {
        Factory<Comment> outer = Factory.of(Comment.QUERY.on(database.db),
                () -> map("body", "b", "author_id", authors(), "post_id", posts().withoutParents()));

        outer.create();

        assertEquals(1L, count("authors"));
        assertEquals(1L, count("posts"));
        assertNull(database.scalar("select author_id from posts"));
    }

    @Test
    void withoutParentsReturnsANewFactory() {
        Factory<Comment> base = comments();

        Factory<Comment> without = base.withoutParents();
        without.create();
        base.create();

        assertNotSame(base, without);
        assertEquals(2L, count("authors"));
        assertEquals(1L, count("posts"));
        assertEquals(2L, count("comments"));
    }
}
