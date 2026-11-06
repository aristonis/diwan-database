package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.eloquent.exception.MassAssignmentException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The related-side writes of a belongsToMany relation: saving, creating and finding related roles
 * from the user's side, with the link rows and the owner touches they produce. Each test opens its
 * own database and reads the statements the write ran from the query log.
 */
class BelongsToManyRelatedWritesTest {

    private static final String ROLES_INSERT = "insert into \"roles\"";
    private static final String ROLES_UPDATE = "update \"roles\"";
    private static final String ROLE_USER_INSERT = "insert into \"role_user\"";
    private static final String ROLE_USER_UPDATE = "update \"role_user\"";
    private static final String RACING_LINK =
            "insert into \"role_user\" (\"user_id\", \"role_id\", \"status\", \"weight\") values (3, 1, 'active', 1)";

    private BtmWriteDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmWriteDb.open();
        registerLocalModels();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void saveManyTouchesOnce() {
        // Arrange
        LocalUser user = localUser(3);
        List<LocalRole> roles = Arrays.asList(newRole("r-a"), newRole("r-b"), newRole("r-c"));
        db.queryLog().clear();

        // Act
        user.roles().saveMany(roles, pivots(3));

        // Assert
        assertEquals(3, scalarCount("select count(*) from role_user where user_id = ?", 3));
        assertEquals(1, countStarting(db.queryLog().writes(), ROLES_UPDATE));
    }

    @Test
    void saveManyOfEmptyListTouchesNothing() {
        // Arrange
        LocalUser user = localUser(3);
        db.queryLog().clear();

        // Act
        List<LocalRole> saved = user.roles().saveMany(Collections.<LocalRole>emptyList(),
                Collections.<Map<String, ?>>emptyList());

        // Assert
        assertTrue(saved.isEmpty());
        assertEquals(0, countStarting(db.queryLog().writes(), ROLES_UPDATE));
    }

    @Test
    void saveManyWithEveryModelVetoedTouchesNothing() {
        // Arrange
        LocalUser user = localUser(3);
        List<LocalRole> roles = Arrays.asList(newRole("blocked"), newRole("blocked"));
        db.queryLog().clear();

        // Act
        user.roles().saveMany(roles, pivots(2));

        // Assert
        assertEquals(0, scalarCount("select count(*) from role_user where user_id = 3"));
        assertEquals(0, countStarting(db.queryLog().writes(), ROLES_UPDATE));
    }

    @Test
    void saveInsertsRelatedThenAttaches() {
        // Arrange
        LocalUser user = localUser(3);
        LocalRole role = newRole("auditor");
        db.queryLog().clear();

        // Act
        user.roles().save(role, attrs("status", "active", "weight", 2));

        // Assert
        // The model insert reads its new key back with "returning", so it is logged as a select.
        assertTrue(db.queryLog().sql().get(0).startsWith(ROLES_INSERT));
        assertTrue(db.queryLog().writes().get(0).startsWith(ROLE_USER_INSERT));
        assertTrue(role.exists());
        assertEquals("active", db.real.scalar("select status from role_user where user_id = ? and role_id = ?",
                3, role.getAttribute("id")).orElseThrow());
    }

    @Test
    void saveOfExistingRelatedAddsLink() {
        // Arrange
        LocalUser user = localUser(2);
        LocalRole editor = LocalRole.QUERY.on(db.db).findOrFail(2);
        editor.setAttribute("name", "editors");
        db.queryLog().clear();

        // Act
        user.roles().save(editor, attrs("status", "pending", "weight", 1));

        // Assert
        assertEquals(1, scalarCount("select count(*) from roles where name = ?", "editors"));
        assertEquals(1, scalarCount("select count(*) from role_user where user_id = 2 and role_id = 2"));
        assertEquals(0, countStarting(db.queryLog().writes(), ROLES_INSERT));
    }

    @Test
    void saveVetoedByHookWritesNoLink() {
        // Arrange
        LocalUser user = localUser(1);
        LocalRole blocked = newRole("blocked");
        db.queryLog().clear();

        // Act
        user.roles().save(blocked, attrs("status", "active", "weight", 1));

        // Assert
        assertFalse(blocked.exists());
        assertEquals(2, scalarCount("select count(*) from role_user where user_id = 1"));
        assertEquals(0, scalarCount("select count(*) from roles where name = ?", "blocked"));
        assertEquals(0, countStarting(db.queryLog().writes(), ROLE_USER_INSERT));
    }

    @Test
    void saveManyRefusesMismatchedAttributeList() {
        // Arrange
        LocalUser user = localUser(3);
        List<LocalRole> roles = Arrays.asList(newRole("r-a"), newRole("r-b"));
        db.queryLog().clear();

        // Act
        assertThrows(IllegalArgumentException.class,
                () -> user.roles().saveMany(roles, pivots(1)));

        // Assert
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(2, scalarCount("select count(*) from roles"));
    }

