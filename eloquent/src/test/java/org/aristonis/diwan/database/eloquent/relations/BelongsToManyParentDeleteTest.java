package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deleting the parent of a belongsToMany relation does not cascade: its pivot rows stay until the
 * caller detaches them.
 */
class BelongsToManyParentDeleteTest {

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private long linksOfUser(int userId) {
        return ((Number) db.real.selectOne("select count(*) as c from role_user where user_id = ?", userId)
                .orElseThrow().get("c")).longValue();
    }

    @Test
    void deletingTheParentLeavesItsPivotRows() {
        // Arrange
        User user = User.QUERY.on(db.db).findOrFail(1);

        // Act
        boolean deleted = user.delete();

        // Assert
        assertTrue(deleted);
        assertEquals(2, linksOfUser(1));
    }

    @Test
    void detachingBeforeTheDeleteClearsThePivotRows() {
        // Arrange
        User user = User.QUERY.on(db.db).findOrFail(1);

        // Act
        user.roles().detach();
        user.delete();

        // Assert
        assertEquals(0, linksOfUser(1));
    }

    @Test
    void deletingTheParentLeavesOtherParentsLinks() {
        // Arrange
        User user = User.QUERY.on(db.db).findOrFail(1);

        // Act
        user.delete();

        // Assert
        assertEquals(1, linksOfUser(2));
    }
}
