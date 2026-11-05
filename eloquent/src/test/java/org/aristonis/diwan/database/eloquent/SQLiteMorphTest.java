package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.relations.MorphMany;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphTo;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Morph relations on SQLite: posts and videos both own comments through {@code commentable_type} and
 * {@code commentable_id}, and a comment finds its owner through the morph map.
 */
class SQLiteMorphTest {

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
    void postAndVideoCommentsSeeOnlyTheirOwnRowsWhenBothHaveIdOne() {
        Post post = Post.QUERY.on(fx.db).findOrFail(1);
        Video video = Video.QUERY.on(fx.db).findOrFail(1);

        assertEquals(Arrays.asList("first", "second"), sortedValues(post.comments().getResults(), "body"));
        assertEquals(Collections.singletonList("clip note"), sortedValues(video.comments().getResults(), "body"));
    }

    @Test
    void postCommentsCreateStoresThePostTypeAndTheId() {
        Post post = Post.QUERY.on(fx.db).findOrFail(1);

        Comment created = post.comments().create(Map.of("body", "fresh"));

        Comment stored = Comment.QUERY.on(fx.db).findOrFail(created.getKey());
        assertEquals("post", stored.getAttribute("commentable_type"));
        assertEquals(1L, ((Number) stored.getAttribute("commentable_id")).longValue());
        assertEquals("fresh", stored.getAttribute("body"));
    }

    @Test
    void videoCommentsCreateStoresTheVideoTypeAndTheId() {
        Video video = Video.QUERY.on(fx.db).findOrFail(1);

        Comment created = video.comments().create(Map.of("body", "clip reply"));

        Comment stored = Comment.QUERY.on(fx.db).findOrFail(created.getKey());
        assertEquals("video", stored.getAttribute("commentable_type"));
        assertEquals(1L, ((Number) stored.getAttribute("commentable_id")).longValue());
    }

    @Test
    void commentableLazyLoadsThePostOrTheVideoItPointsAt() {
        Model onPost = commentNamed("first").commentable().getResults();
        Model onVideo = commentNamed("clip note").commentable().getResults();

        assertInstanceOf(Post.class, onPost);
        assertEquals("Alpha", onPost.getAttribute("title"));
        assertInstanceOf(Video.class, onVideo);
        assertEquals("Clip", onVideo.getAttribute("title"));
    }

    @Test
    void eagerCommentableLoadsEachTypeWithOneQueryAndLeavesAMissingOwnerNull() {
        fx.log.clear();

        ModelCollection<Comment> comments = Comment.QUERY.on(fx.db).with("commentable").get();

        List<String> reads = fx.log.reads();
        assertEquals(3, reads.size(), reads.toString());
        assertEquals(1, countContaining(reads, "\"morph_posts\""), reads.toString());
        assertEquals(1, countContaining(reads, "\"morph_videos\""), reads.toString());
        Map<String, Model> owners = ownersByBody(comments);
        assertInstanceOf(Post.class, owners.get("first"));
        assertEquals("Alpha", owners.get("first").getAttribute("title"));
        assertInstanceOf(Video.class, owners.get("clip note"));
        assertEquals("Clip", owners.get("clip note").getAttribute("title"));
        assertTrue(owners.containsKey("stray"));
        assertNull(owners.get("stray"));
    }

    @Test
    void whereHasCommentsKeepsOnlyPostsThatHaveAPostComment() {
        ModelCollection<Post> posts = Post.QUERY.on(fx.db).query().whereHas("comments", q -> { }).get();

        assertEquals(Collections.singletonList("Alpha"), sortedValues(posts, "title"));
    }

    @Test
    void whereHasConstraintMatchesOnlyPostCommentsWithTheBody() {
        ModelCollection<Post> posts = Post.QUERY.on(fx.db).query()
                .whereHas("comments", q -> q.where("body", "first")).get();

        assertEquals(Collections.singletonList("Alpha"), sortedValues(posts, "title"));
    }

    @Test
    void whereHasConstraintIgnoresAVideoCommentWithTheSameBody() {
        ModelCollection<Post> posts = Post.QUERY.on(fx.db).query()
                .whereHas("comments", q -> q.where("body", "clip note")).get();

        assertTrue(posts.isEmpty());
    }

    private void createTables(Connection real) {
        SQLiteSchema.of(real).create("morph_posts", t -> {
            t.id();
            t.string("title");
        });
        SQLiteSchema.of(real).create("morph_videos", t -> {
            t.id();
            t.string("title");
        });
        SQLiteSchema.of(real).create("morph_comments", t -> {
            t.id();
            t.string("body");
            t.morphs("commentable");
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
     */
    private static void seed(Connection real) {
        real.statement("insert into morph_posts (title) values ('Alpha')");
        real.statement("insert into morph_posts (title) values ('Beta')");
        real.statement("insert into morph_posts (title) values ('Gamma')");
        real.statement("insert into morph_videos (title) values ('Clip')");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('first', 'post', 1)");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('second', 'post', 1)");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('clip note', 'video', 1)");
        real.statement("insert into morph_comments (body, commentable_type, commentable_id)"
                + " values ('stray', 'video', 3)");
    }

    private Comment commentNamed(String body) {
        return Comment.QUERY.on(fx.db).query().where("body", body).get().get(0);
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

    /** A post: owns comments. */
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
            return RelationMap.of(Post.class).add("comments", Post::comments);
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }
    }

    /** A video: owns comments, with ids that overlap the posts'. */
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
            return RelationMap.of(Video.class).add("comments", Video::comments);
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }
    }

    /** A comment: belongs to a post or a video. */
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
}
