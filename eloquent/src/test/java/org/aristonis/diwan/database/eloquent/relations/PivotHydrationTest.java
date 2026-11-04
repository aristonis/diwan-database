package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pivot hydration on the belongsToMany read paths: the {@code pivot_*} columns move into a
 * pivot object, the pivot links to its parent and related model, and the role keeps no
 * prefixed attribute. Every case runs on the logged in-memory SQLite fixture.
 */
class PivotHydrationTest {

    private static final String ROLE_NAME = "name";

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private BtmModels.User user(int id) {
        BtmModels.User user = BtmModels.User.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return user;
    }

    private static BtmModels.Role named(ModelCollection<BtmModels.Role> roles, String name) {
        for (BtmModels.Role role : roles) {
            if (name.equals(role.getAttribute(ROLE_NAME))) {
                return role;
            }
        }
        throw new AssertionError("no role named " + name + " in the result");
    }

    private static List<BtmModels.Role> allNamed(ModelCollection<BtmModels.Role> roles, String name) {
        List<BtmModels.Role> matches = new ArrayList<>();
        for (BtmModels.Role role : roles) {
            if (name.equals(role.getAttribute(ROLE_NAME))) {
                matches.add(role);
            }
        }
        return matches;
    }

    private static Pivot pivotOf(Model model) {
        assertTrue(model.relationLoaded("pivot"), "the pivot relation is loaded");
        return (Pivot) model.getRelation("pivot");
    }

    private static int intAttribute(Model model, String key) {
        return ((Number) model.getAttribute(key)).intValue();
    }

    private static void assertNoPivotPrefixedAttributes(Model model) {
        for (String key : model.getAttributes().keySet()) {
            assertFalse(key.startsWith("pivot_"), "no pivot_ attribute on the related model: " + key);
        }
    }

    @Test
    void migratesPrefixed() {
        ModelCollection<BtmModels.Role> roles = user(1).roles().get();

        BtmModels.Role admin = named(roles, "admin");
        Pivot pivot = pivotOf(admin);

        assertEquals(1, intAttribute(pivot, "user_id"));
        assertEquals(1, intAttribute(pivot, "role_id"));
        assertEquals("active", pivot.getAttribute("status"));
        assertFalse(admin.hasAttribute("pivot_status"));
        assertNoPivotPrefixedAttributes(admin);
    }

    @Test
    void realColumnIsStolen() {
        db.real.statement("alter table roles add column pivot_note varchar(255)");
        db.real.statement("update roles set pivot_note = 'kept' where id = 1");

        BtmModels.Role admin = named(user(1).roles().get(), "admin");

        assertEquals("kept", pivotOf(admin).getAttribute("note"));
        assertFalse(admin.hasAttribute("pivot_note"));
    }

    @Test
    void userAliasIsStolen() {
        ModelCollection<BtmModels.Role> roles = user(1).roles()
                .select("roles.name", "role_user.weight as pivot_foo").get();

        BtmModels.Role admin = named(roles, "admin");

        assertEquals(5, intAttribute(pivotOf(admin), "foo"));
        assertFalse(admin.hasAttribute("pivot_foo"));
    }

    @Test
    void modelNotDirty() {
        BtmModels.Role admin = named(user(1).roles().get(), "admin");

        assertFalse(admin.isDirty());
        assertTrue(admin.save());
    }

    @Test
    void saveNeverWritesPivotColumns() {
        BtmModels.Role admin = named(user(1).roles().get(), "admin");
        admin.setAttribute(ROLE_NAME, "owner");

        assertTrue(admin.save());

        BtmModels.Role reread = BtmModels.Role.QUERY.on(db.db).findOrFail(1);
        assertEquals("owner", reread.getAttribute(ROLE_NAME));
    }

