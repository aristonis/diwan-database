package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/**
 * The models of the morph-to tests: posts and videos both have an author and are commented on
 * through a type and an id column.
 */
final class MorphToModels {

    private MorphToModels() {
    }

    /**
     * Creates the morph tables on the database and aliases {@code post} and {@code video}.
     *
     * @param db the database the tests run on
     */
    static void prepare(RelationDb db) {
        db.real.statement("create table morph_authors (id integer primary key, name varchar)");
        db.real.statement("create table morph_posts (id integer primary key, title varchar, author_id integer)");
        db.real.statement("create table morph_videos (id integer primary key, title varchar, author_id integer)");
        db.real.statement("create table morph_comments (id integer primary key, body varchar,"
                + " commentable_type varchar, commentable_id integer, created_at varchar, updated_at varchar)");
        db.real.statement("create table morph_text_comments (id integer primary key,"
                + " commentable_type varchar, commentable_id varchar)");
        List<ModelQuery<?>> entries = Arrays.asList(Author.QUERY, Post.QUERY, Video.QUERY, Comment.QUERY,
                TextComment.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
        Map<String, Class<? extends Model>> aliases = new HashMap<>();
        aliases.put("post", Post.class);
        aliases.put("video", Video.class);
        MorphMap.morphMap(aliases);
    }

    /** An author of posts and videos. */
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

    /** A post with an author. */
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
            return RelationMap.of(Post.class).add("author", Post::author);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }
    }

    /** A video with an author. */
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
            return RelationMap.of(Video.class).add("author", Video::author);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }
    }

    /** A comment on a post or a video; keeps timestamps. */
    static final class Comment extends Model {
        static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected String table() {
            return "morph_comments";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Comment.class).add("commentable", Comment::commentable);
        }

        public MorphTo<Model> commentable() {
            return morphTo("commentable");
        }
    }

    /** A comment whose id column is text, so its ids never equal a numeric key by type. */
    static final class TextComment extends Model {
        static final ModelQuery<TextComment> QUERY = ModelQuery.of(TextComment.class, TextComment::new);

        @Override
        protected String table() {
            return "morph_text_comments";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TextComment.class).add("commentable", TextComment::commentable);
        }

        public MorphTo<Model> commentable() {
            return morphTo("commentable");
        }
    }
}
