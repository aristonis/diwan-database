package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Refreshing a role that holds its pivot: the pivot is kept as it is, and the other loaded
 * relations are read again.
 */
class RefreshSkipsPivotTest {

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
    void refreshKeepsThePivotAndReloadsOtherLoadedRelations() {
        BtmModels.Role role = firstRoleOfUserOne();
        role.load("permissions");
        Object pivot = role.getRelation("pivot");
        int permissionsBefore = role.relatedMany("permissions").size();
        seedPermissionForRoles(3, 1, 2);

        role.refresh();

        assertSame(pivot, role.getRelation("pivot"));
        assertEquals(permissionsBefore + 1, role.relatedMany("permissions").size());
    }

    @Test
    void refreshWithOnlyThePivotLoadedReturnsTheSameModel() {
        BtmModels.Role role = firstRoleOfUserOne();

        assertSame(role, role.refresh());
    }

    private BtmModels.Role firstRoleOfUserOne() {
        BtmModels.Role role = BtmModels.User.QUERY.on(db.db).findOrFail(1).roles().get().get(0);
        assertTrue(role.relationLoaded("pivot"));
        return role;
    }

    private void seedPermissionForRoles(int permissionId, int... roleIds) {
        db.seed("permissions", Map.of("id", permissionId, "name", "Permission " + permissionId));
        for (int roleId : roleIds) {
            db.seed("permission_role", Map.of("permission_id", permissionId, "role_id", roleId));
        }
    }
}
