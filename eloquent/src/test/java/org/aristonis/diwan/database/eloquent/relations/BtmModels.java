package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/**
 * Model classes and relation map entries for belongsToMany tests.
 */
final class BtmModels {

    private BtmModels() {
    }

    /**
     * Initialises every model class, so each {@link ModelQuery} entry is registered
     * before a relation names the class.
     */
    static void register() {
        List<ModelQuery<?>> entries = Arrays.asList(
                User.QUERY, Role.QUERY, Post.QUERY, Tag.QUERY, BlogPost.QUERY,
                Country.QUERY, Language.QUERY, Permission.QUERY, Membership.QUERY
        );
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /**
     * A user: belongs to many roles, friends, and memberships.
     */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(User.class)
                    .add("roles", User::roles)
                    .add("friends", User::friends)
                    .add("memberships", User::memberships)
                    .add("rolesByDefault", User::rolesByDefault)
                    .add("rolesAs", User::rolesAs);
        }

        /**
         * Belongs to many roles with pivot columns and timestamps.
         */
        public BelongsToMany<Role> roles() {
            return belongsToMany(Role.class, "role_user", "user_id", "role_id")
                    .withPivot("status", "weight")
                    .withTimestamps();
        }

        /**
         * Belongs to many users as friends (self relation).
         */
        public BelongsToMany<User> friends() {
            return belongsToMany(User.class, "user_user", "user_id", "friend_id");
        }

        /**
         * Belongs to many roles using the Membership pivot class.
         */
        public BelongsToMany<Role> memberships() {
            return belongsToMany(Role.class, Membership.class, "user_id", "role_id");
        }

        /**
         * Belongs to many roles using the default table name.
         */
        public BelongsToMany<Role> rolesByDefault() {
            return belongsToMany(Role.class);
        }

        /**
         * Belongs to many roles with a custom accessor name.
         */
        public BelongsToMany<Role> rolesAs() {
            return belongsToMany(Role.class).as("membership");
        }
    }

    /**
     * A role: belongs to many permissions and has users through memberships.
     */
    static final class Role extends Model {
        static final ModelQuery<Role> QUERY = ModelQuery.of(Role.class, Role::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Role.class).add("permissions", Role::permissions);
        }

        public BelongsToMany<Permission> permissions() {
            return belongsToMany(Permission.class);
        }
    }

    /**
     * A post: belongs to many tags with default table post_tag.
     */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Post.class).add("tags", Post::tags);
        }

        public BelongsToMany<Tag> tags() {
            return belongsToMany(Tag.class);
        }
    }

    /**
     * A tag: simple model for belongsToMany tests.
     */
    static final class Tag extends Model {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /**
     * A blog post: belongs to many tags with default table blog_post_tag.
     */
    static final class BlogPost extends Model {
        static final ModelQuery<BlogPost> QUERY = ModelQuery.of(BlogPost.class, BlogPost::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(BlogPost.class).add("tags", BlogPost::tags);
        }

        public BelongsToMany<Tag> tags() {
            return belongsToMany(Tag.class);
        }
    }

    /**
     * A country: keyed by string code, belongs to many languages with custom keys.
     */
    static final class Country extends Model {
        static final ModelQuery<Country> QUERY = ModelQuery.of(Country.class, Country::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected String primaryKey() {
            return "code";
        }

        @Override
        protected String keyType() {
            return "string";
        }

        @Override
        protected boolean incrementing() {
            return false;
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Country.class).add("languages", Country::languages);
        }

        public BelongsToMany<Language> languages() {
            return belongsToMany(Language.class, "country_language", "country_code", "language_iso", "code", "iso");
        }
    }

    /**
     * A language: keyed by string iso.
     */
    static final class Language extends Model {
        static final ModelQuery<Language> QUERY = ModelQuery.of(Language.class, Language::new);

        @Override
        protected String table() {
            return "languages";
        }

        @Override
        protected String primaryKey() {
            return "iso";
        }

        @Override
        protected String keyType() {
            return "string";
        }

        @Override
        protected boolean incrementing() {
            return false;
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /**
     * A permission: belongs to many roles.
     */
    static final class Permission extends Model {
        static final ModelQuery<Permission> QUERY = ModelQuery.of(Permission.class, Permission::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Permission.class).add("roles", Permission::roles);
        }

        public BelongsToMany<Role> roles() {
            return belongsToMany(Role.class);
        }
    }

    /**
     * A membership pivot model with user and role relations.
     */
    static final class Membership extends Pivot {
        static final ModelQuery<Membership> QUERY = ModelQuery.of(Membership.class, Membership::new);

        public Membership() {
        }

        @Override
        protected String table() {
            return "role_user";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Membership.class)
                    .add("user", Membership::user)
                    .add("role", Membership::role);
        }

        public BelongsTo<User> user() {
            return belongsTo(User.class, "user_id");
        }

        public BelongsTo<Role> role() {
            return belongsTo(Role.class, "role_id");
        }
    }

    /**
     * A model with no ModelQuery entry, for testing unregistered behaviour.
     */
    static final class Unregistered extends Model {
        // No QUERY field - intentionally unregistered
    }

    /**
     * A pivot model with a ModelQuery entry that is only reachable by its static
     * initialiser, never referenced elsewhere, for testing unloaded behaviour.
     */
    static final class Unloaded extends Pivot {
        static final ModelQuery<Unloaded> QUERY = ModelQuery.of(Unloaded.class, Unloaded::new);

        public Unloaded() {
        }

        @Override
        protected String table() {
            return "unloaded_pivot";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }
}