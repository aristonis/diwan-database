package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The updateExistingPivot write of a belongsToMany relation: the values set, the update timestamp,
 * the ids and recorded pivot conditions that select the rows, and the disabled timestamp column.
 * Rows are checked in the database; the statement SQL is checked where the set list is the point.
 */
class BelongsToManyUpdateExistingPivotTest {

    private static final String SEEDED_UPDATED_AT = "2024-01-01 11:00:00";
    private static final int LARGE_ID_COUNT = 1000;
    private static final int MAX_BINDINGS = 999;
    private static final String WEIGHT_AND_TIMESTAMP_UPDATE = "update \"role_user\" set \"weight\" = ?, "
            + "\"updated_at\" = ? where \"role_user\".\"user_id\" = ? and \"role_user\".\"role_id\" in (?)";
    private static final String WEIGHT_UPDATE = "update \"role_user\" set \"weight\" = ? "
            + "where \"role_user\".\"user_id\" = ? and \"role_user\".\"role_id\" in (?)";

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
    void updateWritesAttributesAndUpdatedAt() {
        // Arrange
        TsUser owner = user(1);
        db.queryLog().clear();

        // Act
        int updated = owner.roles().updateExistingPivot(2, attrs("weight", 8));

        // Assert
        assertEquals(1, updated);
        assertEquals(Arrays.asList(WEIGHT_AND_TIMESTAMP_UPDATE), db.queryLog().writes());
        assertEquals(8, intAt("select weight from role_user where user_id = 1 and role_id = 2"));
        assertNotEquals(SEEDED_UPDATED_AT,
                textAt("select updated_at from role_user where user_id = 1 and role_id = 2"));
    }

    @Test
    void updateWithEmptyAttributesRunsNoQuery() {
        // Arrange: friends keeps no timestamps, so an empty set has nothing to write.
        BtmModels.User owner = BtmModels.User.QUERY.on(db.db).findOrFail(1);
        db.queryLog().clear();

        // Act
        int updated = owner.friends().updateExistingPivot(2, Collections.<String, Object>emptyMap());

        // Assert
        assertEquals(0, updated);
        assertTrue(db.queryLog().writes().isEmpty());
    }

    @Test
    void updateOfUnlinkedIdReturnsZero() {
        // Arrange
        TsUser other = user(2);

        // Act
        int updated = other.roles().updateExistingPivot(2, attrs("weight", 8));

        // Assert
        assertEquals(0, updated);
        assertEquals(0, intAt("select count(*) from role_user where user_id = 2 and role_id = 2"));
    }

    @Test
    void updateAcceptsSeveralIds() {
        // Arrange
        TsUser owner = user(1);
        db.queryLog().clear();

        // Act
        int updated = owner.roles().updateExistingPivot(Arrays.asList(1, 2), attrs("weight", 8));

        // Assert
        assertEquals(2, updated);
        assertEquals(1, db.queryLog().writes().size());
        assertEquals(2, intAt("select count(*) from role_user where user_id = 1 and weight = 8"));
    }

    @Test
    void updateRespectsRecordedPivotWheres() {
        // Arrange: role 2 is pending, so the active condition hides it.
        TsUser owner = user(1);

        // Act
        int updated = owner.roles().wherePivot("status", "active").updateExistingPivot(2, attrs("weight", 8));

        // Assert
        assertEquals(0, updated);
        assertEquals(1, intAt("select weight from role_user where user_id = 1 and role_id = 2"));
    }

    @Test
    void updateSkipsDisabledUpdatedColumn() {
        // Arrange
        TsUser owner = user(1);
        db.queryLog().clear();

        // Act
        owner.roles().withTimestamps("created_at", "").updateExistingPivot(2, attrs("weight", 8));

        // Assert
        assertEquals(Arrays.asList(WEIGHT_UPDATE), db.queryLog().writes());
        assertEquals(SEEDED_UPDATED_AT,
                textAt("select updated_at from role_user where user_id = 1 and role_id = 2"));
    }

    @Test
    void updateStatementBindingsStayWithinBudget() {
        // Arrange: 1,000 links, so the ids go in more than one chunk.
        int userWithRoles = 3;
        for (int roleId = 1; roleId <= LARGE_ID_COUNT; roleId++) {
            link(userWithRoles, roleId);
        }
        TsUser owner = user(userWithRoles);
        db.queryLog().clear();

        // Act
        int updated = owner.roles().updateExistingPivot(range(1, LARGE_ID_COUNT), attrs("weight", 4));

        // Assert
        assertEquals(LARGE_ID_COUNT, updated);
        assertTrue(db.queryLog().writeBindingCounts().size() > 1);
        for (int count : db.queryLog().writeBindingCounts()) {
            assertTrue(count <= MAX_BINDINGS);
        }
        assertEquals(LARGE_ID_COUNT, intAt("select count(*) from role_user where user_id = ? and weight = 4",
                userWithRoles));
    }

    private TsUser user(int id) {
        return TsUser.QUERY.on(db.db).findOrFail(id);
    }

    private void link(int userId, int roleId) {
        db.real.insert("insert into role_user (user_id, role_id, status, weight, updated_at) "
                + "values (?, ?, ?, ?, ?)", userId, roleId, "active", 1, SEEDED_UPDATED_AT);
    }

    private static Map<String, Object> attrs(String column, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(column, value);
        return map;
    }

    private static List<Object> range(int from, int to) {
        List<Object> ids = new ArrayList<>();
        for (int id = from; id <= to; id++) {
            ids.add(id);
        }
        return ids;
    }

    private int intAt(String sql, Object... bindings) {
        return ((Number) db.real.scalar(sql, bindings).orElseThrow()).intValue();
    }

    private String textAt(String sql) {
        return String.valueOf(db.real.scalar(sql).orElseThrow());
    }
}