    @Test
    void createRefusesGuardedAttribute() {
        // Arrange
        TsUser user = tsUser(3);
        db.queryLog().clear();

        // Act and Assert
        assertThrows(MassAssignmentException.class, () -> user.roles().create(attrs("name", "x")));
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(2, scalarCount("select count(*) from roles"));
    }

    @Test
    void createManyRollsBackAllRecordsOnFailure() {
        // Arrange
        db.createRoleNameUniqueIndex();
        LocalUser user = localUser(3);
        List<Map<String, ?>> records = Arrays.asList(attrs("name", "fresh"), attrs("name", "admin"));

        // Act and Assert
        assertThrows(UniqueConstraintViolationException.class,
                () -> user.roles().createMany(records, pivots(2)));
        assertEquals(0, scalarCount("select count(*) from roles where name = ?", "fresh"));
        assertEquals(0, scalarCount("select count(*) from role_user where user_id = 3"));
    }

    @Test
    void writesOnUnsavedParentAreRefused() {
        // Arrange
        LocalUser unsaved = LocalUser.QUERY.on(db.db).newModel();
        LocalRole role = newRole("x");
        db.queryLog().clear();

        // Act and Assert
        assertThrows(IllegalStateException.class, () -> unsaved.roles().save(role, attrs("status", "active")));
        assertThrows(IllegalStateException.class,
                () -> unsaved.roles().create(attrs("name", "y"), attrs("status", "active")));
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(2, scalarCount("select count(*) from roles"));
    }

    @Test
    void findOrNewReturnsUnsavedModelWhenMissing() {
        // Arrange
        TsUser user = tsUser(1);
        db.queryLog().clear();

        // Act
        TsRole fresh = user.roles().findOrNew(99);

        // Assert
        assertFalse(fresh.exists());
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(2, scalarCount("select count(*) from role_user where user_id = 1"));
    }

    @Test
    void firstOrNewSearchesWholeRelatedTable() {
        // Arrange
        TsUser user = tsUser(3);
        db.queryLog().clear();

        // Act
        TsRole found = user.roles().firstOrNew(attrs("name", "admin"));

        // Assert
        assertTrue(found.exists());
        assertEquals("admin", found.getAttribute("name"));
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(0, scalarCount("select count(*) from role_user where user_id = 3"));
    }

    @Test
    void firstOrCreateReturnsLinkedMatch() {
        // Arrange
        LocalUser user = localUser(1);
        db.queryLog().clear();

        // Act
        LocalRole role = user.roles().firstOrCreate(attrs("name", "admin"));

        // Assert
        assertEquals("admin", role.getAttribute("name"));
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(2, scalarCount("select count(*) from role_user where user_id = 1"));
    }

    @Test
    void firstOrCreateAttachesUnlinkedMatch() {
        // Arrange
        LocalUser user = localUser(3);
        db.queryLog().clear();

        // Act
        LocalRole role = user.roles().firstOrCreate(attrs("name", "admin"), attrs(),
                attrs("status", "active", "weight", 1));

        // Assert
        assertEquals("admin", role.getAttribute("name"));
        assertNull(Pivot.of(role));
        assertEquals(2, scalarCount("select count(*) from roles"));
        assertEquals(1, scalarCount("select count(*) from role_user where user_id = 3 and role_id = 1"));
    }

    @Test
    void firstOrCreateSwallowsRacingAttachViolation() {
        // Arrange
        db.createLinkUniqueIndex();
        boolean[] raced = {false};
        QueryFactory racing = SQLiteQuery.of(racingConnection(db.db.connection(), raced));
        LocalUser user = LocalUser.QUERY.on(racing).findOrFail(3);

        // Act
        LocalRole role = user.roles().firstOrCreate(attrs("name", "admin"), attrs(),
                attrs("status", "active", "weight", 1));

        // Assert
        assertTrue(raced[0]);
        assertEquals("admin", role.getAttribute("name"));
        assertEquals(1, scalarCount("select count(*) from role_user where user_id = 3 and role_id = 1"));
    }

    @Test
    void firstOrCreateRethrowsViolationWhenLinkIsAbsent() {
        // Arrange: the unique index is on user and status, and user 3 already holds an active link to role 2
        SQLiteSchema.of(db.real).table("role_user", t -> t.unique("user_id", "status"));
        db.real.statement("insert into role_user (user_id, role_id, status, weight) values (3, 2, 'active', 1)");
        LocalUser user = localUser(3);

        // Act and Assert: role 1 matches but linking it as active breaks the index, and the link is not there
        assertThrows(UniqueConstraintViolationException.class, () -> user.roles().firstOrCreate(
                attrs("name", "admin"), attrs(), attrs("status", "active", "weight", 1)));
        assertEquals(0, scalarCount("select count(*) from role_user where user_id = 3 and role_id = 1"));
    }

