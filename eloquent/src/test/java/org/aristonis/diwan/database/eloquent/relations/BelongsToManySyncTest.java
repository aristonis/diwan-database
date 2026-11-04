package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sync family of a belongsToMany relation: which ids are detached, attached and updated, how
 * ids compare, and how the writes behave on failure or inside an outer transaction. Each test opens
 * its own database and asserts the join table rows and the returned {@link SyncResult}.
 */
class BelongsToManySyncTest {

    private static final String LINKED_ROLES = "select role_id from role_user where user_id = ? order by role_id";
    private static final String LINK_ROW = "select status, weight from role_user where user_id = ? and role_id = ?";
    private static final String BUSY_TIMEOUT_OPTION = "?busy_timeout=5000";
    private static final int RACE_THREADS = 2;
    private static final int RACE_TIMEOUT_SECONDS = 30;
    private static final int RACER_USER = 3;

    private BtmWriteDb db;

    @TempDir
    Path dir;

    @BeforeEach
    void open() throws SQLException {
        db = BtmWriteDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void syncDetachesIdsMissingFromTheList() {
        // Arrange: user 1 is linked to roles 1 and 2

        // Act
        SyncResult result = rolesOf(1).sync(Arrays.asList(2, 3));

        // Assert
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertEquals(Arrays.<Object>asList(1L), result.getDetached());
        assertTrue(result.getUpdated().isEmpty());
        assertEquals(Arrays.asList(2L, 3L), linkedRoles(db.real, 1));
    }

    @Test
    void syncComparesStringAndIntegerKeysAsEqual() {
        // Arrange: user 1 is linked to roles 1 and 2

        // Act
        SyncResult result = rolesOf(1).sync(Arrays.asList("1", "3"));

        // Assert
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertEquals(Arrays.<Object>asList(2L), result.getDetached());
        assertEquals(Arrays.asList(1L, 3L), linkedRoles(db.real, 1));
    }

    @Test
    void syncUpdatesAttributesOfExistingLinks() {
        // Arrange: user 1 is linked to role 1 (active, 5) and role 2
        Map<Object, Object> ids = new LinkedHashMap<>();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("status", "done");
        attributes.put("weight", 9);
        ids.put(1, attributes);

        // Act
        SyncResult result = rolesOf(1).sync(ids);

        // Assert
        assertEquals(Arrays.<Object>asList(1L), result.getUpdated());
        assertEquals(Arrays.<Object>asList(2L), result.getDetached());
        assertTrue(result.getAttached().isEmpty());
        Map<String, Object> row = linkRow(db.real, 1, 1);
        assertEquals("done", row.get("status"));
        assertEquals(9, ((Number) row.get("weight")).intValue());
    }

    @Test
    void syncWithEmptyAttributesDoesNotUpdate() {
        // Arrange: user 1 is linked to role 1 (active, 5)

        // Act
        SyncResult result = rolesOf(1).sync(Collections.singletonList(1));

        // Assert
        assertTrue(result.getUpdated().isEmpty());
        assertEquals("active", linkRow(db.real, 1, 1).get("status"));
    }

    @Test
    void syncWithEmptyListDetachesAll() {
        // Arrange: user 1 is linked to roles 1 and 2

        // Act
        SyncResult result = rolesOf(1).sync(Collections.emptyList());

        // Assert
        assertEquals(Arrays.<Object>asList(1L, 2L), sorted(result.getDetached()));
        assertTrue(result.getAttached().isEmpty());
        assertTrue(linkedRoles(db.real, 1).isEmpty());
    }

    @Test
    void syncWithoutDetachingOnEmptyListRunsNoQuery() {
        // Arrange: resolve the parent before the log is cleared, so its find is not counted
        BelongsToMany<TsRole> roles = rolesOf(1);
        db.queryLog().clear();

        // Act
        SyncResult result = roles.syncWithoutDetaching(Collections.emptyList());

        // Assert
        assertTrue(result.isEmpty());
        assertTrue(db.queryLog().sql().isEmpty());
        assertTrue(db.queryLog().writes().isEmpty());
    }

    @Test
    void syncWithoutDetachingKeepsOtherLinks() {
        // Arrange: user 1 is linked to roles 1 and 2

        // Act
        SyncResult result = rolesOf(1).syncWithoutDetaching(Collections.singletonList(3));

        // Assert
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertTrue(result.getDetached().isEmpty());
        assertEquals(Arrays.asList(1L, 2L, 3L), linkedRoles(db.real, 1));
    }

    @Test
    void syncWithPivotValuesAppliesSameValuesToAll() {
        // Arrange: user 1 is linked to roles 1 and 2
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", "archived");

        // Act
        SyncResult result = rolesOf(1).syncWithPivotValues(Arrays.asList(1, 3), values);

        // Assert
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertEquals(Arrays.<Object>asList(1L), result.getUpdated());
        assertEquals(Arrays.<Object>asList(2L), result.getDetached());
        assertEquals("archived", linkRow(db.real, 1, 1).get("status"));
        assertEquals("archived", linkRow(db.real, 1, 3).get("status"));
    }

    @Test
    void syncIgnoresLinksHiddenByPivotWheres() {
        // Arrange: role 2 is linked to user 1 as pending, so the active-only relation cannot see it
        BelongsToMany<TsRole> activeOnly = rolesOf(1).wherePivot("status", "active");

        // Act
        SyncResult result = activeOnly.sync(Collections.emptyList());

        // Assert
        assertEquals(Arrays.<Object>asList(1L), result.getDetached());
        assertEquals(Arrays.asList(2L), linkedRoles(db.real, 1));
    }

    @Test
    void syncAttachesNewIdsInOneInsert() {
        // Arrange: user 3 has no roles

        // Act
        SyncResult result = rolesOf(RACER_USER).sync(Arrays.asList(1, 2));

        // Assert
        assertEquals(Arrays.<Object>asList(1L, 2L), result.getAttached());
        assertEquals(Arrays.asList(1L, 2L), linkedRoles(db.real, RACER_USER));
    }

    @Test
    void syncRefusesDottedPivotColumn() {
        // Arrange: user 3 has no links, so role 1 would be attached with the dotted column
        Map<Object, Object> ids = new LinkedHashMap<>();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("role_user.status", "done");
        ids.put(1, attributes);
        BelongsToMany<TsRole> roles = rolesOf(RACER_USER);

        // Act and Assert
        assertThrows(IllegalArgumentException.class, () -> roles.sync(ids));
        assertTrue(linkedRoles(db.real, RACER_USER).isEmpty());
    }

    @Test
    void syncRollsBackWhenAttachFails() {
        // Arrange: inserting role 3 is refused, after the detach of role 1 has run
        refuseRoleThree(db.real);

        // Act and Assert
        assertThrows(QueryException.class, () -> rolesOf(1).sync(Arrays.asList(2, 3)));
        assertEquals(Arrays.asList(1L, 2L), linkedRoles(db.real, 1));
    }

    @Test
    void syncInsideOuterTransactionUsesSavepoint() {
        // Arrange: refuse role 3, so the sync fails after its detach of role 1
        refuseRoleThree(db.real);

        // Act: the outer transaction attaches role 2 to user 2 before the refused sync
        db.real.transaction(c -> {
            TsUser.QUERY.on(SQLiteQuery.of(c)).findOrFail(2).roles().attach(2);
            try {
                TsUser.QUERY.on(SQLiteQuery.of(c)).findOrFail(1).roles().sync(Arrays.asList(2, 3));
            } catch (QueryException refused) {
                // Expected: the sync's savepoint is rolled back and the outer transaction carries on.
            }
            return null;
        });

        // Assert: the sync's detach is undone, the outer attach is kept
        assertEquals(Arrays.asList(1L, 2L), linkedRoles(db.real, 1));
        assertEquals(Arrays.asList(1L, 2L), linkedRoles(db.real, 2));
    }

    @Test
    void syncOnRootBoundParentInStrictModeSucceeds() {
        // Arrange: the parent is bound to the root connection, outside any transaction

        // Act
        SyncResult result = rolesOf(RACER_USER).sync(Collections.singletonList(2));

        // Assert
        assertEquals(Arrays.<Object>asList(2L), result.getAttached());
        assertEquals(Arrays.asList(2L), linkedRoles(db.real, RACER_USER));
    }

    @Test
    void concurrentSyncEndsInSuccessOrCleanUniqueViolation() throws Exception {
        // Arrange: a file database with a unique link index and user 3 as the only user
        Connection shared = fileConnection(dir.resolve("race.db"));
        createRaceSchema(shared);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", RACER_USER);
        user.put("name", "User 3");
        SQLiteQuery.of(shared).table("users").insert(user);
        // Each racer has its own connection, as two requests would.
        QueryFactory firstRacer = SQLiteQuery.of(fileConnection(dir.resolve("race.db")));
        QueryFactory secondRacer = SQLiteQuery.of(fileConnection(dir.resolve("race.db")));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACE_THREADS);
        try {
            // Act
            Future<Object> first = pool.submit(() -> racingSync(firstRacer, start));
            Future<Object> second = pool.submit(() -> racingSync(secondRacer, start));
            start.countDown();

            // Assert: each racer succeeds or fails cleanly, and the link table holds exactly one row per role
            assertTrue(isCleanOutcome(first.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
            assertTrue(isCleanOutcome(second.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
            assertEquals(Arrays.asList(1L, 2L), linkedRoles(shared, RACER_USER));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object racingSync(QueryFactory race, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return TsUser.QUERY.on(race).findOrFail(RACER_USER).roles().sync(Arrays.asList(1, 2));
        } catch (QueryException refused) {
            return refused;
        }
    }

    private static boolean isCleanOutcome(Object outcome) {
        return outcome instanceof SyncResult || outcome instanceof QueryException;
    }

    private BelongsToMany<TsRole> rolesOf(int userId) {
        return TsUser.QUERY.on(db.db).findOrFail(userId).roles();
    }

    private static void refuseRoleThree(Connection connection) {
        connection.unprepared("create trigger refuse_role_three before insert on role_user "
                + "when new.role_id = 3 begin select raise(abort, 'refused'); end");
    }

    private static void createRaceSchema(Connection connection) {
        SQLiteSchema.of(connection).create("users", t -> {
            t.id();
            t.string("name");
        });
        SQLiteSchema.of(connection).create("role_user", t -> {
            t.integer("user_id");
            t.integer("role_id");
            t.string("status").nullable();
            t.integer("weight").nullable();
            t.timestamp("created_at").nullable();
            t.timestamp("updated_at").nullable();
        });
        SQLiteSchema.of(connection).table("role_user", t -> t.unique("user_id", "role_id"));
    }

    private static Connection fileConnection(Path file) {
        JdbcConnectionProvider provider = new JdbcConnectionProvider() {
            @Override
            public java.sql.Connection acquire() throws SQLException {
                return DriverManager.getConnection("jdbc:sqlite:" + file + BUSY_TIMEOUT_OPTION);
            }

            @Override
            public void release(java.sql.Connection connection) throws SQLException {
                connection.close();
            }
        };
        return Connection.builder().name("sqlite").connectionProvider(provider).build();
    }

    private static List<Long> linkedRoles(Connection connection, int userId) {
        List<Long> roles = new ArrayList<>();
        for (Map<String, Object> row : connection.select(LINKED_ROLES, userId)) {
            roles.add(((Number) row.get("role_id")).longValue());
        }
        return roles;
    }

    private static Map<String, Object> linkRow(Connection connection, int userId, int roleId) {
        return connection.selectOne(LINK_ROW, userId, roleId)
                .orElseThrow(() -> new IllegalStateException("no link for user " + userId + " role " + roleId));
    }

    private static List<Object> sorted(List<Object> keys) {
        List<Object> copy = new ArrayList<>(keys);
        copy.sort((a, b) -> Long.compare(((Number) a).longValue(), ((Number) b).longValue()));
        return copy;
    }
}