    @Test
    void toMapNoPrefixedKeys() {
        BtmModels.Role admin = named(user(1).roles().get(), "admin");

        Map<String, Object> map = admin.toMap();

        assertTrue(map.containsKey("pivot"));
        for (String key : map.keySet()) {
            assertFalse(key.startsWith("pivot_"), "no pivot_ key in the map: " + key);
        }
    }

    @Test
    void pivotState() {
        BtmModels.User user = user(1);
        BtmModels.Role admin = named(user.roles().get(), "admin");

        Pivot pivot = pivotOf(admin);

        assertTrue(pivot.exists());
        assertFalse(pivot.isDirty());
        assertEquals("role_user", pivot.getTable());
        assertEquals(user.getConnectionName(), pivot.getConnectionName());
        assertSame(user, pivot.getPivotParent());
    }

    @Test
    void emptyPivotWhenNoAliases() {
        BtmModels.User user = user(1);
        BtmModels.Role admin = BtmModels.Role.QUERY.on(db.db).findOrFail(1);

        new PivotHydrator(user.roles(), user).hydrate(admin);

        assertNotNull(pivotOf(admin));
        assertTrue(pivotOf(admin).getAttributes().isEmpty());
    }

    @Test
    void customPivotClass() {
        ModelCollection<BtmModels.Role> roles = user(1).memberships().get();

        BtmModels.Role admin = named(roles, "admin");

        assertTrue(pivotOf(admin) instanceof BtmModels.Membership);
        assertEquals(1, intAttribute(pivotOf(admin), "user_id"));
    }

    @Test
    void customPivotCastsOnRead() {
        CastOwner owner = CastOwner.QUERY.on(db.db).findOrFail(1);
        CastMembership pivot = (CastMembership) pivotOf(named(owner.roles().get(), "admin"));

        assertEquals(5, ((Number) pivot.getRawOriginal("weight")).intValue());
        assertEquals(Integer.valueOf(5), pivot.getInteger("weight"));
        assertFalse(pivot.isDirty());
    }

    @Test
    void attributeNeverFallsBack() {
        BtmModels.Role admin = named(user(1).roles().get(), "admin");

        assertNull(admin.getAttribute("pivot"));
        assertFalse(admin.hasAttribute("pivot"));
    }

    @Test
    void oneInstancePerJoinedRow() {
        db.seed("role_user", Map.of("user_id", 1, "role_id", 1, "status", "trial", "weight", 9));

        List<BtmModels.Role> admins = allNamed(user(1).roles().get(), "admin");

        assertEquals(2, admins.size());
        assertNotSame(admins.get(0), admins.get(1));
        assertNotSame(pivotOf(admins.get(0)), pivotOf(admins.get(1)));
        assertEquals(new HashSet<>(Arrays.asList("active", "trial")), new HashSet<>(Arrays.asList(
                pivotOf(admins.get(0)).getAttribute("status"), pivotOf(admins.get(1)).getAttribute("status"))));
    }

    @Test
    void twoPivotRowsGiveTwoInstances() {
        db.seed("role_user", Map.of("user_id", 1, "role_id", 1, "status", "trial", "weight", 9));
        List<BtmModels.Role> admins = allNamed(user(1).roles().get(), "admin");
        Pivot first = pivotOf(admins.get(0));
        Pivot second = pivotOf(admins.get(1));
        Object secondStatus = second.getAttribute("status");

        first.setAttribute("status", "changed");

        assertNotSame(first, second);
        assertEquals(secondStatus, second.getAttribute("status"));
    }

    @Test
    void noPivotColumnsGivesEmptyPivot() {
        BtmModels.Role admin = named(user(1).rolesByDefault().get(), "admin");

        Pivot pivot = pivotOf(admin);

        assertTrue(pivot.exists());
        assertFalse(pivot.hasAttribute("status"));
        assertFalse(pivot.hasAttribute("weight"));
    }