    @Test
    void firstOrCreateCreatesAndAttachesWhenNoMatch() {
        // Arrange
        LocalUser user = localUser(3);

        // Act
        LocalRole role = user.roles().firstOrCreate(attrs("name", "fresh"), attrs(),
                attrs("status", "active", "weight", 1));

        // Assert
        assertTrue(role.exists());
        assertEquals(1, scalarCount("select count(*) from roles where name = ?", "fresh"));
        assertEquals(1, scalarCount("select count(*) from role_user where user_id = 3 and role_id = ?",
                role.getAttribute("id")));
    }

    @Test
    void updateOrCreateSavesFoundModelWithoutTouch() {
        // Arrange
        LocalUser user = localUser(1);
        db.queryLog().clear();

        // Act
        LocalRole role = user.roles().updateOrCreate(attrs("name", "admin"), attrs("name", "admins"),
                attrs("status", "pending", "weight", 9));

        // Assert
        assertEquals("admins", role.getAttribute("name"));
        assertEquals(1, countStarting(db.queryLog().writes(), ROLES_UPDATE));
        assertEquals(0, countStarting(db.queryLog().writes(), ROLE_USER_UPDATE));
        assertEquals("active", db.real.scalar("select status from role_user where user_id = 1 and role_id = 1")
                .orElseThrow());
    }

    @Test
    void updateOrCreateCreatesWithPivotAttributes() {
        // Arrange
        LocalUser user = localUser(3);

        // Act
        LocalRole role = user.roles().updateOrCreate(attrs("name", "newbie"), attrs("name", "newbie"),
                attrs("status", "pending", "weight", 2));

        // Assert
        assertTrue(role.wasRecentlyCreated());
        assertEquals("pending", db.real.scalar("select status from role_user where user_id = 3 and role_id = ?",
                role.getAttribute("id")).orElseThrow());
    }

    @Test
    void createAttachFailureLeavesNoOrphanRelatedRow() {
        // Arrange
        LocalUser user = localUser(3);

        // Act and Assert
        assertThrows(QueryException.class,
                () -> user.roles().create(attrs("name", "orphan"), attrs("no_such_column", 1)));
        assertEquals(0, scalarCount("select count(*) from roles where name = ?", "orphan"));
        assertEquals(0, scalarCount("select count(*) from role_user where user_id = 3"));
    }

    // ---- helpers ----

    private static void registerLocalModels() {
        if (LocalUser.QUERY == null || LocalRole.QUERY == null) {
            throw new IllegalStateException("a local model is not initialised");
        }
    }

    private LocalUser localUser(int id) {
        return LocalUser.QUERY.on(db.db).findOrFail(id);
    }

    private TsUser tsUser(int id) {
        return TsUser.QUERY.on(db.db).findOrFail(id);
    }

    private LocalRole newRole(String name) {
        LocalRole role = LocalRole.QUERY.on(db.db).newModel();
        role.setAttribute("name", name);
        return role;
    }

    private int scalarCount(String sql, Object... bindings) {
        return ((Number) db.real.scalar(sql, bindings).orElseThrow()).intValue();
    }

    private static int countStarting(List<String> statements, String prefix) {
        int count = 0;
        for (String statement : statements) {
            if (statement.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static List<Map<String, ?>> pivots(int size) {
        List<Map<String, ?>> pivots = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            pivots.add(attrs("status", "active", "weight", i));
        }
        return pivots;
    }

    private static Map<String, Object> attrs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // Wraps the connection so the first link insert finds the pair already linked by a racing request.
    private Connection racingConnection(Connection inner, boolean[] raced) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (isLinkInsert(method, args) && !raced[0]) {
                        raced[0] = true;
                        db.real.statement(RACING_LINK);
                    }
                    try {
                        return method.invoke(inner, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static boolean isLinkInsert(Method method, Object[] args) {
        return "insert".equals(method.getName()) && args != null && args[0] instanceof String
                && ((String) args[0]).startsWith(ROLE_USER_INSERT);
    }

    /** A user that touches its roles whenever a link changes. */
    static final class LocalUser extends Model {
        static final ModelQuery<LocalUser> QUERY = ModelQuery.of(LocalUser.class, LocalUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(LocalUser.class).add("roles", LocalUser::roles);
        }

        @Override
        protected Set<String> touches() {
            return Collections.singleton("roles");
        }

        public BelongsToMany<LocalRole> roles() {
            return belongsToMany(LocalRole.class, "role_user", "user_id", "role_id")
                    .withPivot("status", "weight")
                    .withTimestamps();
        }
    }

    /** A role that accepts mass assignment of its name and refuses to save when named blocked. */
    static final class LocalRole extends Model {
        static final ModelQuery<LocalRole> QUERY = ModelQuery.of(LocalRole.class, LocalRole::new);

        @Override
        protected String table() {
            return "roles";
        }

        @Override
        protected List<String> fillable() {
            return Collections.singletonList("name");
        }

        @Override
        protected boolean saving() {
            return !"blocked".equals(getAttribute("name"));
        }
    }
}
