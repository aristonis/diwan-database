package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Eager loading of belongsToMany: the one query per load, the match by key, the select the
 * constraint leaves, the refusals and the nested, count and load forms that reach the relation.
 */
class BelongsToManyEagerTest {

    private static final String ALIASES = "\"role_user\".\"user_id\" as \"pivot_user_id\", "
            + "\"role_user\".\"role_id\" as \"pivot_role_id\", "
            + "\"role_user\".\"status\" as \"pivot_status\", "
            + "\"role_user\".\"weight\" as \"pivot_weight\", "
            + "\"role_user\".\"created_at\" as \"pivot_created_at\", "
            + "\"role_user\".\"updated_at\" as \"pivot_updated_at\"";

    private static final String ROLE_JOIN = " from \"roles\" inner join \"role_user\" on "
            + "\"roles\".\"id\" = \"role_user\".\"role_id\"";

    private static final String COUNTRY_ALIASES = "\"country_language\".\"country_code\" as \"pivot_country_code\", "
            + "\"country_language\".\"language_iso\" as \"pivot_language_iso\"";

    private static final String COUNTRY_EAGER = "select \"languages\".*, " + COUNTRY_ALIASES
            + " from \"languages\" inner join \"country_language\" on \"languages\".\"iso\" = "
            + "\"country_language\".\"language_iso\"";

