package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.factory.Sequence;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.HasOne;
import org.aristonis.diwan.database.eloquent.relations.MorphMany;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphOne;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Factory has: children created under a saved parent through has-many, has-one and morph relations. */
class FactoryHasTest {

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

    /** An author with posts, a profile, comments and an avatar. */
    static final class Author extends Plain {
        static final ModelQuery<Author> QUERY = ModelQuery.of(Author.class, Author::new);

        @Override
        protected String table() {
            return "authors";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Author.class).add("posts", Author::posts).add("profile", Author::profile)
                    .add("comments", Author::comments).add("avatar", Author::avatar);
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class, "author_id", "id");
        }

        public HasOne<Profile> profile() {
            return hasOne(Profile.class, "author_id", "id");
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }

        public MorphOne<Avatar> avatar() {
            return morphOne(Avatar.class, "imageable");
        }
    }

    /** A post with comments. */
    static final class Post extends Plain {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("comments", Post::comments);
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }
    }

    /** A profile, one per author. */
    static final class Profile extends Plain {
        static final ModelQuery<Profile> QUERY = ModelQuery.of(Profile.class, Profile::new);

        @Override
        protected String table() {
            return "profiles";
        }
    }

    /** A comment on any commentable. */
    static final class Comment extends Plain {
        static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected String table() {
            return "comments";
        }
    }

    /** An avatar, one per imageable. */
    static final class Avatar extends Plain {
        static final ModelQuery<Avatar> QUERY = ModelQuery.of(Avatar.class, Avatar::new);

        @Override
        protected String table() {
            return "avatars";
        }
    }

    /** An owner whose class has no morph alias. */
    static final class Loose extends Plain {
        static final ModelQuery<Loose> QUERY = ModelQuery.of(Loose.class, Loose::new);

        @Override
        protected String table() {
            return "looses";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Loose.class).add("comments", Loose::comments);
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }
    }

    private SqliteModelDb database;
    private boolean wasRequired;

    @BeforeEach
    void open() throws SQLException {
        wasRequired = MorphMap.requiresMorphMap();
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("has_author", Author.class);
        aliases.put("has_post", Post.class);
        MorphMap.morphMap(aliases);
        database = SqliteModelDb.open();
        database.sql("create table authors (id integer primary key autoincrement, name text)");
        database.sql("create table looses (id integer primary key autoincrement, name text)");
        database.sql("create table posts (id integer primary key autoincrement, author_id integer, title text)");
        database.sql("create table profiles (id integer primary key autoincrement, author_id integer, bio text)");
        database.sql("create table comments (id integer primary key autoincrement, commentable_type text,"
                + " commentable_id integer, body text)");
        database.sql("create table avatars (id integer primary key autoincrement, imageable_type text,"
                + " imageable_id integer, url text)");
    }

    @AfterEach
    void close() throws SQLException {
        MorphMap.requireMorphMap(wasRequired);
        Post.QUERY.events().forget();
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

    private Factory<Comment> comments() {
        return Factory.of(Comment.QUERY.on(database.db), () -> map("body", "c"));
    }

    private static long id(Model model) {
        return ((Number) model.getKey()).longValue();
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
    void hasManyCreatesThreeChildrenWithTheForeignKey() {
        Author author = authors().has(posts().count(3), "posts").create();

        String key = String.valueOf(id(author));
        assertEquals(Arrays.asList(key, key, key), column("posts", "author_id"));
    }

    @Test
    void hasOneCreatesOneChildWithTheForeignKey() {
        Author author = authors().has(Factory.of(Profile.QUERY.on(database.db), () -> map("bio", "b")), "profile")
                .create();

        assertEquals(Collections.singletonList(String.valueOf(id(author))), column("profiles", "author_id"));
    }

    @Test
    void morphManyWritesTheClassNameWhenTheClassHasNoAlias() {
        MorphMap.requireMorphMap(false);
        Loose owner = Factory.of(Loose.QUERY.on(database.db), () -> map("name", "L"))
                .has(comments().count(2), "comments").create();

        assertEquals(Arrays.asList(Loose.class.getName(), Loose.class.getName()),
                column("comments", "commentable_type"));
        assertEquals(Arrays.asList(String.valueOf(id(owner)), String.valueOf(id(owner))),
                column("comments", "commentable_id"));
    }

    @Test
    void morphManyWritesTheAliasWhenTheMapHasOne() {
        Author author = authors().has(comments().count(2), "comments").create();

        assertEquals(Arrays.asList("has_author", "has_author"), column("comments", "commentable_type"));
        assertEquals(Arrays.asList(String.valueOf(id(author)), String.valueOf(id(author))),
                column("comments", "commentable_id"));
    }

    @Test
    void morphOneWritesKeyAndType() {
        Author author = authors().has(Factory.of(Avatar.QUERY.on(database.db), () -> map("url", "u")), "avatar")
                .create();

        assertEquals(Collections.singletonList("has_author"), column("avatars", "imageable_type"));
        assertEquals(Collections.singletonList(String.valueOf(id(author))), column("avatars", "imageable_id"));
    }

    @Test
    void aChildStateAndAfterCreatingRunPerChild() {
        AtomicInteger created = new AtomicInteger();

        authors().has(posts().count(2).state(map("title", "S")).afterCreating(post -> created.incrementAndGet()),
                "posts").create();

        assertEquals(Arrays.asList("S", "S"), column("posts", "title"));
        assertEquals(2, created.get());
    }

    @Test
    void createQuietlyKeepsTheChildrenQuiet() {
        AtomicInteger fired = new AtomicInteger();
        Post.QUERY.events().saved(model -> fired.incrementAndGet());

        authors().has(posts().count(2), "posts").createQuietly();

        assertEquals(2L, count("posts"));
        assertEquals(0, fired.get());
    }

    @Test
    void hasOnMakeCreatesNoChild() {
        Author made = authors().has(posts().count(2), "posts").make();

        assertFalse(made.exists());
        assertEquals(0L, count("posts"));
        assertEquals(0L, count("authors"));
    }

    @Test
    void hasOnMakeManyCreatesNoChild() {
        List<Author> made = authors().has(posts(), "posts").makeMany(2);

        assertEquals(2, made.size());
        assertEquals(0L, count("posts"));
    }

    @Test
    void hasOnCreateManyCreatesChildrenPerParent() {
        authors().has(posts().count(2), "posts").createMany(2);

        assertEquals(2L, count("authors"));
        assertEquals(4L, count("posts"));
        assertEquals(2L, ((Number) database.scalar("select count(distinct author_id) from posts")).longValue());
    }

    @Test
    void theRelationColumnsWinOverTheChildsOwnStates() {
        Author author = authors().has(posts().state(map("author_id", 999L)), "posts").create();

        assertEquals(Collections.singletonList(String.valueOf(id(author))), column("posts", "author_id"));
    }

    @Test
    void aChildWithItsOwnNestedHasCreatesGrandchildren() {
        authors().has(posts().has(comments().count(2), "comments"), "posts").create();

        assertEquals(Arrays.asList("has_post", "has_post"), column("comments", "commentable_type"));
        assertEquals(Arrays.asList("1", "1"), column("comments", "commentable_id"));
    }

    @Test
    void aMorphMapRequiredViolationIsNotCaught() {
        MorphMap.requireMorphMap(true);
        Factory<Loose> loose = Factory.of(Loose.QUERY.on(database.db), () -> map("name", "L"))
                .has(comments(), "comments");

        assertThrows(ClassMorphViolationException.class, loose::create);
    }

    @Test
    void aBiFunctionStateReceivesTheParentForHasChildren() {
        authors().has(posts().state((soFar, parent) -> map("title", "of " + parent.getAttribute("name"))), "posts")
                .create();

        assertEquals(Collections.singletonList("of Ann"), column("posts", "title"));
    }

    @Test
    void aBiConsumerAfterMakingReceivesTheParent() {
        List<Object> parents = new ArrayList<>();

        Author author = authors().has(posts().afterMaking((post, parent) -> parents.add(parent.getKey())), "posts")
                .create();

        assertEquals(Collections.singletonList(author.getKey()), parents);
    }

    @Test
    void aBiConsumerAfterCreatingReceivesTheParent() {
        List<Object> parents = new ArrayList<>();

        Author author = authors().has(posts().afterCreating((post, parent) -> parents.add(parent.getKey())),
                "posts").create();

        assertEquals(Collections.singletonList(author.getKey()), parents);
    }

    @Test
    void theParentIsNullAtTheTopLevel() {
        List<Object> seen = new ArrayList<>();

        authors().state((soFar, parent) -> {
            seen.add(parent);
            return map();
        }).afterMaking((author, parent) -> seen.add(parent)).afterCreating((author, parent) -> seen.add(parent))
                .create();

        assertEquals(3, seen.size());
        for (Object parent : seen) {
            assertNull(parent);
        }
    }

    @Test
    void theOneArgumentOverloadsStillCompileAndRun() {
        List<String> order = new ArrayList<>();

        Author author = authors().state(soFar -> map("name", "Bo")).afterMaking(a -> order.add("making"))
                .afterCreating(a -> order.add("created")).create();

        assertEquals("Bo", author.getAttribute("name"));
        assertEquals(Arrays.asList("making", "created"), order);
    }

    @Test
    void aSequenceEntryStillGetsTheIndexOnly() {
        Sequence byIndex = Sequence.byIndex(index -> map("title", "n" + index));

        authors().has(posts().sequence(byIndex).count(3), "posts").create();

        assertEquals(Arrays.asList("n0", "n1", "n2"), column("posts", "title"));
    }
}
