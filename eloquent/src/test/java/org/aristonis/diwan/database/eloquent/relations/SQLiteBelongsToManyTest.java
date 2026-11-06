package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.eloquent.page.CursorModelPage;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.exception.ModelNotFoundException;
import org.aristonis.diwan.database.eloquent.page.ModelPage;
import org.aristonis.diwan.database.eloquent.page.SimpleModelPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * belongsToMany reads executed on SQLite: lazy and eager loads, existence, the self relation,
 * aggregates, finders, pages and chunks. Pivot values and driver types are checked against the
 * same columns read raw from the database.
 */
class SQLiteBelongsToManyTest {

    /** The bound keys per eager query on SQLite, before a constraint's bindings are taken off. */
    private static final int SQLITE_BOUND_KEYS = 900;

    private static final int STRING_KEY_COUNT = 2_000;

    /** The countries {@link BtmDb} already seeds, which count toward {@link #STRING_KEY_COUNT}. */
    private static final int SEEDED_COUNTRY_COUNT = 2;

    private static final String ENGLISH = "en";

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    // ---- lazy reads

    @Test
    void lazyReadReturnsRolesWithPivotValuesAndDriverTypes() {
        BtmModels.User ann = user(1);

        ModelCollection<BtmModels.Role> roles = ann.roles().get();
        Pivot pivot = pivotOf(roleWithId(roles, 1));

        assertEquals(Arrays.asList(1L, 2L), sorted(keysOf(roles)));
        assertEquals("active", pivot.getAttribute("status"));
        assertEquals(5L, number(pivot, "weight"));
        assertEquals(1L, number(pivot, "user_id"));
        assertEquals(1L, number(pivot, "role_id"));
        assertDriverValue("select weight as v from role_user where user_id = 1 and role_id = 1",
                pivot.getAttribute("weight"));
        assertDriverValue("select created_at as v from role_user where user_id = 1 and role_id = 1",
                pivot.getAttribute("created_at"));
        assertDriverValue("select updated_at as v from role_user where user_id = 1 and role_id = 1",
                pivot.getAttribute("updated_at"));
    }

    @Test
    void lazyReadOfUserWithoutRolesIsEmpty() {
        BtmModels.User unattached = user(4);

        assertTrue(unattached.roles().get().isEmpty());
    }

    // ---- eager loads

    @Test
    void eagerWithRolesMatchesPivotsToEachUser() {
        db.clearLog();

        ModelCollection<BtmModels.User> users = users().with("roles").orderBy("id").get();

        assertEquals(2, db.queries().size());
        ModelCollection<BtmModels.Role> annRoles = users.get(0).relatedMany("roles");
        ModelCollection<BtmModels.Role> bobRoles = users.get(1).relatedMany("roles");
        assertEquals(Arrays.asList(1L, 2L), sorted(keysOf(annRoles)));
        assertEquals("pending", pivotOf(roleWithId(annRoles, 2)).getAttribute("status"));
        assertEquals(1L, number(pivotOf(roleWithId(annRoles, 2)), "user_id"));
        assertEquals(Collections.singletonList(1L), keysOf(bobRoles));
        assertEquals(2L, number(pivotOf(bobRoles.get(0)), "user_id"));
        assertTrue(users.get(2).<BtmModels.Role>relatedMany("roles").isEmpty());
        assertTrue(users.get(3).<BtmModels.Role>relatedMany("roles").isEmpty());
    }

    @Test
    void eagerRoleAttachedToTwoUsersIsTwoInstances() {
        ModelCollection<BtmModels.User> users = users().with("roles").orderBy("id").get();

        BtmModels.Role forAnn = roleWithId(users.get(0).relatedMany("roles"), 1);
        BtmModels.Role forBob = roleWithId(users.get(1).relatedMany("roles"), 1);

        assertNotSame(forAnn, forBob);
        assertEquals(1L, number(pivotOf(forAnn), "user_id"));
        assertEquals(2L, number(pivotOf(forBob), "user_id"));
    }

    // ---- existence