    private static final String ROLE_KEYS_WHERE = " where \"role_user\".\"user_id\" in (";

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        ModelQuery.preventLazyLoading(false);
        db.close();
    }

    @Test
    void shapeAndMatch() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).with("roles").where("id", "<=", 3).get();

        assertEquals(2, db.sql().size());
        assertEquals(eagerRolesFor("1, 2, 3"), db.sql().get(1));
        assertEquals(ids(1, 2), idSet(loaded(byId(users, 1), "roles")));
        assertEquals(ids(1), idSet(loaded(byId(users, 2), "roles")));
        assertTrue(loaded(byId(users, 3), "roles").isEmpty());
    }

    @Test
    void sharedRoleGivesTwoInstancesWithOwnPivots() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).with("roles").where("id", "<=", 2).get();
        ModelCollection<BtmModels.Role> rolesOfUser1 = loaded(byId(users, 1), "roles");
        ModelCollection<BtmModels.Role> rolesOfUser2 = loaded(byId(users, 2), "roles");

        BtmModels.Role viaUser1 = byId(rolesOfUser1, 1);
        BtmModels.Role viaUser2 = byId(rolesOfUser2, 1);

        assertNotSame(viaUser1, viaUser2);
        assertEquals(1L, number(Pivot.of(viaUser1), "user_id"));
        assertEquals(2L, number(Pivot.of(viaUser2), "user_id"));
    }

    @Test
    void nullKeyParentDroppedFromKeysAndGetsEmptyCollection() {
        ModelCollection<ScopedUser> users = usersWithKeys(1, null);
        db.clearLog();

        users.load("roles");

        assertEquals(Collections.singletonList(eagerRolesFor("1")), db.sql());
        assertTrue(loaded(users.get(1), "roles").isEmpty());
    }

    @Test
    void onlyNullKeyParentsRunNoQuery() {
        ModelCollection<ScopedUser> users = usersWithKeys(null, null);
        db.clearLog();

        users.load("roles");

        assertEquals(Collections.emptyList(), db.sql());
        assertTrue(loaded(users.get(0), "roles").isEmpty());
        assertTrue(loaded(users.get(1), "roles").isEmpty());
    }

    @Test
    void longParentKeysMatchIntegerPivotKeys() {
        ModelCollection<ScopedUser> users = usersWithKeys(1L, 2L);
        db.clearLog();

        users.load("roles");

        assertEquals(Collections.singletonList(eagerRolesFor("1, 2")), db.sql());
        assertEquals(ids(1, 2), idSet(loaded(byId(users, 1L), "roles")));
    }

    @Test
    void stringParentKeysAreBoundAndMatchedByNormalisedKey() {
        db.seed("countries", Map.of("code", "1", "name", "Numeric"));
        db.seed("country_language", Map.of("country_code", "1", "language_iso", "en"));

        ModelCollection<BtmModels.Country> countries = BtmModels.Country.QUERY.on(db.db)
                .with("languages").where("code", "1").get();

        assertEquals(COUNTRY_EAGER + " where \"country_language\".\"country_code\" in (?)", db.sql().get(1));
        ModelCollection<BtmModels.Language> languages = loaded(countries.get(0), "languages");
        assertEquals(Collections.singleton("English"), attributes(languages, "name"));
    }

    @Test
    void customParentKeyReadsCodeAndMatchesPivotForeignKey() {
        ModelCollection<BtmModels.Country> countries = BtmModels.Country.QUERY.on(db.db).with("languages").get();

        assertEquals(COUNTRY_EAGER + " where \"country_language\".\"country_code\" in (?, ?)", db.sql().get(1));
        ModelCollection<BtmModels.Language> us = loaded(countries.find("US").get(), "languages");
        ModelCollection<BtmModels.Language> fr = loaded(countries.find("FR").get(), "languages");
        assertEquals(Collections.singleton("English"), attributes(us, "name"));
        assertEquals(Collections.singleton("French"), attributes(fr, "name"));
    }

    @Test
    void narrowSelectClosureStillMatchesAndAddsAliases() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db)
                .with("roles", q -> q.select("name")).where("id", "<=", 1).get();

        assertEquals(eagerNarrowFor("1"), db.sql().get(1));
        ModelCollection<BtmModels.Role> roles = loaded(byId(users, 1), "roles");
        assertEquals(new HashSet<>(Arrays.asList("admin", "editor")), attributes(roles, "name"));
        assertNotNull(Pivot.of(roles.get(0)));
    }

    @Test
    void emptySelectClosureSelectsRelatedColumnsAndAliases() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db)
                .with("roles", q -> q.select()).where("id", "<=", 1).get();

        assertEquals(eagerRolesFor("1"), db.sql().get(1));
        assertEquals(ids(1, 2), idSet(loaded(byId(users, 1), "roles")));
    }

    @Test
    void noUnaliasedMatchColumnIsAddedToNarrowSelect() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db)
                .with("roles", q -> q.select("name")).where("id", "<=", 1).get();

        assertFalse(db.sql().get(1).contains("\"role_user\".\"user_id\", "));
        ModelCollection<BtmModels.Role> roles = loaded(byId(users, 1), "roles");
        assertFalse(roles.isEmpty());
        for (BtmModels.Role role : roles) {
            assertNull(role.getAttribute("user_id"));
        }
    }

    @Test
    void qualifiedPivotWhereFiltersPerParent() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db)
                .with("roles", q -> q.where("role_user.status", "active")).get();

        assertEquals(ids(1), idSet(loaded(byId(users, 1), "roles")));
        assertEquals(ids(1), idSet(loaded(byId(users, 2), "roles")));
        assertTrue(loaded(byId(users, 3), "roles").isEmpty());
        assertTrue(loaded(byId(users, 4), "roles").isEmpty());
    }

    @Test
    void constraintOrderIsKeptInMatchedList() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db)
                .with("roles", q -> q.orderByDesc("roles.name")).where("id", "<=", 1).get();

        assertEquals(Arrays.asList(2, 1), loaded(byId(users, 1), "roles").modelKeys());
    }

    @Test
    void closureLimitIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> ScopedUser.QUERY.on(db.db).with("roles", q -> q.limit(2)).get());
    }

    @Test
    void relationLimitIsRefused() {
        assertThrows(IllegalStateException.class, () -> ScopedUser.QUERY.on(db.db).with("limitedRoles").get());
    }

    @Test
    void nestedPathLoadsOnHydratedRolesOneQueryPerLevel() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).with("roles.permissions").get();

        assertEquals(3, db.sql().size());
        ModelCollection<BtmModels.Role> roles = loaded(byId(users, 1), "roles");
        BtmModels.Role admin = byId(roles, 1);
        assertEquals(ids(1, 2), idSet(loaded(admin, "permissions")));
        assertNotNull(Pivot.of(admin));
    }

    @Test
    void nestedPathUnderHasManyCarriesPivots() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).with("posts.tags").where("id", "<=", 2).get();

        ModelCollection<BtmModels.Post> posts = loaded(byId(users, 1), "posts");
        BtmModels.Post post1 = byId(posts, 1);
        ModelCollection<BtmModels.Tag> tags = loaded(post1, "tags");
        assertEquals(ids(1, 2), idSet(tags));
        assertNotNull(Pivot.of(tags.get(0)));
    }

    @Test
    void loadAndLoadMissingOnModelAndCollection() {
        ScopedUser user1 = ScopedUser.QUERY.on(db.db).findOrFail(1);
        user1.load("roles");
        assertEquals(ids(1, 2), idSet(loaded(user1, "roles")));
        db.clearLog();
        user1.loadMissing("roles");
        assertEquals(Collections.emptyList(), db.sql());

        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).all();
        users.load("roles");
        assertEquals(ids(1, 2), idSet(loaded(byId(users, 1), "roles")));
        db.clearLog();
        users.loadMissing("roles");
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void loadedEmptyCollectionCountsAsLoaded() {
        ScopedUser user3 = ScopedUser.QUERY.on(db.db).findOrFail(3);
        user3.load("roles");
        assertTrue(user3.relationLoaded("roles"));
        db.clearLog();

        user3.loadMissing("roles");

        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void eagerAndCountBothWork() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).with("roles").withCount("roles").get();

        assertEquals(2, db.sql().size());
        assertEquals(2L, number(byId(users, 1), "roles_count"));
        assertEquals(1L, number(byId(users, 2), "roles_count"));
        assertEquals(0L, number(byId(users, 3), "roles_count"));
    }

    @Test
    void wherePivotDeclaredInRelationAppliesInEagerQuery() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).with("activeRoles").get();

        assertTrue(db.sql().get(1).contains("\"role_user\".\"status\" = ?"));
        assertEquals(ids(1), idSet(loaded(byId(users, 1), "activeRoles")));
        assertEquals(ids(1), idSet(loaded(byId(users, 2), "activeRoles")));
        assertTrue(loaded(byId(users, 3), "activeRoles").isEmpty());
    }

    @Test
    void orderByPivotDeclaredInRelationOrdersEagerMatches() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).with("rolesByWeight").get();

        assertEquals(Arrays.asList(2, 1), loaded(byId(users, 1), "rolesByWeight").modelKeys());
    }

    @Test
    void nullPivotKeyRowIsSkippedWithoutError() {
        SQLiteSchema.of(db.real).create("broken_pivot", t -> {
            t.integer("user_id").nullable();
            t.integer("role_id");
        });
        db.seed("broken_pivot", Map.of("role_id", 1));
        db.seed("broken_pivot", Map.of("user_id", 1, "role_id", 2));

        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db)
                .with("brokenRoles", q -> q.orWhereNull("broken_pivot.user_id"))
                .where("id", "<=", 2).get();

        assertEquals(ids(2), idSet(loaded(byId(users, 1), "brokenRoles")));
        assertTrue(loaded(byId(users, 2), "brokenRoles").isEmpty());
    }

    @Test
    void parentsOfTwoClassesRefused() {
        List<Model> parents = Arrays.asList(ScopedUser.QUERY.on(db.db).findOrFail(1),
                BtmModels.Role.QUERY.on(db.db).findOrFail(1));

        assertThrows(IllegalArgumentException.class,
                () -> EagerLoader.load(parents, Collections.singletonMap("roles", EagerLoader.NO_OP)));
    }

    @Test
    void customPivotClassGivesOnePivotInstancePerRow() {
        ModelCollection<BtmModels.User> users = BtmModels.User.QUERY.on(db.db).with("memberships")
                .where("id", 1).get();

        ModelCollection<BtmModels.Role> roles = loaded(users.get(0), "memberships");
        assertEquals(2, roles.size());
        for (BtmModels.Role role : roles) {
            assertTrue(Pivot.of(role) instanceof BtmModels.Membership);
        }
    }

    @Test
    void matchRelinksRealParentAsPivotParentAndChaperoneLink() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db).with("memberships")
                .where("id", "<=", 2).get();
        ScopedUser user1 = byId(users, 1);
        ModelCollection<BtmModels.Role> memberRoles = loaded(user1, "memberships");
        assertEquals(2, memberRoles.size());

        for (BtmModels.Role role : memberRoles) {
            Pivot pivot = Pivot.of(role, "pivot");
            assertSame(user1, pivot.getRelation("user"));
            assertSame(user1, pivot.getPivotParent());
        }
    }

    @Test
    void probeQueryIsNeverRun() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db)
                .with("roles", q -> q.where("role_user.status", "active"))
                .where("id", "<=", 2).get();

        assertEquals(2, db.sql().size());
        assertEquals(ids(1), idSet(loaded(byId(users, 1), "roles")));
    }

    @Test
    void whereGroupConstraintKeepsPivotsOnMatchedRoles() {
        ModelCollection<ScopedUser> users = ScopedUser.QUERY.on(db.db)
                .with("roles", q -> q.whereGroup(group -> group.where("roles.name", "admin")))
                .where("id", "<=", 1).get();

        ModelCollection<BtmModels.Role> roles = loaded(byId(users, 1), "roles");
        assertEquals(ids(1), idSet(roles));
        assertNotNull(Pivot.of(roles.get(0)));
    }

    // ---- helpers

    private ModelCollection<ScopedUser> usersWithKeys(Object... keys) {
        List<Map<String, ?>> rows = new ArrayList<>();
        for (Object key : keys) {
            rows.add(Collections.singletonMap("id", key));
        }
        return ScopedUser.QUERY.on(db.db).hydrate(rows);
    }

    private static String eagerRolesFor(String keys) {
        return "select \"roles\".*, " + ALIASES + ROLE_JOIN + ROLE_KEYS_WHERE + keys + ")";
    }

    private static String eagerNarrowFor(String keys) {
        return "select \"name\", " + ALIASES + ROLE_JOIN + ROLE_KEYS_WHERE + keys + ")";
    }

    private static Set<Object> ids(Object... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private static Set<Object> idSet(ModelCollection<?> models) {
        return new HashSet<>(models.modelKeys());
    }

    private static Set<Object> attributes(ModelCollection<? extends Model> models, String column) {
        Set<Object> values = new HashSet<>();
        for (Model model : models) {
            values.add(model.getAttribute(column));
        }
        return values;
    }

    private static <T extends Model> T byId(ModelCollection<T> models, Object id) {
        return models.find(id).get();
    }

    /** Reads a loaded relation; {@code getRelation} is untyped, so the caller states the element type. */
    @SuppressWarnings("unchecked")
    private static <T extends Model> ModelCollection<T> loaded(Model model, String name) {
        return (ModelCollection<T>) model.getRelation(name);
    }

    private static long number(Model model, String column) {
        return ((Number) model.getAttribute(column)).longValue();
    }

    /** The roles and languages each test declares on its own fixture, so one list reads one relation. */
    static final class ScopedUser extends Model {
        static final ModelQuery<ScopedUser> QUERY = ModelQuery.of(ScopedUser.class, ScopedUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(ScopedUser.class)
                    .add("roles", ScopedUser::roles)
                    .add("limitedRoles", ScopedUser::limitedRoles)
                    .add("activeRoles", ScopedUser::activeRoles)
                    .add("rolesByWeight", ScopedUser::rolesByWeight)
                    .add("memberships", ScopedUser::memberships)
                    .add("posts", ScopedUser::posts)
                    .add("brokenRoles", ScopedUser::brokenRoles);
        }

        public BelongsToMany<BtmModels.Role> roles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .withPivot("status", "weight")
                    .withTimestamps();
        }

        public BelongsToMany<BtmModels.Role> limitedRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id").limit(2);
        }

        public BelongsToMany<BtmModels.Role> activeRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .wherePivot("status", "active");
        }

        public BelongsToMany<BtmModels.Role> rolesByWeight() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .orderByPivot("weight");
        }

        public BelongsToMany<BtmModels.Role> memberships() {
            return belongsToMany(BtmModels.Role.class, BtmModels.Membership.class, "user_id", "role_id")
                    .chaperone();
        }

        public BelongsToMany<BtmModels.Role> brokenRoles() {
            return belongsToMany(BtmModels.Role.class, "broken_pivot", "user_id", "role_id");
        }

        public HasMany<BtmModels.Post> posts() {
            return hasMany(BtmModels.Post.class, "user_id");
        }
    }
}
