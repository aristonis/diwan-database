package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphTo;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Factory forParent: the owner key, and the type for a morph-to, written from a created or given parent. */
class FactoryForTest {

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

    /** An author, with a unique code besides the key. */
    static final class Author extends Plain {
        static final ModelQuery<Author> QUERY = ModelQuery.of(Author.class, Author::new);

        @Override
        protected String table() {
            return "authors";
        }
    }

    /** An organisation whose class has no morph alias. */
    static final class Org extends Plain {
        static final ModelQuery<Org> QUERY = ModelQuery.of(Org.class, Org::new);

        @Override
        protected String table() {
            return "orgs";
        }
    }

    /** A post that belongs to an author by key and to a reviewer by code. */
    static final class Post extends Plain {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("author", Post::author).add("reviewer", Post::reviewer);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id", "id");
        }

        public BelongsTo<Author> reviewer() {
            return belongsTo(Author.class, "reviewer_code", "code");
        }
    }

    /** A comment on an author or an organisation. */
    static final class Comment extends Plain {
        static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected String table() {
            return "comments";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Comment.class).add("commentable", Comment::commentable);
        }

        public MorphTo<Model> commentable() {
            return morphTo("commentable");
        }
    }

    private SqliteModelDb database;
    private boolean wasRequired;

    @BeforeEach
    void open() throws SQLException {
        wasRequired = MorphMap.requiresMorphMap();
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("for_author", Author.class);
        MorphMap.morphMap(aliases);
        database = SqliteModelDb.open();
        database.sql("create table authors (id integer primary key autoincrement, name text, code text)");
        database.sql("create table orgs (id integer primary key autoincrement, name text)");
        database.sql("create table posts (id integer primary key autoincrement, author_id integer,"
                + " reviewer_code text, title text)");
        database.sql("create table comments (id integer primary key autoincrement, commentable_type text,"
                + " commentable_id integer, body text)");
    }

    @AfterEach
    void close() throws SQLException {
        MorphMap.requireMorphMap(wasRequired);
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

    private Factory<Org> orgs() {
        return Factory.of(Org.QUERY.on(database.db), () -> map("name", "Acme"));
    }

    private Factory<Post> posts() {
        return Factory.of(Post.QUERY.on(database.db), () -> map("title", "t"));
    }

    private Factory<Comment> comments() {
        return Factory.of(Comment.QUERY.on(database.db), () -> map("body", "c"));
    }

    private long count(String table) {
        return ((Number) database.scalar("select count(*) from " + table)).longValue();
    }

    private List<String> column(String table, String column) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : database.rows("select " + column + " from " + table + " order by id")) {
            values.add(String.valueOf(row.get(column)));
        }
        return values;
    }

    @Test
    void forParentFactoryOnABelongsToWritesTheOwnerKey() {
        Post post = posts().forParent(authors(), "author").create();

        assertEquals(1L, count("authors"));
        assertEquals(Collections.singletonList("1"), column("posts", "author_id"));
        assertEquals(1L, ((Number) post.getAttribute("author_id")).longValue());
    }

    @Test
    void forParentModelOnABelongsToWritesTheOwnerKey() {
        authors().create();
        Author second = authors().create();

        posts().forParent(second, "author").create();

        assertEquals(2L, count("authors"));
        assertEquals(Collections.singletonList(String.valueOf(second.getKey())), column("posts", "author_id"));
    }

    @Test
    void forParentOnAMorphToWritesKeyAndType() {
        MorphMap.requireMorphMap(false);

        Org org = orgs().create();
        comments().forParent(org, "commentable").create();

        assertEquals(Collections.singletonList(Org.class.getName()), column("comments", "commentable_type"));
        assertEquals(Collections.singletonList(String.valueOf(org.getKey())), column("comments", "commentable_id"));
    }

    @Test
    void forParentOnAMorphToWritesTheAliasWhenMapped() {
        comments().forParent(authors(), "commentable").create();

        assertEquals(Collections.singletonList("for_author"), column("comments", "commentable_type"));
        assertEquals(Collections.singletonList("1"), column("comments", "commentable_id"));
    }

    @Test
    void anEmptyMorphToOwnerKeyNameFallsBackToThePrimaryKey() {
        authors().state(map("code", "A")).create();
        Author second = authors().state(map("code", "B")).create();

        comments().forParent(second, "commentable").create();

        assertEquals(Collections.singletonList("2"), column("comments", "commentable_id"));
    }

    @Test
    void aCustomOwnerKeyNameIsUsed() {
        posts().forParent(authors().state(map("code", "K1")), "reviewer").create();

        assertEquals(Collections.singletonList("K1"), column("posts", "reviewer_code"));
    }

    @Test
    void countThreeWithForParentFactoryWritesOneParentRow() {
        posts().count(3).forParent(authors(), "author").createMany();

        assertEquals(1L, count("authors"));
        assertEquals(Arrays.asList("1", "1", "1"), column("posts", "author_id"));
    }

    @Test
    void insertThreeWithForParentFactoryWritesOneParentRow() {
        posts().count(3).forParent(authors(), "author").insert();

        assertEquals(1L, count("authors"));
        assertEquals(Arrays.asList("1", "1", "1"), column("posts", "author_id"));
    }

    @Test
    void aStateOverridingTheKeyWins() {
        posts().forParent(authors(), "author").state(map("author_id", 77)).create();

        assertEquals(Collections.singletonList("77"), column("posts", "author_id"));
    }

    @Test
    void setOverridingTheKeyWins() {
        posts().forParent(authors(), "author").set("author_id", 88).create();

        assertEquals(Collections.singletonList("88"), column("posts", "author_id"));
    }

    @Test
    void twoCreateCallsOnOneFactoryMakeTwoParents() {
        Factory<Post> factory = posts().forParent(authors(), "author");

        factory.create();
        factory.create();

        assertEquals(2L, count("authors"));
        assertEquals(Arrays.asList("1", "2"), column("posts", "author_id"));
    }

    @Test
    void anUnsavedModelParentThrowsIllegalState() {
        Author unsaved = authors().make();

        assertThrows(IllegalStateException.class, () -> posts().forParent(unsaved, "author").create());
        assertEquals(0L, count("posts"));
    }

    @Test
    void theForParentStepIsTheFirstLayerWhateverTheCallOrder() {
        posts().state(map("author_id", 55)).forParent(authors(), "author").create();

        assertEquals(Collections.singletonList("55"), column("posts", "author_id"));
    }

    @Test
    void forParentChainedTwiceFillsBothRelations() {
        posts().forParent(authors(), "author").forParent(authors().state(map("code", "R")), "reviewer").create();

        assertEquals(2L, count("authors"));
        assertEquals(Collections.singletonList("1"), column("posts", "author_id"));
        assertEquals(Collections.singletonList("R"), column("posts", "reviewer_code"));
    }
}
