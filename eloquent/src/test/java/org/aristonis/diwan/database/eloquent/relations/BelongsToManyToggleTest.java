package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The toggle of a belongsToMany relation: each given id is detached when linked and attached when
 * not. Each test opens its own database and asserts the join table rows and the returned
 * {@link SyncResult}.
 */
class BelongsToManyToggleTest {

    private static final String LINKED_ROLES = "select role_id from role_user where user_id = ? order by role_id";
    private static final String LINK_ROW = "select status, weight from role_user where user_id = ? and role_id = ?";

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
    void toggleFlipsLinkedAndUnlinkedIds() {
        // Arrange: user 1 is linked to roles 1 and 2

        // Act
        SyncResult result = rolesOf(1).toggle(Arrays.asList(2, 3));

        // Assert: 2 was linked and is detached, 3 was not and is attached, nothing is updated
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        assertEquals(Arrays.<Object>asList(2L), result.getDetached());
        assertTrue(result.getUpdated().isEmpty());
        assertEquals(Arrays.asList(1L, 3L), linkedRoles(db.real, 1));
    }

    @Test
    void toggleKeepsPerIdAttributesOnAttach() {
        // Arrange: user 1 is linked to roles 1 and 2; role 3 is not linked yet
        Map<Object, Object> ids = new LinkedHashMap<>();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("status", "new");
        attributes.put("weight", 7);
        ids.put(3, attributes);

        // Act
        SyncResult result = rolesOf(1).toggle(ids);

        // Assert
        assertEquals(Arrays.<Object>asList(3L), result.getAttached());
        Map<String, Object> row = linkRow(db.real, 1, 3);
        assertEquals("new", row.get("status"));
        assertEquals(7, ((Number) row.get("weight")).intValue());
    }

    @Test
    void toggleRollsBackWhenAttachFails() {
        // Arrange: inserting role 3 is refused, after the detach of role 2 has run
        db.real.unprepared("create trigger refuse_role_three before insert on role_user "
                + "when new.role_id = 3 begin select raise(abort, 'refused'); end");

        // Act and Assert
        assertThrows(QueryException.class, () -> rolesOf(1).toggle(Arrays.asList(2, 3)));
        assertEquals(Arrays.asList(1L, 2L), linkedRoles(db.real, 1));
    }

    @Test
    void toggleResultKeysAreCast() {
        // Arrange: user 1 is linked to roles 1 and 2

        // Act
        SyncResult result = rolesOf(1).toggle(Arrays.asList("3", "2"));

        // Assert: the string ids come back as the integer key type
        List<Object> attached = new ArrayList<>(result.getAttached());
        assertEquals(Arrays.<Object>asList(3L), attached);
        assertEquals(Arrays.<Object>asList(2L), result.getDetached());
        assertEquals(Arrays.asList(1L, 3L), linkedRoles(db.real, 1));
    }

    private BelongsToMany<TsRole> rolesOf(int userId) {
        return TsUser.QUERY.on(db.db).findOrFail(userId).roles();
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
}
