package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Category;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Country;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Role;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Statement;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Relations over soft-deleting related models: every relation kind on the lazy, eager, existence and
 * count paths, the table alias of a self relation, relation factories that lift the scope, and the
 * size of an eager key chunk when a scope carries bindings.
 */
class SoftDeleteRelationsTest {

    private static final String POSTS_LIVE = "\"posts\".\"deleted_at\" is null";
    private static final String ALIAS_LIVE = "\"laravel_reserved_0\".\"deleted_at\" is null";
    private static final int ANDROID_LIMIT = 900;
    private static final Consumer<EloquentBuilder<?>> LIFT = q -> q.withTrashed();
    private static final Consumer<EloquentBuilder<?>> KEEP = q -> { };

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        StringKeyUser.QUERY.clearGlobalScopes();
        TrashingUser.QUERY.clearGlobalScopes();
        LoneCountry.QUERY.clearGlobalScopes();
        fixtures.close();
    }

    /** A user whose key type is a string, so eager keys are bound instead of inlined. */
    static final class StringKeyUser extends Model {
        static final ModelQuery<StringKeyUser> QUERY = ModelQuery.of(StringKeyUser.class, StringKeyUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected String keyType() {
            return "string";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(StringKeyUser.class).add("posts", StringKeyUser::posts);
        }

        HasMany<Post> posts() {
            return hasMany(Post.class, "user_id");
        }
    }

    /** A user whose relation factory asks for trashed posts, as a relation method may. */
    static final class TrashingUser extends Model {
        static final ModelQuery<TrashingUser> QUERY = ModelQuery.of(TrashingUser.class, TrashingUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TrashingUser.class).add("posts", TrashingUser::posts);
        }

        HasMany<Post> posts() {
            return hasMany(Post.class, "user_id").withTrashed();
        }
    }

    /** A country with a one-through-one relation that can only match the trashed post 4. */
    static final class LoneCountry extends Model {
        static final ModelQuery<LoneCountry> QUERY = ModelQuery.of(LoneCountry.class, LoneCountry::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(LoneCountry.class).add("trashedPost", LoneCountry::trashedPost);
        }

        HasOneThrough<Post> trashedPost() {
            return hasOneThrough(Post.class, User.class, "country_id", "user_id", "id", "id")
                    .where("posts.id", 4);
        }
    }

    private static List<Long> ids(Iterable<? extends Model> models) {
        List<Long> ids = new ArrayList<>();
        for (Model model : models) {
            ids.add(Long.parseLong(String.valueOf(model.getKey())));
        }
        Collections.sort(ids);
        return ids;
    }

    private static List<Long> longs(long... values) {
        List<Long> list = new ArrayList<>();
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    private static int number(Model model, String attribute) {
        return ((Number) model.getAttribute(attribute)).intValue();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> constraint(String relation,
            Consumer<EloquentBuilder<?>> clause) {
        return Collections.<String, Consumer<EloquentBuilder<?>>>singletonMap(relation, clause);
    }

    private void trashUser(long id) {
        assertTrue(fixtures.users().findOrFail(id).delete());
        fixtures.log.clear();
    }

    private void addUsersUpTo(int last) {
        fixtures.real.statement("with recursive n(i) as (select 4 union all select i + 1 from n where i < " + last
                + ") insert into users (id, country_id, name) select i, 2, 'U' from n");
        fixtures.log.clear();
    }

    private List<Statement> postReads() {
        List<Statement> reads = new ArrayList<>();
        for (Statement statement : fixtures.log.reads()) {
            if (statement.sql().contains("from \"posts\"")) {
                reads.add(statement);
            }
        }
        return reads;
    }

    private void addUserWithOnlyATrashedPost() {
        fixtures.insert("users", "id", 4, "country_id", 1, "name", "Dan");
        fixtures.insert("posts", "id", 7, "tenant_id", ScopeFixtures.TENANT_ID, "user_id", 4, "writer_id", null,
                "title", "Post 7", "deleted_at", ScopeFixtures.TRASHED_AT);
    }

    // ---- has many

    @Test
    void hasManyHidesTrashedRelated() {
        User ann = fixtures.users().findOrFail(1);

        List<Long> live = ids(ann.posts().get());
        List<Long> all = ids(ann.posts().withTrashed().get());

        assertEquals(longs(1, 2), live);
        assertEquals(longs(1, 2, 4), all);
    }

    @Test
    void hasManyOnlyTrashedReturnsTheTrashedRows() {
        User ann = fixtures.users().findOrFail(1);

        assertEquals(longs(4), ids(ann.posts().onlyTrashed().get()));
    }

    @Test
    void hasManyEagerLoadHidesTrashedRelated() {
        List<User> users = fixtures.users().query().where("id", 1).get();

        assertEquals(longs(1, 2), ids(users.get(0).relatedMany("posts")));
    }

    @Test
    void hasManyExistenceHonoursTheScope() {
        addUserWithOnlyATrashedPost();

        List<Long> live = ids(fixtures.users().query().has("posts").get());
        List<Long> lifted = ids(fixtures.users().query().whereHas("posts", LIFT).get());

        assertEquals(longs(1, 2), live);
        assertEquals(longs(1, 2, 4), lifted);
    }

    @Test
    void everyEagerChunkCarriesTheScope() {
        addUsersUpTo(1_500);

        ModelCollection<StringKeyUser> users = StringKeyUser.QUERY.on(fixtures.db).query().with("posts").get();

        List<Statement> reads = postReads();
        assertEquals(1_500, users.size());
        assertEquals(2, reads.size());
        for (Statement read : reads) {
            assertTrue(read.sql().contains(POSTS_LIVE), read.sql());
            assertTrue(read.bindings().size() <= ANDROID_LIMIT, read.sql());
        }
        Model ann = users.get(0);
        assertEquals(longs(1, 2), ids(ann.relatedMany("posts")));
    }

    @Test
    void eagerConstraintCanLiftTheScope() {
        List<User> lifted = fixtures.users().query().where("id", 1).with("posts", LIFT).get();
        List<User> plain = fixtures.users().query().where("id", 1).with("posts").get();

        assertEquals(longs(1, 2, 4), ids(lifted.get(0).relatedMany("posts")));
        assertEquals(longs(1, 2), ids(plain.get(0).relatedMany("posts")));
    }

    @Test
    void whereHasHonoursRelatedScopes() {
        addUserWithOnlyATrashedPost();

        List<Long> plain = ids(fixtures.users().query().whereHas("posts", KEEP).get());
        List<Long> lifted = ids(fixtures.users().query().whereHas("posts", LIFT).get());

        assertFalse(plain.contains(4L));
        assertTrue(lifted.contains(4L));
    }

    @Test
    void withCountSkipsTrashed() {
        User plain = fixtures.users().query().withCount("posts").where("id", 1).first().get();
        User lifted = fixtures.users().query().withCount(constraint("posts", LIFT)).where("id", 1).first().get();

        assertEquals(2, number(plain, "posts_count"));
        assertEquals(3, number(lifted, "posts_count"));
    }

    @Test
    void relationFactoryThatLiftsTheScopeReachesExistence() {
        addUserWithOnlyATrashedPost();

        List<Long> viaFactory = ids(TrashingUser.QUERY.on(fixtures.db).query().has("posts").get());
        List<Long> viaPlain = ids(fixtures.users().query().has("posts").get());

        assertTrue(viaFactory.contains(4L));
        assertFalse(viaPlain.contains(4L));
    }

    @Test
    void relationFactoryThatLiftsTheScopeReachesLazyAndEager() {
        TrashingUser ann = TrashingUser.QUERY.on(fixtures.db).findOrFail(1);

        List<TrashingUser> eager = TrashingUser.QUERY.on(fixtures.db).query().where("id", 1).with("posts").get();

        assertEquals(longs(1, 2, 4), ids(ann.posts().get()));
        assertEquals(longs(1, 2, 4), ids(eager.get(0).relatedMany("posts")));
    }

    @Test
    void deleteAndRestoreRoundTrip() {
        User ann = fixtures.users().findOrFail(1);
        Post first = fixtures.posts().findOrFail(1);

        assertTrue(first.delete());
        List<Long> afterDelete = ids(ann.posts().get());
        first.restore();
        List<Long> afterRestore = ids(ann.posts().get());

        assertEquals(longs(2), afterDelete);
        assertEquals(longs(1, 2), afterRestore);
    }

    // ---- belongs to

    @Test
    void belongsToTrashedParent() {
        trashUser(1);
        Post post = fixtures.posts().findOrFail(1);

        Model hidden = post.user().getResults();
        Model fallback = post.user().withDefault().getResults();
        Model lifted = post.user().withTrashed().getResults();

        assertNull(hidden);
        assertNotNull(fallback);
        assertFalse(fallback.exists());
        assertNotNull(lifted);
        assertEquals("1", String.valueOf(lifted.getKey()));
    }

    @Test
    void belongsToEagerExistenceAndCountHonourTheScope() {
        trashUser(1);

        List<Post> eager = fixtures.posts().query().with("user").get();
        List<Long> has = ids(fixtures.posts().query().has("user").get());
        List<Long> lifted = ids(fixtures.posts().query().whereHas("user", LIFT).get());
        Post counted = fixtures.posts().query().withCount("user").where("id", 1).first().get();
        Post countedLifted = fixtures.posts().query().withCount(constraint("user", LIFT)).where("id", 1)
                .first().get();

        Map<Long, Post> byId = new java.util.HashMap<>();
        for (Post post : eager) {
            byId.put(ids(Collections.singletonList(post)).get(0), post);
        }
        assertNull(byId.get(1L).related("user"));
        assertNotNull(byId.get(3L).related("user"));
        assertEquals(longs(3), has);
        assertEquals(longs(1, 2, 3), lifted);
        assertEquals(0, number(counted, "user_count"));
        assertEquals(1, number(countedLifted, "user_count"));
    }

    // ---- belongs to many

    @Test
    void belongsToManyQualifiesDeletedAt() {
        fixtures.real.statement("update role_user set deleted_at = '" + ScopeFixtures.TRASHED_AT
                + "' where user_id = 1 and role_id = 1");
        User ann = fixtures.users().findOrFail(1);
        fixtures.log.clear();

        List<Long> live = ids(ann.roles().getResults());

        String sql = fixtures.log.reads().get(0).sql();
        assertEquals(longs(1), live);
        assertTrue(sql.contains("\"roles\".\"deleted_at\" is null"), sql);
        assertFalse(sql.contains("\"role_user\".\"deleted_at\""), sql);
    }

    @Test
    void belongsToManyLazyEagerExistenceAndCountHonourTheScope() {
        fixtures.insert("role_user", "user_id", 3, "role_id", 2);
        User ann = fixtures.users().findOrFail(1);

        List<User> eager = fixtures.users().query().where("id", 1).with("roles").get();
        List<Long> has = ids(fixtures.users().query().has("roles").get());
        List<Long> lifted = ids(fixtures.users().query().whereHas("roles", LIFT).get());
        User counted = fixtures.users().query().withCount("roles").where("id", 1).first().get();
        User countedLifted = fixtures.users().query().withCount(constraint("roles", LIFT)).where("id", 1)
                .first().get();

        assertEquals(longs(1, 2), ids(ann.roles().withTrashed().getResults()));
        assertEquals(longs(1), ids(eager.get(0).relatedMany("roles")));
        assertEquals(longs(1, 2), has);
        assertEquals(longs(1, 2, 3), lifted);
        assertEquals(1, number(counted, "roles_count"));
        assertEquals(2, number(countedLifted, "roles_count"));
    }

    // ---- through relations

    @Test
    void hasManyThroughLazyEagerExistenceAndCountHonourTheScope() {
        Country france = fixtures.countries().findOrFail(1);

        List<Country> eager = fixtures.countries().query().where("id", 1).with("posts").get();
        List<Long> has = ids(fixtures.countries().query().has("posts").get());
        Country counted = fixtures.countries().query().withCount("posts").where("id", 1).first().get();
        Country countedLifted = fixtures.countries().query().withCount(constraint("posts", LIFT)).where("id", 1)
                .first().get();

        assertEquals(longs(1, 2, 3), ids(france.posts().get()));
        assertEquals(longs(1, 2, 3, 4), ids(france.posts().withTrashed().get()));
        assertEquals(longs(1, 2, 3), ids(eager.get(0).relatedMany("posts")));
        assertEquals(longs(1), has);
        assertEquals(3, number(counted, "posts_count"));
        assertEquals(4, number(countedLifted, "posts_count"));
    }

    @Test
    void hasOneThroughLazyEagerExistenceAndCountHonourTheScope() {
        LoneCountry france = LoneCountry.QUERY.on(fixtures.db).findOrFail(1);

        List<LoneCountry> eager = LoneCountry.QUERY.on(fixtures.db).query().where("id", 1).with("trashedPost").get();
        List<LoneCountry> eagerLifted = LoneCountry.QUERY.on(fixtures.db).query().where("id", 1)
                .with("trashedPost", LIFT).get();
        List<Long> has = ids(LoneCountry.QUERY.on(fixtures.db).query().has("trashedPost").get());
        List<Long> lifted = ids(LoneCountry.QUERY.on(fixtures.db).query().whereHas("trashedPost", LIFT).get());
        LoneCountry counted = LoneCountry.QUERY.on(fixtures.db).query().withCount("trashedPost").where("id", 1)
                .first().get();
        LoneCountry countedLifted = LoneCountry.QUERY.on(fixtures.db).query()
                .withCount(constraint("trashedPost", LIFT)).where("id", 1).first().get();

        assertNull(france.trashedPost().getResults());
        assertEquals("4", String.valueOf(france.trashedPost().withTrashed().getResults().getKey()));
        assertTrue(eager.get(0).relationLoaded("trashedPost"));
        assertNull(eager.get(0).getRelation("trashedPost"));
        assertNotNull(eagerLifted.get(0).getRelation("trashedPost"));
        assertTrue(has.isEmpty());
        assertEquals(longs(1), lifted);
        assertEquals(0, number(counted, "trashed_post_count"));
        assertEquals(1, number(countedLifted, "trashed_post_count"));
    }

    // ---- self relation

    @Test
    void selfRelationScopeUsesTheAlias() {
        fixtures.insert("categories", "id", 5, "parent_id", null, "name", "lone");
        fixtures.insert("categories", "id", 6, "parent_id", 5, "name", "dead", "deleted_at",
                ScopeFixtures.TRASHED_AT);

        EloquentBuilder<Category> plain = fixtures.categories().query().whereHas("children", KEEP);
        EloquentBuilder<Category> lifted = fixtures.categories().query().whereHas("children", LIFT);

        String sql = plain.toSql();
        assertTrue(sql.contains(ALIAS_LIVE), sql);
        assertEquals(1, occurrences(sql, "\"categories\".\"deleted_at\" is null"), sql);
        assertFalse(lifted.toSql().contains(ALIAS_LIVE), lifted.toSql());
        assertEquals(longs(1, 2), ids(plain.get()));
        assertEquals(longs(1, 2, 5), ids(lifted.get()));
    }

    // ---- forwarders

    @Test
    void forwardersReturnTheRelation() {
        User ann = fixtures.users().findOrFail(1);
        Country france = fixtures.countries().findOrFail(1);
        LoneCountry lone = LoneCountry.QUERY.on(fixtures.db).findOrFail(1);
        BelongsToMany<Role> roles = ann.roles();
        HasManyThrough<Post> posts = france.posts();
        HasOneThrough<Post> post = lone.trashedPost();

        assertSame(roles, roles.withTrashed());
        assertSame(posts, posts.withTrashed());
        assertSame(post, post.withTrashed());
        assertEquals(longs(1, 2), ids(roles.getResults()));
        assertEquals(longs(1, 2, 3, 4), ids(posts.get()));
        assertEquals("4", String.valueOf(post.getResults().getKey()));
    }

    @Test
    void onlyTrashedAndWithoutTrashedOnARelationReachTheQuery() {
        User ann = fixtures.users().findOrFail(1);

        List<Long> only = ids(ann.roles().onlyTrashed().getResults());
        List<Long> kept = ids(ann.roles().withTrashed().withoutTrashed().getResults());

        assertEquals(longs(2), only);
        assertEquals(longs(1), kept);
    }

    // ---- chunk size

    @Test
    void scopeBindingsShrinkTheChunk() {
        addUsersUpTo(2_000);
        Post.QUERY.addGlobalScope("views", builder -> builder.whereIn("views", Arrays.asList(0, 1, 2)));

        StringKeyUser.QUERY.on(fixtures.db).query().with("posts").get();

        List<Statement> reads = postReads();
        assertEquals(3, reads.size());
        assertEquals(ANDROID_LIMIT, reads.get(0).bindings().size());
        for (Statement read : reads) {
            assertTrue(read.bindings().size() <= ANDROID_LIMIT, read.sql());
        }
    }
}
