package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The detach write of a belongsToMany relation: the recorded pivot conditions, the parent key, the
 * ids given, and the chunking of a large id list. Rows are checked in the database; the nested
 * condition is checked as SQL because its grouping is the point.
 */
class BelongsToManyDetachTest {

    private static final String NESTED_DELETE = "delete from \"role_user\" where "
            + "(\"role_user\".\"status\" = ? or \"role_user\".\"weight\" = ?) and \"role_user\".\"user_id\" = ?";

    private static final int LARGE_ID_COUNT = 1000;
    private static final int MAX_BINDINGS = 999;
    private static final int OTHER_USER = 2;

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
    void detachWithEmptyListRunsNoQuery() {
        // Arrange
        TsUser owner = user(1);
        db.queryLog().clear();

        // Act
        int removed = owner.roles().detach(Collections.emptyList());

        // Assert
        assertEquals(0, removed);
        assertTrue(db.queryLog().sql().isEmpty());
        assertTrue(db.queryLog().writes().isEmpty());
    }

    @Test
    void detachNestsOrPivotWheresBeforeParentKey() {
        // Arrange
        TsUser owner = user(1);
        db.queryLog().clear();

        // Act
        int removed = owner.roles().wherePivot("status", "active").orWherePivot("weight", 1).detach();

        // Assert
        assertEquals(2, removed);
        assertEquals(Arrays.asList(NESTED_DELETE), db.queryLog().writes());
        assertEquals(Arrays.asList(3), db.queryLog().writeBindingCounts());
        assertEquals(1, count("select count(*) from role_user where user_id = " + OTHER_USER));
    }

    @Test
    void detachWithoutIdsRemovesAllParentRows() {
        // Arrange
        TsUser owner = user(1);

        // Act
        int removed = owner.roles().detach();

        // Assert
        assertEquals(2, removed);
        assertEquals(0, count("select count(*) from role_user where user_id = 1"));
        assertEquals(1, count("select count(*) from role_user where user_id = " + OTHER_USER));
    }

    @Test
    void detachOfUnlinkedIdReturnsZero() {
        // Arrange
        TsUser other = user(OTHER_USER);

        // Act
        int removed = other.roles().detach(2);

        // Assert
        assertEquals(0, removed);
        assertEquals(1, count("select count(*) from role_user where user_id = " + OTHER_USER));
    }

    @Test
    void detachChunksLargeIdLists() {
        // Arrange
        int userWithoutRoles = 3;
        for (int roleId = 1; roleId <= LARGE_ID_COUNT; roleId++) {
            link(userWithoutRoles, roleId);
        }
        TsUser owner = user(userWithoutRoles);
        db.queryLog().clear();

        // Act
        int removed = owner.roles().detach(range(1, LARGE_ID_COUNT));

        // Assert: the chunks are summed, so every linked row is counted once.
        assertEquals(LARGE_ID_COUNT, removed);
        assertTrue(db.queryLog().writeBindingCounts().size() > 1);
        for (int count : db.queryLog().writeBindingCounts()) {
            assertTrue(count <= MAX_BINDINGS);
        }
        assertEquals(0, count("select count(*) from role_user where user_id = " + userWithoutRoles));
    }

    private TsUser user(int id) {
        return TsUser.QUERY.on(db.db).findOrFail(id);
    }

    private void link(int userId, int roleId) {
        db.real.insert("insert into role_user (user_id, role_id, status, weight) values (?, ?, ?, ?)",
                userId, roleId, "active", 1);
    }

    private static List<Object> range(int from, int to) {
        List<Object> ids = new ArrayList<>();
        for (int id = from; id <= to; id++) {
            ids.add(id);
        }
        return ids;
    }

    private int count(String sql) {
        return ((Number) db.real.scalar(sql).orElseThrow()).intValue();
    }
}
