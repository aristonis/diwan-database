package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

/**
 * The models of the morph relation tests: posts and videos own comments and an image, and a
 * comment owns replies, which are comments again.
 */
final class MorphModels {

    private MorphModels() {
    }

    /** Initialises every model class, so each {@link ModelQuery} entry is registered. */
    static void register() {
        List<ModelQuery<?>> entries = Arrays.asList(Post.QUERY, Video.QUERY, Comment.QUERY, Image.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /**
     * Maps the aliases {@code post}, {@code video} and {@code comment} to their classes.
     */
    static void mapAliases() {
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>();
        aliases.put("post", Post.class);
        aliases.put("video", Video.class);
        aliases.put("comment", Comment.class);
        MorphMap.morphMap(aliases);
    }

    /**
     * Creates the morph tables on the database's unlogged connection.
     *
     * @param db the database to add the tables to
     */
    static void createTables(RelationDb db) {
        SQLiteSchema.of(db.real).create("morph_posts", t -> {
            t.id();
            t.string("title");
        });
        SQLiteSchema.of(db.real).create("morph_videos", t -> {
            t.id();
            t.string("title");
        });
        SQLiteSchema.of(db.real).create("morph_comments", t -> {
            t.id();
            t.string("body");
            t.string("commentable_type").nullable();
            t.integer("commentable_id").nullable();
        });
        SQLiteSchema.of(db.real).create("morph_images", t -> {
            t.id();
            t.string("url");
            t.string("imageable_type").nullable();
            t.integer("imageable_id").nullable();
        });
    }

    /** A post: owns comments and one image. */
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
            return RelationMap.of(Post.class).add("comments", Post::comments).add("image", Post::image);
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }

        public MorphOne<Image> image() {
            return morphOne(Image.class, "imageable");
        }
    }

    /** A video: owns comments and one image, with ids that overlap the posts'. */
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
            return RelationMap.of(Video.class).add("comments", Video::comments).add("image", Video::image);
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }

        public MorphOne<Image> image() {
            return morphOne(Image.class, "imageable");
        }
    }

    /** A comment: belongs to a post, a video or another comment, and owns replies. */
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
            return RelationMap.of(Comment.class).add("replies", Comment::replies);
        }

        public MorphMany<Comment> replies() {
            return morphMany(Comment.class, "commentable");
        }
    }

    /** An image: belongs to a post or a video. */
    static final class Image extends Model {
        static final ModelQuery<Image> QUERY = ModelQuery.of(Image.class, Image::new);

        @Override
        protected String table() {
            return "morph_images";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> fillable() {
            return Arrays.asList("url");
        }
    }
}
