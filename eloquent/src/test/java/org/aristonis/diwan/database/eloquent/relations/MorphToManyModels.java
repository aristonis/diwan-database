package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

/**
 * The models of the morph many-to-many tests: posts and videos share tags through the
 * {@code taggables} table, a tag relates to other tags, and two pivot classes (one morph pivot, one
 * plain) test {@code using}. Posts, videos and tags all have ids 1 and 2, so a missing type filter
 * shows at once.
 */
final class MorphToManyModels {

    /** The fixed pivot rows: tag id, owner id, owner type, weight. */
    private static final Object[][] LINKS = {
        {1, 1, "post", 5},
        {2, 1, "post", 7},
        {1, 2, "post", 1},
        {1, 1, "video", 3},
        {3, 1, "video", 9},
        {2, 2, "video", 4},
        {3, 3, "video", 8},
        {2, 1, "tag", 0},
    };

    private MorphToManyModels() {
    }

    /** Initialises every model class, so each {@link ModelQuery} entry is registered. */
    static void register() {
        List<ModelQuery<?>> entries = Arrays.asList(Post.QUERY, Video.QUERY, Tag.QUERY, TouchingPost.QUERY,
                TaggablePivot.QUERY, PlainPivot.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /** Maps the aliases {@code post}, {@code video} and {@code tag} to their classes. */
    static void mapAliases() {
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("post", Post.class);
        aliases.put("video", Video.class);
        aliases.put("tag", Tag.class);
        MorphMap.morphMap(aliases);
    }

    /**
     * Creates the tables and seeds them with integer owner ids on the pivot.
     *
     * @param db the database to fill
     */
    static void createAndSeed(RelationDb db) {
        createAndSeed(db, false);
    }

    /**
     * Creates the tables and seeds them.
     *
     * <p>Posts 1 to 3, videos 1 and 2, tags 1 (php), 2 (java) and 3 (go). Post 1 has tags 1 and 2,
     * post 2 has tag 1, post 3 has none. Video 1 has tags 1 and 3, video 2 has tag 2, and a video
     * link names the missing video 3. Tag 1 is related to tag 2.
     *
     * @param db the database to fill
     * @param textOwnerIds whether the pivot stores the owner id as text, so keys read back as strings
     */
    static void createAndSeed(RelationDb db, boolean textOwnerIds) {
        Schema schema = SQLiteSchema.of(db.real);
        schema.create("mm_posts", t -> {
            t.id();
            t.string("title");
            t.nullableTimestamps();
        });
        schema.create("mm_videos", t -> {
            t.id();
            t.string("title");
        });
        schema.create("mm_tags", t -> {
            t.id();
            t.string("name");
            t.nullableTimestamps();
        });
        schema.create("taggables", t -> {
            t.integer("tag_id");
            if (textOwnerIds) {
                t.string("taggable_id");
            } else {
                t.integer("taggable_id");
            }
            t.string("taggable_type");
            t.integer("weight").nullable();
        });
        for (int id = 1; id <= 3; id++) {
            db.seed("mm_posts", Map.of("id", id, "title", "post " + id));
        }
        db.seed("mm_videos", Map.of("id", 1, "title", "video 1"));
        db.seed("mm_videos", Map.of("id", 2, "title", "video 2"));
        db.seed("mm_tags", Map.of("id", 1, "name", "php"));
        db.seed("mm_tags", Map.of("id", 2, "name", "java"));
        db.seed("mm_tags", Map.of("id", 3, "name", "go"));
        for (Object[] link : LINKS) {
            Object owner = textOwnerIds ? String.valueOf(link[1]) : link[1];
            db.seed("taggables", Map.of("tag_id", link[0], "taggable_id", owner, "taggable_type", link[2],
                    "weight", link[3]));
        }
    }

    /**
     * Creates the {@code mm_labels} table that the explicit-name relations point at: the tag in
     * {@code tag_ref}, the owner in {@code owner_ref}, the type in {@code labelable_type}.
     *
     * @param db the database to add the table to
     */
    static void createLabels(RelationDb db) {
        SQLiteSchema.of(db.real).create("mm_labels", t -> {
            t.integer("tag_ref");
            t.integer("owner_ref");
            t.string("labelable_type");
        });
        db.seed("mm_labels", Map.of("tag_ref", 2, "owner_ref", 1, "labelable_type", "post"));
        db.seed("mm_labels", Map.of("tag_ref", 3, "owner_ref", 1, "labelable_type", "video"));
    }

    /**
     * Returns the keys of the models as sorted longs, so a test does not depend on row order.
     *
     * @param models the models
     * @return the sorted keys
     */
    static List<Long> sortedKeys(Iterable<? extends Model> models) {
        List<Long> keys = new java.util.ArrayList<>();
        for (Model model : models) {
            keys.add(((Number) model.getKey()).longValue());
        }
        Collections.sort(keys);
        return keys;
    }

    /**
     * Returns the ids as a list of longs, for comparing with {@link #sortedKeys}.
     *
     * @param ids the ids
     * @return the ids in the given order
     */
    static List<Long> ids(long... ids) {
        List<Long> list = new java.util.ArrayList<>();
        for (long id : ids) {
            list.add(id);
        }
        return list;
    }

    /**
     * Counts the {@code taggables} rows that match a condition.
     *
     * @param db the database
     * @param where the SQL condition
     * @return the row count
     */
    static int linkCount(RelationDb db, String where) {
        return ((Number) db.real.scalar("select count(*) from taggables where " + where).orElseThrow())
                .intValue();
    }

    /** A post: owns tags through the default names. */
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

        public MorphToMany<Tag> tagsNamed(String name) {
            return morphToMany(Tag.class, name);
        }

        public MorphToMany<Tag> tagsInTable(String table) {
            return morphToMany(Tag.class, "taggable", table);
        }

        public MorphToMany<Tag> labels() {
            return morphToMany(Tag.class, "labelable", "mm_labels", "owner_ref", "tag_ref");
        }

        public MorphToMany<Tag> fullyNamedLabels() {
            return morphToMany(Tag.class, "labelable", "mm_labels", "owner_ref", "tag_ref", "id", "id",
                    "fullyNamedLabels");
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

    /** A post that touches its tags when it is saved. */
    static final class TouchingPost extends Model {
        static final ModelQuery<TouchingPost> QUERY = ModelQuery.of(TouchingPost.class, TouchingPost::new);

        // Stands in for a post on the pivot, so its links are the ones seeded for posts.
        @Override
        public String getMorphClass() {
            return "post";
        }

        @Override
        protected String table() {
            return "mm_posts";
        }

        @Override
        protected Set<String> touches() {
            return Collections.singleton("tags");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TouchingPost.class).add("tags", TouchingPost::tags);
        }

        public MorphToMany<Tag> tags() {
            return morphToMany(Tag.class, "taggable");
        }
    }

    /** A tag: the inverse side for posts and videos, and related to other tags. */
    static final class Tag extends Model {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected String table() {
            return "mm_tags";
        }

        @Override
        protected List<String> fillable() {
            return Collections.singletonList("name");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Tag.class)
                    .add("posts", Tag::posts)
                    .add("videos", Tag::videos)
                    .add("related", Tag::related);
        }

        public MorphToMany<Post> posts() {
            return morphedByMany(Post.class, "taggable");
        }

        public MorphToMany<Video> videos() {
            return morphedByMany(Video.class, "taggable");
        }

        public MorphToMany<Post> labeledPosts() {
            return morphedByMany(Post.class, "labelable", "mm_labels", "tag_ref", "owner_ref");
        }

        /** Tags related to this tag: the tag-typed pivot rows. */
        public MorphToMany<Tag> related() {
            return morphToMany(Tag.class, "taggable");
        }
    }

    /** A morph pivot class on {@code taggables}. */
    static final class TaggablePivot extends MorphPivot {
        static final ModelQuery<TaggablePivot> QUERY = ModelQuery.of(TaggablePivot.class, TaggablePivot::new);

        public TaggablePivot() {
        }

        @Override
        protected String table() {
            return "taggables";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A pivot class that is not a morph pivot, which a morph relation must refuse. */
    static final class PlainPivot extends Pivot {
        static final ModelQuery<PlainPivot> QUERY = ModelQuery.of(PlainPivot.class, PlainPivot::new);

        public PlainPivot() {
        }

        @Override
        protected String table() {
            return "taggables";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }
}