    @Test
    void hasAndWhereHasFilterUsersByRelatedRowsAndPivotColumn() {
        ModelCollection<BtmModels.User> withRoles = users().has("roles").orderBy("id").get();
        ModelCollection<BtmModels.User> pending = users()
                .whereHas("roles", q -> q.where("role_user.status", "pending"))
                .orderBy("id").get();

        assertEquals(Arrays.asList(1L, 2L), keysOf(withRoles));
        assertEquals(Collections.singletonList(1L), keysOf(pending));
    }

    @Test
    void selfRelationFriendsCountsAndFilters() {
        ModelCollection<BtmModels.User> counted = users().withCount("friends").orderBy("id").get();
        BtmModels.User ann = counted.get(0);

        assertEquals(1L, number(ann, "friends_count"));
        assertEquals(1L, number(counted.get(1), "friends_count"));
        assertEquals(0L, number(counted.get(2), "friends_count"));
        assertDriverValue("select count(*) as v from user_user where user_id = 1", ann.getAttribute("friends_count"));
        assertEquals(Arrays.asList(1L, 2L), keysOf(users().has("friends").orderBy("id").get()));
    }

    // ---- aggregates

    @Test
    void aggregatesReturnValuesAndDriverTypes() {
        ModelCollection<BtmModels.User> users = users().withCount("roles")
                .withSum("roles", "role_user.weight").withExists("roles").orderBy("id").get();
        BtmModels.User ann = users.get(0);

        assertEquals(2L, number(ann, "roles_count"));
        assertEquals(6L, number(ann, "roles_sum_role_userweight"));
        assertDriverValue("select count(*) as v from role_user where user_id = 1", ann.getAttribute("roles_count"));
        assertDriverValue("select sum(weight) as v from role_user where user_id = 1",
                ann.getAttribute("roles_sum_role_userweight"));
        assertEquals(Boolean.TRUE, ann.getAttribute("roles_exists"));
        assertEquals(0L, number(users.get(3), "roles_count"));
        assertNull(users.get(3).getAttribute("roles_sum_role_userweight"));
        assertEquals(Boolean.FALSE, users.get(3).getAttribute("roles_exists"));
    }

    // ---- finders

    @Test
    void findersReturnPivotedRolesAndDistinctIds() {
        BtmModels.User ann = user(1);

        Optional<BtmModels.Role> pending = ann.roles().find(2);
        ModelCollection<BtmModels.Role> distinct = ann.roles().findOrFail(Arrays.asList(1, 1, 2));

        assertTrue(pending.isPresent());
        assertEquals("pending", pivotOf(pending.get()).getAttribute("status"));
        assertFalse(ann.roles().find(99).isPresent());
        assertEquals(Arrays.asList(1L, 2L), sorted(keysOf(distinct)));
    }

    @Test
    void findOrFailRaisesWhenAKeyIsNotAttached() {
        BtmModels.User ann = user(1);

        assertThrows(ModelNotFoundException.class, () -> ann.roles().findOrFail(99));
        assertThrows(ModelNotFoundException.class, () -> ann.roles().findOrFail(Arrays.asList(1, 99)));
    }

    // ---- pages

    @Test
    void threePageKindsCarryPivotsAndTotals() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("roles.id");

        ModelPage<BtmModels.Role> first = roles.paginate(1);
        ModelPage<BtmModels.Role> second = roles.paginate(1, 2);
        SimpleModelPage<BtmModels.Role> simple = roles.simplePaginate(1);
        CursorModelPage<BtmModels.Role> cursor = roles.cursorPaginate(1);

