package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.MorphMany;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphTo;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The morph query methods on SQLite: {@code whereHasMorph}, {@code doesntHaveMorph},
 * {@code whereMorphedTo}, {@code whereNotMorphedTo} and per-class {@code withMorph}, run through
 * the morph map against posts and videos that both own comments.
 */
class SQLiteMorphQueryTest {

    private EventFixtures fx;

    @BeforeEach
    void open() throws SQLException {
        fx = EventFixtures.open();
        createTables(fx.real);
        mapAliases();
        seed(fx.real);
    }

    @AfterEach
    void close() throws SQLException {
        EventFixtures.forgetAll();
        fx.close();
    }

    @Test
    void whereHasMorphKeepsCommentsWhoseVideoOwnerHasTheTitle() {
        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).query()
                .whereHasMorph("commentable", Arrays.asList(Post.class, "video"), q -> q.where("title", "Clip"))
                .get();

        assertEquals(Collections.singletonList("clip note"), sortedValues(comments, "body"));
    }

    @Test
    void whereHasMorphKeepsCommentsWhosePostOwnerHasTheTitle() {
        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).query()
                .whereHasMorph("commentable", Arrays.asList(Post.class, "video"), q -> q.where("title", "Alpha"))
                .get();

        assertEquals(Arrays.asList("first", "second"), sortedValues(comments, "body"));
    }

    @Test
    void whereHasMorphKeepsCommentsWhenEitherTypeMatchesTheConstraint() {
        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).query()
                .whereHasMorph("commentable", Arrays.asList(Post.class, "video"),
                        q -> q.where("title", "Alpha").orWhere("title", "Clip"))
                .get();

        assertEquals(Arrays.asList("clip note", "first", "second"), sortedValues(comments, "body"));
    }

    @Test
    void doesntHaveMorphWithAnExplicitListKeepsStrayAndNullTypeComments() {
        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).query()
                .doesntHaveMorph("commentable", Arrays.asList(Post.class, "video")).get();

        // The stray comment points at video 3, which does not exist, so it has no owner.
        assertEquals(Arrays.asList("orphan", "stray"), sortedValues(comments, "body"));
    }

    @Test
    void whereMorphedToAModelKeepsOnlyThatOwnersComments() {
        Post post = Post.QUERY.on(fx.db).findOrFail(1);

        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).query()
                .whereMorphedTo("commentable", post).get();

        assertEquals(Arrays.asList("first", "second"), sortedValues(comments, "body"));
    }

    @Test
    void whereMorphedToACollectionKeepsEachOwnerOfEachType() {
        Post post = Post.QUERY.on(fx.db).findOrFail(1);
        Video video = Video.QUERY.on(fx.db).findOrFail(1);

        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).query()
                .whereMorphedTo("commentable", Arrays.asList(post, video)).get();

        assertEquals(Arrays.asList("clip note", "first", "second"), sortedValues(comments, "body"));
    }

    @Test
    void whereNotMorphedToAModelKeepsOtherOwnersAndNullTypes() {
        Post post = Post.QUERY.on(fx.db).findOrFail(1);

        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).query()
                .whereNotMorphedTo("commentable", post).get();

        assertEquals(Arrays.asList("clip note", "orphan", "stray"), sortedValues(comments, "body"));
    }

    @Test
    void withMorphLoadsAuthorOnPostOwnersAndChannelOnVideoOwnersOnly() {
        fx.log.clear();

        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).query()
                .withMorph("commentable", o -> o.morphWith(Post.class, "author").morphWith(Video.class, "channel"))
                .get();

        List<String> reads = fx.log.reads();
        assertEquals(1, countContaining(reads, "\"morph_authors\""), reads.toString());
        assertEquals(1, countContaining(reads, "\"morph_channels\""), reads.toString());
        Map<String, Model> owners = ownersByBody(comments);
        assertEquals("Ann", owners.get("first").related("author").getAttribute("name"));
        assertFalse(owners.get("first").relationLoaded("channel"));
        assertEquals("Main", owners.get("clip note").related("channel").getAttribute("name"));
        assertFalse(owners.get("clip note").relationLoaded("author"));
        assertNull(owners.get("stray"));
        assertNull(owners.get("orphan"));
    }

    private void createTables(Connection real) {
        SQLiteSchema.of(real).create("morph_authors", t -> {
            t.id();
            t.string("name");
        });
        SQLiteSchema.of(real).create("morph_channels", t -> {
            t.id();
            t.string("name");
        });
        SQLiteSchema.of(real).create("morph_posts", t -> {
            t.id();
            t.string("title");
            t.bigInteger("author_id").nullable();
        });
        SQLiteSchema.of(real).create("morph_videos", t -> {
            t.id();
            t.string("title");
            t.bigInteger("channel_id").nullable();
        });
        SQLiteSchema.of(real).create("morph_comments", t -> {
            t.id();
            t.string("body");
            t.nullableMorphs("commentable");
        });
    }

    private static void mapAliases() {
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("post", Post.class);
        aliases.put("video", Video.class);
        MorphMap.morphMap(aliases);
    }

    /**
     * Inserts the rows without ids, so each table numbers them in insert order. The post with id 3
     * has no comment of its own; the video comment with commentable id 3 must not count for it.
     * The orphan comment has no owner type or id at all.
     */
    private static void seed(Connection real) {
        real.statement("insert into morph_authors (name) values ('Ann')");
        real.statement("insert into morph_authors (name) values ('Bob')");
        real.statement("insert into morph_channels (name) values ('Main')");
        real.statement("insert into morph_posts (title, author_id) values ('Alpha', 1)");
        real.statement("insert into morph_posts (title, author_id) values ('Beta', 2)");
        real.statement("insert into morph_posts (title, author_id) values ('Gamma', null)");
        real.statement("insert into morph_videos (title, channel_id) values ('Clip', 1)");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('first', 'post', 1)");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('second', 'post', 1)");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('clip note', 'video', 1)");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('stray', 'video', 3)");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('orphan', null, null)");
    }

    private static Map<String, Model> ownersByBody(ModelCollection<Comment> comments) {
        Map<String, Model> owners = new LinkedHashMap<>();
        for (Comment comment : comments) {
            owners.put((String) comment.getAttribute("body"), comment.related("commentable"));
        }
        return owners;
    }

    private static List<String> sortedValues(Iterable<? extends Model> models, String key) {
        List<String> values = new ArrayList<>();
        for (Model model : models) {
            values.add(String.valueOf(model.getAttribute(key)));
        }
        Collections.sort(values);
        return values;
    }

    private static long countContaining(List<String> sql, String fragment) {
        return sql.stream().filter(text -> text.contains(fragment)).count();
    }

    /** A post: owns comments and has an author. */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "morph_posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("comments", Post::comments).add("author", Post::author);
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }
    }

    /** A video: owns comments and has a channel, with ids that overlap the posts'. */
    static final class Video extends Model {
        static final ModelQuery<Video> QUERY = ModelQuery.of(Video.class, Video::new);

        @Override
        protected String table() {
            return "morph_videos";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Video.class).add("comments", Video::comments).add("channel", Video::channel);
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }

        public BelongsTo<Channel> channel() {
            return belongsTo(Channel.class, "channel_id");
        }
    }

    /** A comment: belongs to a post or a video, or to neither. */
    static final class Comment extends Model {
        static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected String table() {
            return "morph_comments";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> fillable() {
            return Arrays.asList("body");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Comment.class).add("commentable", Comment::commentable);
        }

        public MorphTo<Model> commentable() {
            return morphTo("commentable");
        }
    }

    /** An author of posts. */
    static final class Author extends Model {
        static final ModelQuery<Author> QUERY = ModelQuery.of(Author.class, Author::new);

        @Override
        protected String table() {
            return "morph_authors";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** The channel a video belongs to. */
    static final class Channel extends Model {
        static final ModelQuery<Channel> QUERY = ModelQuery.of(Channel.class, Channel::new);

        @Override
        protected String table() {
            return "morph_channels";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }
}
