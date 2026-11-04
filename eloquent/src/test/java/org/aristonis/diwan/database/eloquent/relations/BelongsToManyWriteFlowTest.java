package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A realistic sequence of belongsToMany writes on one user: attach, sync with pivot values, sync
 * again and detach, checking the rows and the {@link SyncResult} each step returns.
 */
class BelongsToManyWriteFlowTest {

    private static final long USER_ID = 3L;

    private BtmWriteDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmWriteDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void attachSyncUpdateDetachRoundTrip() {
        // Arrange
        TsUser user = TsUser.QUERY.on(db.db).findOrFail(USER_ID);
        TsRole viewer = TsRole.QUERY.on(db.db).newModel();
        viewer.setAttribute("name", "viewer");
        viewer.save();
        long viewerId = ((Number) viewer.getAttribute("id")).longValue();

        // Act and Assert: attach two roles
        user.roles().attach(Arrays.asList(1, 2), attrs("status", "pending", "weight", 1));
        assertEquals(2, rowCount());

        // Act and Assert: sync to roles 2 and the viewer, changing pivot values on the kept role
        SyncResult synced = user.roles().syncWithPivotValues(Arrays.asList(2L, viewerId),
                attrs("status", "active", "weight", 9));
        assertEquals(Arrays.<Object>asList(viewerId), synced.getAttached());
        assertEquals(Arrays.<Object>asList(1L), synced.getDetached());
        assertEquals(Arrays.<Object>asList(2L), synced.getUpdated());
        assertEquals("active", pivotStatus(2L));
        assertEquals(9, pivotWeight(2L));

        // Act and Assert: sync to the viewer alone, which detaches role 2 and changes nothing else
        SyncResult trimmed = user.roles().sync(Arrays.asList(viewerId));
        assertEquals(Arrays.<Object>asList(2L), trimmed.getDetached());
        assertTrue(trimmed.getAttached().isEmpty());
        assertTrue(trimmed.getUpdated().isEmpty());

        // Act and Assert: detach everything that is left
        assertEquals(1, user.roles().detach());
        assertEquals(0, rowCount());
    }

    private int rowCount() {
        return ((Number) db.real.scalar("select count(*) from role_user where user_id = ?", USER_ID)
                .orElseThrow()).intValue();
    }

    private Object pivotStatus(long roleId) {
        return db.real.scalar("select status from role_user where user_id = ? and role_id = ?", USER_ID, roleId)
                .orElseThrow();
    }

    private int pivotWeight(long roleId) {
        return ((Number) db.real.scalar("select weight from role_user where user_id = ? and role_id = ?",
                USER_ID, roleId).orElseThrow()).intValue();
    }

    private static Map<String, Object> attrs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