        assertEquals(Collections.singletonList(1L), keysOf(first.items()));
        assertEquals("active", pivotOf(first.items().get(0)).getAttribute("status"));
        assertEquals(2L, first.total());
        assertTrue(first.hasMorePages());
        assertEquals(Collections.singletonList(2L), keysOf(second.items()));
        assertEquals("pending", pivotOf(second.items().get(0)).getAttribute("status"));
        assertFalse(second.hasMorePages());
        assertEquals(Collections.singletonList(1L), keysOf(simple.items()));
        assertTrue(simple.hasMorePages());
        assertEquals(Collections.singletonList(1L), keysOf(cursor.items()));
        assertTrue(cursor.nextCursor().isPresent());
    }

    @Test
    void cursorPaginateOrdersByPivotAliasAcrossPages() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("pivot_weight");

        CursorModelPage<BtmModels.Role> first = roles.cursorPaginate(1);
        CursorModelPage<BtmModels.Role> second = roles.cursorPaginate(1, first.nextCursor().get());

        assertEquals(Collections.singletonList(2L), keysOf(first.items()));
        assertEquals(1L, number(pivotOf(first.items().get(0)), "weight"));
        assertTrue(first.nextCursor().isPresent());
        assertEquals(Collections.singletonList(1L), keysOf(second.items()));
        assertEquals(5L, number(pivotOf(second.items().get(0)), "weight"));
        assertFalse(second.nextCursor().isPresent());
    }

    // ---- chunks

    @Test
    void chunkByIdVisitsEachRelatedRowInKeyOrder() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        List<Long> visited = new ArrayList<>();
        List<Object> pivotStatuses = new ArrayList<>();

        boolean completed = roles.chunkById(1, page -> {
            for (BtmModels.Role role : page) {
                visited.add(keyOf(role));
                pivotStatuses.add(pivotOf(role).getAttribute("status"));
            }
            return true;
        });

        assertTrue(completed);
        assertEquals(Arrays.asList(1L, 2L), visited);
        assertEquals(Arrays.asList("active", "pending"), pivotStatuses);
    }

    // ---- string keys

    @Test
    void twoThousandStringKeysLoadInThreeChunksOfNineHundred() {
        seedCountries(STRING_KEY_COUNT - SEEDED_COUNTRY_COUNT);
        db.clearLog();

        ModelCollection<BtmModels.Country> countries = BtmModels.Country.QUERY.on(db.db).query()
                .with("languages").get();

        assertEquals(STRING_KEY_COUNT, countries.size());
        assertEquals(Arrays.asList(SQLITE_BOUND_KEYS, SQLITE_BOUND_KEYS, 200), eagerBindingCounts());
        for (BtmModels.Country country : countries) {
            ModelCollection<BtmModels.Language> languages = country.relatedMany("languages");
            assertEquals(1, languages.size());
            assertEquals(country.getKey(), pivotOf(languages.get(0)).getAttribute("country_code"));
        }
    }

    // ---- helpers

    private BtmModels.User user(int id) {
        return BtmModels.User.QUERY.on(db.db).findOrFail(id);
    }

    private EloquentBuilder<BtmModels.User> users() {
        return BtmModels.User.QUERY.on(db.db).query();
    }

    private void seedCountries(int count) {
        for (int i = 0; i < count; i++) {
            String code = String.format("C%04d", i);
            db.seed("countries", Map.of("code", code, "name", "Country " + i));
            db.seed("country_language", Map.of("country_code", code, "language_iso", ENGLISH));
        }
    }

    private List<Integer> eagerBindingCounts() {
        List<Integer> counts = new ArrayList<>();
        for (BtmDb.Query query : db.queries()) {
            if (query.sql().startsWith("select \"languages\".*")) {
                counts.add(query.bindings().size());
            }
        }
        return counts;
    }

    private Object cell(String sql) {
        Object value = db.real.selectOne(sql).orElseThrow(IllegalStateException::new).get("v");
        assertNotNull(value, sql);
        return value;
    }

    /** Asserts that a value read through a model has the Java type and value of the column read raw. */
    private void assertDriverValue(String sql, Object actual) {
        Object raw = cell(sql);
        assertNotNull(actual, sql);
        assertEquals(raw.getClass(), actual.getClass(), sql);
        assertEquals(raw, actual, sql);
    }

    private static Pivot pivotOf(Model related) {
        Pivot pivot = Pivot.of(related);
        assertNotNull(pivot, "pivot of a related model");
        return pivot;
    }

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    private static long keyOf(Model model) {
        return ((Number) model.getKey()).longValue();
    }

    private static List<Long> keysOf(Iterable<? extends Model> models) {
        List<Long> keys = new ArrayList<>();
        for (Model model : models) {
            keys.add(keyOf(model));
        }
        return keys;
    }

    private static List<Long> sorted(List<Long> keys) {
        List<Long> copy = new ArrayList<>(keys);
        Collections.sort(copy);
        return copy;
    }

    private static BtmModels.Role roleWithId(Iterable<BtmModels.Role> roles, long id) {
        for (BtmModels.Role role : roles) {
            if (keyOf(role) == id) {
                return role;
            }
        }
        return fail("role " + id + " is not in the collection");
    }
}
