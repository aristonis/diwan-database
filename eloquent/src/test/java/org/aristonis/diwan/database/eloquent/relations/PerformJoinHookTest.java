package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The join hook of the one-to-many kinds. A plain hasMany and a belongsToMany build the same SQL as
 * before the hook, and a subclass that overrides the hook gets its join into the lazy, eager and
 * existence queries.
 */
class PerformJoinHookTest {

    private static final String JOIN_TEXT = " inner join \"profiles\" on "
            + "\"profiles\".\"user_id\" = \"posts\".\"user_id\"";

    private static final String HAS_MANY_LAZY = "select * from \"posts\" where \"posts\".\"user_id\" = ?"
            + " and \"posts\".\"user_id\" is not null";

    private static final String JOINED_LAZY = "select * from \"posts\" inner join \"profiles\" on "
            + "\"profiles\".\"user_id\" = \"posts\".\"user_id\" where \"posts\".\"user_id\" = ?"
            + " and \"posts\".\"user_id\" is not null";

    private static final String ROLE_ALIASES = "\"role_user\".\"user_id\" as \"pivot_user_id\", "
            + "\"role_user\".\"role_id\" as \"pivot_role_id\", "
            + "\"role_user\".\"status\" as \"pivot_status\", "
            + "\"role_user\".\"weight\" as \"pivot_weight\", "
            + "\"role_user\".\"created_at\" as \"pivot_created_at\", "
            + "\"role_user\".\"updated_at\" as \"pivot_updated_at\"";

    private static final String ROLE_JOIN = " from \"roles\" inner join \"role_user\" on "
            + "\"roles\".\"id\" = \"role_user\".\"role_id\"";

    private static final String ROLES_LAZY = "select \"roles\".*, " + ROLE_ALIASES + ROLE_JOIN
            + " where \"role_user\".\"user_id\" = ?";

    private static final String ROLES_EAGER_OF_ONE = "select \"roles\".*, " + ROLE_ALIASES + ROLE_JOIN
            + " where \"role_user\".\"user_id\" in (1)";

    private static final String USERS_WITH_ROLE = "select * from \"users\" where exists (select * "
            + "from \"roles\" inner join \"role_user\" on \"roles\".\"id\" = \"role_user\".\"role_id\" "
            + "where \"users\".\"id\" = \"role_user\".\"user_id\")";

    private RelationDb db;
    private BtmDb btm;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        Map<String, Object> ann = new LinkedHashMap<>();
        ann.put("id", 1);
        ann.put("name", "Ann");
        db.seed("users", ann);
        btm = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        btm.close();
        db.close();
    }

    @Test
    void belongsToManySqlIsUnchanged() {
        BtmModels.User user = BtmModels.User.QUERY.on(btm.db).findOrFail(1);
        btm.clearLog();

        user.roles().get();
        assertEquals(ROLES_LAZY, btm.sql().get(0));
        btm.clearLog();
        ModelCollection<BtmModels.User> eagerUsers = BtmModels.User.QUERY.on(btm.db).where("id", 1)
                .with("roles").get();
        assertEquals(1, eagerUsers.size());
        assertEquals(ROLES_EAGER_OF_ONE, btm.sql().get(1));
        assertEquals(USERS_WITH_ROLE, BtmModels.User.QUERY.on(btm.db).query().has("roles").toSql());
    }

    @Test
    void overrideShowsInAllThreeBuildPoints() {
        JoinedPosts relation = joinedPosts();

        assertEquals(JOINED_LAZY, relation.getQuery().toSql());

        EloquentBuilder<Post> eager = Post.QUERY.on(db.db).query();
        relation.prepareEagerQuery(eager, Arrays.<Object>asList(1));
        assertTrue(eager.toSql().contains(JOIN_TEXT), eager.toSql());

        String existence = relation.existenceQuery(User.QUERY.on(db.db).query(), new AliasCounter()).toSql();
        assertTrue(existence.contains(JOIN_TEXT), existence);
    }

    @Test
    void hasManyIgnoresTheHook() {
        User ann = User.QUERY.on(db.db).findOrFail(1);
        HasMany<Post> posts = ann.posts();

        assertEquals(HAS_MANY_LAZY, posts.getQuery().toSql());

        EloquentBuilder<Post> eager = Post.QUERY.on(db.db).query();
        posts.prepareEagerQuery(eager, Arrays.<Object>asList(1));
        assertFalse(eager.toSql().contains("join"), eager.toSql());

        String existence = posts.existenceQuery(User.QUERY.on(db.db).query(), new AliasCounter()).toSql();
        assertFalse(existence.contains("join"), existence);
    }

    private JoinedPosts joinedPosts() {
        User ann = User.QUERY.on(db.db).findOrFail(1);
        return new JoinedPosts(Post.QUERY.on(db.db).query(), ann);
    }

    /** A has-many relation over posts that joins the profiles table in its hook. */
    static final class JoinedPosts extends HasOneOrMany<Post, JoinedPosts> {

        JoinedPosts(EloquentBuilder<Post> query, Model parent) {
            super(query, parent, "user_id", "id");
        }

        @Override
        void performJoin(EloquentBuilder<Post> target) {
            target.join("profiles", "profiles.user_id", "=", "posts.user_id");
        }

        @Override
        public ModelCollection<Post> getResults() {
            return get();
        }

        @Override
        JoinedPosts self() {
            return this;
        }

        @Override
        void initRelation(List<? extends Model> parents, String name) {
            for (Model parent : parents) {
                parent.setRelation(name, emptyCollection());
            }
        }

        @Override
        void match(List<? extends Model> parents, List<Post> results, String name) {
            Map<Object, List<Post>> dictionary = dictionary(results);
            for (Model parent : parents) {
                List<Post> matches = matchesOf(dictionary, parent);
                if (matches != null) {
                    parent.setRelation(name, collectionOf(matches));
                }
            }
        }
    }
}
