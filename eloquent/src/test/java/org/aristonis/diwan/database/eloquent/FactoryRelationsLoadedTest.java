package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.factory.Sequence;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loaded relations around factory creation, and has children built with a sequence and a parent-aware state. */
class FactoryRelationsLoadedTest {

    /** A model with no timestamps and no guarded attributes. */
    abstract static class Plain extends Model {
        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** An author with posts. */
    static final class Author extends Plain {
        static final ModelQuery<Author> QUERY = ModelQuery.of(Author.class, Author::new);

        @Override
        protected String table() {
            return "authors";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Author.class).add("posts", Author::posts);
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class, "author_id", "id");
        }
    }

    /** A post with an author and a note copied from it. */
    static final class Post extends Plain {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("author", Post::author);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id", "id");
        }
    }

    private SqliteModelDb database;

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        database.sql("create table authors (id integer primary key autoincrement, name text)");
        database.sql("create table posts (id integer primary key autoincrement, author_id integer, title text,"
                + " note text)");
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

    private Factory<Author> authors() {
        return Factory.of(Author.QUERY.on(database.db), () -> map("name", "Ann"));
    }

    private Factory<Post> posts() {
        return Factory.of(Post.QUERY.on(database.db), () -> map("title", "t"));
    }

    private List<String> postRows() {
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> row : database.rows("select authors.name as who, posts.title, posts.note"
                + " from posts join authors on authors.id = posts.author_id order by posts.id")) {
            rows.add(row.get("who") + "/" + row.get("title") + "/" + row.get("note"));
        }
        return rows;
    }

    @Test
    void afterMakingLoadLeavesTheRelationLoadedAfterCreate() {
        Post post = posts().forParent(authors(), "author").afterMaking(p -> p.load("author")).create();

        assertTrue(post.relationLoaded("author"));
        assertEquals("Ann", ((Model) post.getRelation("author")).getAttribute("name"));
    }

    @Test
    void aHasSequenceOverThreeParentsGivesEachChildTheRightIndexAndParentValue() {
        Factory<Post> children = posts().count(2).sequence(Sequence.byIndex(index -> map("title", "i" + index)))
                .state((soFar, parent) -> map("note", parent.getAttribute("name")));

        authors().sequence(Sequence.byIndex(index -> map("name", "P" + index))).has(children, "posts")
                .createMany(3);

        assertEquals(Arrays.asList("P0/i0/P0", "P0/i1/P0", "P1/i0/P1", "P1/i1/P1", "P2/i0/P2", "P2/i1/P2"),
                postRows());
    }

    @Test
    void aSequenceOnTheChildContinuesWithinOneBatchAndRestartsForTheNextParent() {
        Factory<Post> children = posts().count(3).sequence(Sequence.byIndex(index -> map("title", "s" + index)));

        authors().has(children, "posts").createMany(2);

        assertEquals(Arrays.asList("Ann/s0/null", "Ann/s1/null", "Ann/s2/null", "Ann/s0/null", "Ann/s1/null",
                "Ann/s2/null"), postRows());
    }

    @Test
    void relationsLoadedBeforeCreateKeepTheirValueAfterHasChildrenAreCreated() {
        ModelCollection<Post> marker = new ModelCollection<>(new ArrayList<>(), Post.QUERY);

        Author author = authors().afterMaking(a -> a.setRelation("posts", marker)).has(posts().count(2), "posts")
                .create();

        assertSame(marker, author.getRelation("posts"));
        assertEquals(2L, ((Number) database.scalar("select count(*) from posts")).longValue());
    }
}
