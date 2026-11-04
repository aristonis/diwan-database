package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelNotFoundException;
import org.aristonis.diwan.database.eloquent.MultipleRecordsFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The finders of a belongsToMany relation. Each one reads a copy of the relation's query, so the roles
 * it returns carry their pivot, the relation's own query is unchanged, and the builder's rules for
 * empty keys and missing rows hold.
 */
class BelongsToManyFindTest {

    private static final String ALIASES = "\"role_user\".\"user_id\" as \"pivot_user_id\", "
            + "\"role_user\".\"role_id\" as \"pivot_role_id\", "
            + "\"role_user\".\"status\" as \"pivot_status\", "
            + "\"role_user\".\"weight\" as \"pivot_weight\", "
            + "\"role_user\".\"created_at\" as \"pivot_created_at\", "
            + "\"role_user\".\"updated_at\" as \"pivot_updated_at\"";

    private static final String JOIN = " from \"roles\" inner join \"role_user\" on "
            + "\"roles\".\"id\" = \"role_user\".\"role_id\"";

    private static final String USER_1_WHERE = " where \"role_user\".\"user_id\" = ?";

    private static final String ROLES_SELECT = "select \"roles\".*, " + ALIASES + JOIN + USER_1_WHERE;

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void findOne() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        db.clearLog();

        Optional<BtmModels.Role> found = roles.find(2);

        assertEquals(Collections.singletonList(ROLES_SELECT + " and \"roles\".\"id\" = ? limit 1"), db.sql());
        assertTrue(found.isPresent());
        assertEquals(2, found.get().getAttribute("id"));
        assertEquals("pending", Pivot.of(found.get()).getAttribute("status"));
    }

    @Test
    void findMissing() {
        assertFalse(user(1).roles().find(99).isPresent());
    }

    @Test
    void findMany() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        db.clearLog();

        ModelCollection<BtmModels.Role> found = roles.find(Arrays.asList(1, 2));

        assertEquals(new HashSet<>(Arrays.asList(1, 2)), new HashSet<>(found.modelKeys()));
        for (BtmModels.Role role : found) {
            assertHasPivot(role);
        }
        assertEquals(1, db.sql().size());
    }

    @Test
    void findManyEmpty() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        db.clearLog();

        ModelCollection<BtmModels.Role> found = roles.findMany(Collections.emptyList());

        assertTrue(found.isEmpty());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void findOrFailThrows() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();

        ModelNotFoundException failure = assertThrows(ModelNotFoundException.class, () -> roles.findOrFail(99));

        assertEquals(BtmModels.Role.class.getName(), failure.getModel());
    }

    @Test
    void distinctIds() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();

        ModelCollection<BtmModels.Role> found = roles.findOrFail(Arrays.asList(1, 1, 2));

        assertEquals(new HashSet<>(Arrays.asList(1, 2)), new HashSet<>(found.modelKeys()));
    }

    @Test
    void someMissing() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();

        assertThrows(ModelNotFoundException.class, () -> roles.findOrFail(Arrays.asList(1, 99)));
    }

    @Test
    void first() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("roles.id");
        db.clearLog();

        Optional<BtmModels.Role> found = roles.first();

        assertEquals(Collections.singletonList(ROLES_SELECT + " order by \"roles\".\"id\" asc limit 1"), db.sql());
        assertTrue(found.isPresent());
        assertEquals(1, found.get().getAttribute("id"));
        assertEquals("active", Pivot.of(found.get()).getAttribute("status"));
    }

    @Test
    void firstOrFailThrowsWhenNoRole() {
        BelongsToMany<BtmModels.Role> roles = user(3).roles();

        ModelNotFoundException failure = assertThrows(ModelNotFoundException.class, () -> roles.firstOrFail());

        assertEquals(BtmModels.Role.class.getName(), failure.getModel());
    }

    @Test
    void firstWhere() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();

        Optional<BtmModels.Role> byName = roles.firstWhere("name", "admin");
        Optional<BtmModels.Role> byPivotWeight = roles.firstWhere("role_user.weight", ">", 2);

        assertEquals(1, byName.get().getAttribute("id"));
        assertEquals(1, byPivotWeight.get().getAttribute("id"));
        assertEquals(5, ((Number) Pivot.of(byPivotWeight.get()).getAttribute("weight")).intValue());
    }

    @Test
    void firstDoesNotLimitRelation() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        roles.first();
        db.clearLog();

        ModelCollection<BtmModels.Role> all = roles.get();

        assertEquals(Collections.singletonList(ROLES_SELECT), db.sql());
        assertEquals(2, all.size());
    }

    @Test
    void firstOrUsesFallbackWhenNoRole() {
        BtmModels.Role fallback = BtmModels.Role.QUERY.on(db.db).newModel();

        BtmModels.Role result = user(3).roles().firstOr(() -> fallback);

        assertSame(fallback, result);
    }

    @Test
    void findOrUsesFallbackWhenMissing() {
        BtmModels.Role fallback = BtmModels.Role.QUERY.on(db.db).newModel();

        BtmModels.Role result = user(1).roles().findOr(99, () -> fallback);

        assertSame(fallback, result);
    }

    @Test
    void findOrReturnsAttachedRole() {
        BtmModels.Role fallback = BtmModels.Role.QUERY.on(db.db).newModel();

        BtmModels.Role result = user(1).roles().findOr(2, () -> fallback);

        assertEquals(2, result.getAttribute("id"));
        assertHasPivot(result);
    }

    @Test
    void soleReturnsOnlyRole() {
        BelongsToMany<BtmModels.Role> roles = user(2).roles();
        db.clearLog();

        BtmModels.Role only = roles.sole();

        assertEquals(Collections.singletonList(ROLES_SELECT + " limit 2"), db.sql());
        assertEquals(1, only.getAttribute("id"));
        assertHasPivot(only);
    }

    @Test
    void soleWithTwoRowsThrows() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();

        MultipleRecordsFoundException failure = assertThrows(MultipleRecordsFoundException.class, () -> roles.sole());

        assertEquals(2, failure.getCount());
    }

    @Test
    void soleWithNoRowThrows() {
        BelongsToMany<BtmModels.Role> roles = user(3).roles();

        assertThrows(ModelNotFoundException.class, () -> roles.sole());
    }

    private BtmModels.User user(int id) {
        return BtmModels.User.QUERY.on(db.db).findOrFail(id);
    }

    private static void assertHasPivot(BtmModels.Role role) {
        assertNotNull(Pivot.of(role));
    }
}
