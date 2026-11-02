package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/** The models of the relation tests: users have posts and a profile, posts have comments. */
final class RelationModels {

    private RelationModels() {
    }

    /**
     * Initialises every model class, so each {@link ModelQuery} entry is registered before a
     * relation names the class.
     */
    static void register() {
        List<ModelQuery<?>> entries = Arrays.asList(User.QUERY, Post.QUERY, Comment.QUERY, Profile.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /** A user: has many posts, has one profile. */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(User.class).add("posts", User::posts).add("profile", User::profile);
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class);
        }

        public HasOne<Profile> profile() {
            return hasOne(Profile.class);
        }
    }

    /** A post: belongs to its author, has many comments. */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> fillable() {
            return Arrays.asList("title", "user_id");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("author", Post::author).add("comments", Post::comments);
        }

        public BelongsTo<User> author() {
            return belongsTo(User.class, "user_id");
        }

        public HasMany<Comment> comments() {
            return hasMany(Comment.class);
        }
    }

    /** A comment: belongs to its post. */
    static final class Comment extends Model {
        static final ModelQuery<Comment> QUERY = ModelQuery.of(Comment.class, Comment::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Comment.class).add("post", Comment::post);
        }

        public BelongsTo<Post> post() {
            return belongsTo(Post.class, "post_id");
        }
    }

    /** A profile: belongs to its user. */
    static final class Profile extends Model {
        static final ModelQuery<Profile> QUERY = ModelQuery.of(Profile.class, Profile::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Profile.class).add("user", Profile::user);
        }

        public BelongsTo<User> user() {
            return belongsTo(User.class, "user_id");
        }
    }
}
