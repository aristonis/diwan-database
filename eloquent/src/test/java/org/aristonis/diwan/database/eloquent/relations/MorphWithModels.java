package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/**
 * The models of the per-class morph option tests: a post has an author, likes and comments, a
 * video has a channel, and a comment belongs to either through a type and an id column.
 */
final class MorphWithModels {

    private MorphWithModels() {
    }

    /**
     * Creates the tables on the database and aliases {@code post} and {@code video}.
     *
     * @param db the database the tests run on
     */
    static void prepare(RelationDb db) {
        db.real.statement("create table mw_authors (id integer primary key, name varchar)");
        db.real.statement("create table mw_channels (id integer primary key, name varchar)");
        db.real.statement("create table mw_posts (id integer primary key, title varchar, published integer,"
                + " author_id integer)");
        db.real.statement("create table mw_videos (id integer primary key, title varchar, channel_id integer)");
        db.real.statement("create table mw_likes (id integer primary key, post_id integer)");
        db.real.statement("create table mw_comments (id integer primary key, commentable_type varchar,"
                + " commentable_id integer)");
        List<ModelQuery<?>> entries = Arrays.asList(Author.QUERY, Channel.QUERY, Post.QUERY, Video.QUERY, Like.QUERY,
                Comment.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
        Map<String, Class<? extends Model>> aliases = new HashMap<>();
        aliases.put("post", Post.class);
        aliases.put("video", Video.class);
        MorphMap.morphMap(aliases);
    }

    /** An author of posts. */
    static final class Author extends Model {
        static final ModelQuery<Author> QUERY = ModelQuery.of(Author.class, Author::new);

        @Override
        protected String table() {
            return "mw_authors";
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
            return "mw_channels";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A like on a post. */
    static final class Like extends Model {
        static final ModelQuery<Like> QUERY = ModelQuery.of(Like.class, Like::new);

        @Override
        protected String table() {
            return "mw_likes";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A post with an author, likes and comments. */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected String table() {
            return "mw_posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("author", Post::author).add("likes", Post::likes)
                    .add("comments", Post::comments);
        }

        public BelongsTo<Author> author() {
            return belongsTo(Author.class, "author_id");
        }

        public HasMany<Like> likes() {
            return hasMany(Like.class, "post_id");
        }

        public MorphMany<Comment> comments() {
            return morphMany(Comment.class, "commentable");
        }
    }

    /** A video with a channel and no author. */
    static final class Video extends Model {
        static final ModelQuery<Video> QUERY = ModelQuery.of(Video.class, Video::new);

        @Override
        protected String table() {
            return "mw_videos";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Video.class).add("channel", Video::channel);
        }

        public BelongsTo<Channel> channel() {
            return belongsTo(Channel.class, "channel_id");
        }
    }

    /** A comment on a post or a video. */
    static final class Comment extends Model {
        static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected String table() {
            return "mw_comments";
        }

        @Override
        protected boolean timestamps() {
            return false;
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
