package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphTo;
import org.aristonis.diwan.database.eloquent.json.JsonCodec;
import org.aristonis.diwan.database.eloquent.relations.Pivot;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Factory relations against a throwaway PostgreSQL; skipped when the server binaries are missing. */
class PostgresFactoryRelationsTest {

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

    /** A UUID-keyed account with notes. */
    static final class Account extends Plain {
        static final ModelQuery<Account> QUERY = ModelQuery.of(Account.class, Account::new);

        @Override
        protected String table() {
            return "accounts";
        }

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Account.class).add("notes", Account::notes);
        }

        public HasMany<Note> notes() {
            return hasMany(Note.class, "account_id", "id");
        }
    }

    /** A note under an account. */
    static final class Note extends Plain {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        @Override
        protected String table() {
            return "notes";
        }
    }

    /** An author. */
    static final class Author extends Plain {
        static final ModelQuery<Author> QUERY = ModelQuery.of(Author.class, Author::new);

        @Override
        protected String table() {
            return "authors";
        }
    }

    /** A tag. */
    static final class Tag extends Plain {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected String table() {
            return "tags";
        }
    }

    /** A pivot whose meta column is a JSON cast over a jsonb column. */
    static final class MetaPivot extends Pivot {
        static final ModelQuery<MetaPivot> QUERY = ModelQuery.of(MetaPivot.class, MetaPivot::new);

        @Override
        protected String table() {
            return "post_tag";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("meta", Casts.json());
        }
    }

    /** A post with tags through the meta pivot. */
    static final class Post extends Plain {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("tags", Post::tags).add("author", Post::author);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id", "id");
        }

        public BelongsToMany<Tag> tags() {
            return belongsToMany(Tag.class, "post_tag", "post_id", "tag_id").using(MetaPivot.class)
                    .withPivot("meta");
        }
    }

    /** A comment on an author. */
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

    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void open() {
        connection = PostgresServer.newDatabase();
        db = PostgresQuery.of(connection);
        MetaPivot.QUERY.getModelClass();
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("pgr_author", Author.class);
        MorphMap.morphMap(aliases);
        connection.statement("create table accounts (id varchar(36) primary key, name varchar(255))");
        connection.statement("create table notes (id bigserial primary key, account_id varchar(36), "
                + "body varchar(255))");
        connection.statement("create table authors (id bigserial primary key, name varchar(255))");
        connection.statement("create table posts (id bigserial primary key, author_id bigint, "
                + "title varchar(255))");
        connection.statement("create table tags (id bigserial primary key, name varchar(255))");
        connection.statement("create table post_tag (post_id bigint, tag_id bigint, meta jsonb)");
        connection.statement("create table comments (id bigserial primary key, commentable_type varchar(255), "
                + "commentable_id bigint, body varchar(255))");
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private Factory<Account> accounts() {
        return Factory.of(Account.QUERY.on(db), () -> map("name", "Acme"));
    }

    private Factory<Note> notes() {
        return Factory.of(Note.QUERY.on(db), () -> map("body", "b"));
    }

    private Factory<Author> authors() {
        return Factory.of(Author.QUERY.on(db), () -> map("name", "Ann"));
    }

    private Factory<Post> posts() {
        return Factory.of(Post.QUERY.on(db), () -> map("title", "t"));
    }

    private Factory<Tag> tags() {
        return Factory.of(Tag.QUERY.on(db), () -> map("name", "x"));
    }

    private Factory<Comment> comments() {
        return Factory.of(Comment.QUERY.on(db), () -> map("body", "c"));
    }

    private long count(String table) {
        return ((Number) connection.select("select count(*) as v from " + table).get(0).get("v")).longValue();
    }

    private List<String> column(String table, String column) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : connection.select("select " + column + " as v from " + table + " order by 1")) {
            values.add(String.valueOf(row.get("v")));
        }
        return values;
    }

    @Test
    void hasManyOnAUuidKeyedParentWritesTheForeignKey() {
        Account account = accounts().has(notes().count(2), "notes").create();

        String key = String.valueOf(account.getKey());
        assertEquals(36, key.length());
        assertEquals(Arrays.asList(key, key), column("notes", "account_id"));
    }

    @Test
    void hasAttachedWithAJsonbPivotValueReadsBackEqual() {
        Map<String, Object> meta = map("k", "v", "j", "w");
        // A pivot attached through a using class takes its columns raw, so the cast value is encoded here.
        Post post = posts().hasAttached(tags(), map("meta", JsonCodec.standard().encode(meta)), "tags").create();

        Post reloaded = Post.QUERY.on(db).findOrFail(post.getKey());

        Pivot pivot = (Pivot) reloaded.tags().get().get(0).getRelation("pivot");
        assertEquals(meta, pivot.getAttribute("meta"));
    }

    @Test
    void hasAttachedWithAFactoryAttaches() {
        Post post = posts().hasAttached(tags().count(2), "tags").create();

        assertEquals(2L, count("tags"));
        assertEquals(Arrays.asList(String.valueOf(post.getKey()), String.valueOf(post.getKey())),
                column("post_tag", "post_id"));
    }

    @Test
    void forParentOnAMorphToWritesKeyAndType() {
        comments().forParent(authors(), "commentable").create();

        assertEquals(Collections.singletonList("pgr_author"), column("comments", "commentable_type"));
        assertEquals(Collections.singletonList("1"), column("comments", "commentable_id"));
    }

    @Test
    void recycleInTurnFeedsTheNestedValues() {
        List<Author> pool = authors().createMany(2);
        Factory<Post> withAuthor = Factory.of(Post.QUERY.on(db), () -> map("title", "t", "author_id", authors()));

        withAuthor.recycle(pool).count(3).createMany();

        assertEquals(2L, count("authors"));
        assertEquals(Arrays.asList("1", "1", "2"), column("posts", "author_id"));
    }

    @Test
    void insertWithForParentCreatesTheParentFirst() {
        posts().count(2).forParent(authors(), "author").insert();

        assertEquals(1L, count("authors"));
        assertEquals(Arrays.asList("1", "1"), column("posts", "author_id"));
    }
}
