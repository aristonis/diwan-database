package org.aristonis.diwan.database.eloquent.relations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/**
 * Timestamped model classes and keyed pivot for the belongsToMany write tests. The users and roles
 * tables carry {@code created_at} and {@code updated_at}, so touches have a column to set.
 */
final class BtmWriteModels {

    private BtmWriteModels() {
    }

    /**
     * Initialises every model class, so each {@link ModelQuery} entry is registered before a
     * relation or a touch names the class.
     */
    static void register() {
        List<ModelQuery<?>> entries = Arrays.asList(
                TsUser.QUERY, TsRole.QUERY,
                BrokenTouchRole.QUERY, TouchingUser.QUERY, KeyedMembership.QUERY
        );
        if (entries.contains(null)) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    /**
     * A timestamped user: belongs to many roles through the {@code role_user} pivot.
     */
    static final class TsUser extends Model {
        static final ModelQuery<TsUser> QUERY = ModelQuery.of(TsUser.class, TsUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TsUser.class).add("roles", TsUser::roles);
        }

        /**
         * Belongs to many roles with pivot columns and timestamps.
         *
         * @return the relation to the roles, never {@code null}
         */
        public BelongsToMany<TsRole> roles() {
            return belongsToMany(TsRole.class, "role_user", "user_id", "role_id")
                    .withPivot("status", "weight")
                    .withTimestamps();
        }
    }

    /**
     * A timestamped role: has users through the {@code role_user} pivot. Touches nothing.
     */
    static final class TsRole extends Model {
        static final ModelQuery<TsRole> QUERY = ModelQuery.of(TsRole.class, TsRole::new);

        @Override
        protected String table() {
            return "roles";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TsRole.class).add("users", TsRole::users);
        }

        /**
         * Belongs to many users with pivot columns and timestamps, the inverse of
         * {@link TsUser#roles()}.
         *
         * @return the relation to the users, never {@code null}
         */
        public BelongsToMany<TsUser> users() {
            return belongsToMany(TsUser.class, "role_user", "role_id", "user_id")
                    .withPivot("status", "weight")
                    .withTimestamps();
        }
    }

    /**
     * A role whose {@code touches()} names a relation that does not exist.
     */
    static final class BrokenTouchRole extends Model {
        static final ModelQuery<BrokenTouchRole> QUERY = ModelQuery.of(BrokenTouchRole.class, BrokenTouchRole::new);

        @Override
        protected String table() {
            return "roles";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(BrokenTouchRole.class).add("users", BrokenTouchRole::users);
        }

        @Override
        protected Set<String> touches() {
            return Collections.singleton("ghosts");
        }

        /**
         * Belongs to many users with pivot columns and timestamps.
         *
         * @return the relation to the users, never {@code null}
         */
        public BelongsToMany<TsUser> users() {
            return belongsToMany(TsUser.class, "role_user", "role_id", "user_id")
                    .withPivot("status", "weight")
                    .withTimestamps();
        }
    }

    /**
     * A user that touches its {@code roles} relation on save.
     */
    static final class TouchingUser extends Model {
        static final ModelQuery<TouchingUser> QUERY = ModelQuery.of(TouchingUser.class, TouchingUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TouchingUser.class).add("roles", TouchingUser::roles);
        }

        @Override
        protected Set<String> touches() {
            return Collections.singleton("roles");
        }

        /**
         * Belongs to many roles with pivot columns and timestamps.
         *
         * @return the relation to the roles, never {@code null}
         */
        public BelongsToMany<TsRole> roles() {
            return belongsToMany(TsRole.class, "role_user", "user_id", "role_id")
                    .withPivot("status", "weight")
                    .withTimestamps();
        }
    }

    /**
     * A membership pivot on {@code role_user_keyed}, with an auto-increment {@code id} key.
     */
    static final class KeyedMembership extends Pivot {
        static final ModelQuery<KeyedMembership> QUERY = ModelQuery.of(KeyedMembership.class, KeyedMembership::new);

        public KeyedMembership() {
        }

        @Override
        protected String table() {
            return "role_user_keyed";
        }

        @Override
        protected boolean incrementing() {
            return true;
        }
    }
}