    @Test
    void pivotXColumnOfRelatedTableIsMoved() {
        db.real.statement("alter table roles add column pivot_x varchar(255)");
        db.real.statement("update roles set pivot_x = 'x-value' where id = 1");
        BtmModels.User user = user(1);

        user.load("roles");
        BtmModels.Role admin = named(user.relatedMany("roles"), "admin");

        assertEquals("x-value", pivotOf(admin).getAttribute("x"));
        assertFalse(admin.hasAttribute("pivot_x"));
    }

    @Test
    void settingsSnapshotIgnoresLaterChanges() {
        BtmModels.User user = user(1);
        BelongsToMany<BtmModels.Role> roles = user.roles();
        PivotHydrator hydrator = new PivotHydrator(roles, user);

        roles.as("membership");
        BtmModels.Role admin = BtmModels.Role.QUERY.on(db.db).findOrFail(1);
        hydrator.hydrate(admin);

        assertEquals("membership", roles.getPivotAccessor());
        assertEquals("pivot", hydrator.accessor());
        assertTrue(admin.relationLoaded("pivot"));
        assertFalse(admin.relationLoaded("membership"));
    }

    @Test
    void chaperoneLinksSetOnEveryRead() {
        BtmModels.User user = user(1);
        ModelCollection<BtmModels.Role> roles = user.memberships().chaperone().get();

        BtmModels.Role admin = named(roles, "admin");
        Pivot pivot = pivotOf(admin);

        assertSame(user, pivot.getRelation("user"));
        assertSame(admin, pivot.getRelation("role"));
    }

    @Test
    void chaperoneLinksSetOnEagerRead() {
        CastOwner owner = CastOwner.QUERY.on(db.db).findOrFail(1);
        owner.load("chaperoned");

        BtmModels.Role admin = named(owner.relatedMany("chaperoned"), "admin");
        Pivot pivot = pivotOf(admin);

        assertSame(owner, pivot.getRelation("user"));
        assertSame(admin, pivot.getRelation("role"));
        assertSame(owner, pivot.getPivotParent());
    }

    @Test
    void addsNoQuery() {
        BtmModels.User user = user(1);

        user.roles().get();

        assertEquals(1, db.queries().size());
    }

    @Test
    void accessorReplacesLoadedRelationOfSameName() {
        BtmModels.User user = user(1);
        PivotHydrator hydrator = new PivotHydrator(user.roles(), user);
        BtmModels.Role admin = BtmModels.Role.QUERY.on(db.db).findOrFail(1);
        Model stale = BtmModels.Role.QUERY.on(db.db).findOrFail(2);
        admin.setRelation("pivot", stale);

        hydrator.hydrate(admin);

        Pivot pivot = hydrator.pivotOf(admin);
        assertNotNull(pivot);
        assertNotSame(stale, pivot);
        assertSame(pivot, admin.getRelation("pivot"));
    }

    /** A pivot whose {@code weight} column is cast to an integer on read. */
    static final class CastMembership extends Pivot {
        static final ModelQuery<CastMembership> QUERY = ModelQuery.of(CastMembership.class, CastMembership::new);

        public CastMembership() {
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
        protected Map<String, Cast<?>> casts() {
            return Map.of("weight", Casts.integer());
        }
    }

    /** A user whose role relations carry the cast pivot class and the chaperone links. */
    static final class CastOwner extends Model {
        static final ModelQuery<CastOwner> QUERY = ModelQuery.of(CastOwner.class, CastOwner::new);

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
            return RelationMap.of(CastOwner.class)
                    .add("roles", CastOwner::roles)
                    .add("chaperoned", CastOwner::chaperoned);
        }

        public BelongsToMany<BtmModels.Role> roles() {
            return belongsToMany(BtmModels.Role.class, CastMembership.class, "user_id", "role_id")
                    .withPivot("weight");
        }

        public BelongsToMany<BtmModels.Role> chaperoned() {
            return belongsToMany(BtmModels.Role.class, BtmModels.Membership.class, "user_id", "role_id")
                    .chaperone();
        }
    }
}
