package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphToMany;
import org.aristonis.diwan.database.eloquent.relations.Pivot;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.pgsql.PostgresSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Morph many-to-many on a throwaway PostgreSQL: posts and videos share tags through {@code taggables}.
 * Skipped when the server binaries are missing, and failing when {@code DIWAN_PG_REQUIRED} is set.
 */
class PostgresMorphToManyTest {

    private static final String TYPE = "\"taggables\".\"taggable_type\" = ?";

    private EventFixtures fx;

    @BeforeEach
    void open() {
        fx = EventFixtures.openPostgres();
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
    void postAndVideoTagsSeeOnlyTheirOwnRowsWhenBothHaveIdOne() {
        Post post = Post.QUERY.on(fx.db).findOrFail(1);
        Video video = Video.QUERY.on(fx.db).findOrFail(1);

        assertEquals(Arrays.asList("go", "php"), sortedValues(video.tags().get(), "name"));
        assertEquals(Arrays.asList("java", "php"), sortedValues(post.tags().get(), "name"));
    }

    @Test
    void lazyQueryFiltersByTheTypeColumn() {
        Post post = Post.QUERY.on(fx.db).findOrFail(1);
        fx.log.clear();

        post.tags().get();

        List<String> reads = fx.log.reads();
        assertEquals(1, reads.size(), reads.toString());
        assertTrue(reads.get(0).contains(TYPE), reads.get(0));
    }

    @Test
    void eagerLoadMatchesEachPostToItsOwnTags() {
        fx.log.clear();

        ModelCollection<Post> posts = Post.QUERY.on(fx.db).with("tags").get();

        assertEquals(2, fx.log.reads().size(), fx.log.reads().toString());
        assertEquals(Arrays.asList("java", "php"), sortedValues(posts.get(0).<Tag>relatedMany("tags"), "name"));
        assertEquals(Collections.singletonList("php"), sortedValues(posts.get(1).<Tag>relatedMany("tags"), "name"));
        assertTrue(posts.get(2).<Tag>relatedMany("tags").isEmpty());
    }

    @Test
    void inverseSidesSeeOnlyTheirOwnType() {
        Tag php = Tag.QUERY.on(fx.db).query().where("name", "php").get().get(0);

        assertEquals(Arrays.asList("post 1", "post 2"), sortedValues(php.posts().get(), "title"));
        assertEquals(Collections.singletonList("video 1"), sortedValues(php.videos().get(), "title"));
    }

    @Test
    void existenceIgnoresRowsOfAnotherTypeWithTheSameId() {
        ModelCollection<Post> has = Post.QUERY.on(fx.db).query().has("tags").get();
        ModelCollection<Post> none = Post.QUERY.on(fx.db).query().doesntHave("tags").get();
        ModelCollection<Post> named = Post.QUERY.on(fx.db).query().whereHas("tags", q -> q.where("name", "go")).get();

        assertEquals(Arrays.asList("post 1", "post 2"), sortedValues(has, "title"));
        assertEquals(Collections.singletonList("post 3"), sortedValues(none, "title"));
        assertTrue(named.isEmpty());
    }

    @Test
    void withCountCountsOnlyTheParentsType() {
        ModelCollection<Post> posts = Post.QUERY.on(fx.db).query().withCount("tags").get();

        assertEquals(2, ((Number) posts.get(0).getAttribute("tags_count")).intValue());
        assertEquals(1, ((Number) posts.get(1).getAttribute("tags_count")).intValue());
        assertEquals(0, ((Number) posts.get(2).getAttribute("tags_count")).intValue());
    }

    @Test
    void pivotDeleteLeavesTheVideoRowWithTheSameIds() {
        Post post = Post.QUERY.on(fx.db).findOrFail(1);
        Tag php = post.tags().get().stream().filter(t -> "php".equals(t.getAttribute("name"))).findFirst().get();

        boolean deleted = ((Pivot) php.getRelation("pivot")).delete();

        assertTrue(deleted);
        assertEquals(1, count(fx.real, "taggable_type = 'video' and taggable_id = 1 and tag_id = 1"));
        assertEquals(0, count(fx.real, "taggable_type = 'post' and taggable_id = 1 and tag_id = 1"));
    }

    private static void createTables(Connection real) {
        PostgresSchema.of(real).create("mm_posts", t -> {
            t.id();
            t.string("title");
        });
        PostgresSchema.of(real).create("mm_videos", t -> {
            t.id();
            t.string("title");
        });
        PostgresSchema.of(real).create("mm_tags", t -> {
            t.id();
            t.string("name");
        });
        PostgresSchema.of(real).create("taggables", t -> {
            t.integer("tag_id");
            t.morphs("taggable");
        });
    }

    private static int count(Connection real, String where) {
        return ((Number) real.scalar("select count(*) from taggables where " + where).orElseThrow()).intValue();
    }

    private static void mapAliases() {
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("post", Post.class);
        aliases.put("video", Video.class);
        MorphMap.morphMap(aliases);
    }

    /**
     * Seeds posts 1 to 3, video 1, tags php (1), java (2) and go (3). Post 1 has php and java, post
     * 2 has php, post 3 has none but a video link names its id. Video 1 has php and go.
     */
    private static void seed(Connection real) {
        real.statement("insert into mm_posts (title) values ('post 1')");
        real.statement("insert into mm_posts (title) values ('post 2')");
        real.statement("insert into mm_posts (title) values ('post 3')");
        real.statement("insert into mm_videos (title) values ('video 1')");
        real.statement("insert into mm_tags (name) values ('php')");
        real.statement("insert into mm_tags (name) values ('java')");
        real.statement("insert into mm_tags (name) values ('go')");
        link(real, 1, 1, "post");
        link(real, 2, 1, "post");
        link(real, 1, 2, "post");
        link(real, 1, 1, "video");
        link(real, 3, 1, "video");
        link(real, 2, 3, "video");
    }

    private static void link(Connection real, int tag, int owner, String type) {
        real.statement("insert into taggables (tag_id, taggable_id, taggable_type) values (" + tag + ", " + owner
                + ", '" + type + "')");
    }

    private static List<String> sortedValues(Iterable<? extends Model> models, String key) {
        List<String> values = new ArrayList<>();
        for (Model model : models) {
            values.add(String.valueOf(model.getAttribute(key)));
        }
        Collections.sort(values);
        return values;
    }

    /** A post: owns tags. */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "mm_posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("tags", Post::tags);
        }

        public MorphToMany<Tag> tags() {
            return morphToMany(Tag.class, "taggable");
        }
    }

    /** A video: owns tags, with ids that overlap the posts'. */
    static final class Video extends Model {
        static final ModelQuery<Video> QUERY = ModelQuery.of(Video.class, Video::new);

        @Override
        protected String table() {
            return "mm_videos";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Video.class).add("tags", Video::tags);
        }

        public MorphToMany<Tag> tags() {
            return morphToMany(Tag.class, "taggable");
        }
    }

    /** A tag: linked to posts and videos. */
    static final class Tag extends Model {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected String table() {
            return "mm_tags";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Tag.class).add("posts", Tag::posts).add("videos", Tag::videos);
        }

        public MorphToMany<Post> posts() {
            return morphedByMany(Post.class, "taggable");
        }

        public MorphToMany<Video> videos() {
            return morphedByMany(Video.class, "taggable");
        }
    }
}
