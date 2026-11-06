package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsRole;
import org.aristonis.diwan.database.eloquent.relations.BtmWriteModels.TsUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The attach write of a belongsToMany relation: the rows written for each id, their column order and
 * timestamps, the per-id attributes, the statement chunks, and the refusals. Rows are checked in the
 * database; the statement SQL is checked where the column order is the point.
 */
class BelongsToManyAttachTest {

    private static final int MAX_BINDINGS = 999;

    private static final String TIMESTAMP = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}";

    private static final String ROLE_USER_INSERT = "insert into \"role_user\" (\"role_id\", \"user_id\", "
            + "\"created_at\", \"updated_at\", \"status\") values (?, ?, ?, ?, ?)";

    private static final String USER_USER_INSERT = "insert into \"user_user\" (\"friend_id\", \"user_id\", "
            + "\"status\") values (?, ?, ?)";

    private static final String COUNTRY_LANGUAGE_INSERT = "insert into \"country_language\" "
            + "(\"language_iso\", \"country_code\") values (?, ?)";

    private static final int LINKED_ROLE = 2;
    private static final int FIRST_NEW_ROLE = 101;
    private static final int LAST_NEW_ROLE = 300;

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
    void attachWithNoIdsRunsNoQuery() {
        // Arrange
        TsUser owner = user(1);
        db.queryLog().clear();

        // Act
        owner.roles().attach(Collections.emptyList());

        // Assert
        assertTrue(db.queryLog().sql().isEmpty());
        assertTrue(db.queryLog().writes().isEmpty());
    }

    @Test
    void attachRefusesModelWithoutKey() {
        // Arrange
        TsUser owner = user(1);
        TsRole unsaved = TsRole.QUERY.on(db.db).newModel();
        db.queryLog().clear();

        // Act
        assertThrows(IllegalArgumentException.class, () -> owner.roles().attach(unsaved));

        // Assert
        assertTrue(db.queryLog().writes().isEmpty());
    }

    @Test
    void attachOnUnsavedParentIsRefused() {
        // Arrange
        TsUser unsaved = TsUser.QUERY.on(db.db).newModel();
        db.queryLog().clear();

        // Act
        assertThrows(IllegalStateException.class, () -> unsaved.roles().attach(1));

        // Assert
        assertTrue(db.queryLog().writes().isEmpty());
    }

    @Test
    void attachWritesKeysAttributesAndTimestamps() {
        // Arrange
        TsUser owner = user(1);
        db.queryLog().clear();

        // Act
        owner.roles().attach(3, attrs("status", "active"));

        // Assert
        assertEquals(Arrays.asList(ROLE_USER_INSERT), db.queryLog().writes());
        assertEquals(Arrays.asList(5), db.queryLog().writeBindingCounts());
        Map<String, Object> row = db.real.selectOne(
                "select status, created_at, updated_at from role_user where user_id = ? and role_id = ?", 1, 3)
                .orElseThrow();
        assertEquals("active", row.get("status"));
        assertEquals(row.get("updated_at"), row.get("created_at"));
        assertTrue(String.valueOf(row.get("created_at")).matches(TIMESTAMP));
    }

    @Test
    void attachWritesDeclaredPivotValues() {
        // Arrange
        TsUser owner = user(1);

        // Act
        owner.roles().withPivotValue("weight", 7).attach(3);

        // Assert
        assertEquals(7, intAt("select weight from role_user where user_id = 1 and role_id = 3"));
    }

    @Test
    void attachSkipsDisabledTimestampColumn() {
        // Arrange
        TsUser owner = user(1);

        // Act
        owner.roles().withTimestamps("", "updated_at").attach(3, attrs("status", "active"));

        // Assert
        Map<String, Object> row = db.real.selectOne(
                "select created_at, updated_at from role_user where user_id = ? and role_id = ?", 1, 3)
                .orElseThrow();
        assertNull(row.get("created_at"));
        assertTrue(String.valueOf(row.get("updated_at")).matches(TIMESTAMP));
    }

    @Test
    void sharedAttributesWinOverPerIdAttributes() {
        // Arrange
        TsUser owner = user(1);
        Map<Integer, Map<String, Object>> perId = new LinkedHashMap<>();
        perId.put(5, attrs("weight", 2));

        // Act
        owner.roles().attach(perId, attrs("weight", 9));

        // Assert
        assertEquals(9, intAt("select weight from role_user where user_id = 1 and role_id = 5"));
    }

    @Test
    void attachRefusesDifferentColumnSetsPerRow() {
        // Arrange
        TsUser owner = user(1);
        Map<Integer, Map<String, Object>> perId = new LinkedHashMap<>();
        perId.put(5, attrs("weight", 2));
        perId.put(6, attrs("status", "active"));
        db.queryLog().clear();

        // Act
        assertThrows(IllegalArgumentException.class,
                () -> owner.roles().attach(perId, Collections.<String, Object>emptyMap()));

        // Assert
        assertTrue(db.queryLog().writes().isEmpty());
        assertEquals(2, count("select count(*) from role_user where user_id = 1"));
    }

    @Test
    void attachSplitsInsertByBindingBudget() {
        // Arrange
        BtmModels.User owner = BtmModels.User.QUERY.on(db.db).findOrFail(1);
        db.queryLog().clear();

        // Act
        owner.friends().attach(range(101, 500), attrs("status", "active"));

        // Assert: friends has three columns per row, so 300 rows per statement; 400 rows land in two chunks.
        assertEquals(401, count("select count(*) from user_user where user_id = 1"));
        assertEquals(2, db.queryLog().writeBindingCounts().size());
        for (int count : db.queryLog().writeBindingCounts()) {
            assertTrue(count <= MAX_BINDINGS);
        }
    }

    @Test
    void attachRollsBackAllChunksWhenOneFails() {
        // Arrange
        db.createLinkUniqueIndex();
        TsUser owner = user(1);

        // Act
        assertThrows(UniqueConstraintViolationException.class,
                () -> owner.roles().attach(idsEndingWithLinkedRole(), attrs("status", "active")));

        // Assert: the first chunk was written and then rolled back with the failing one.
        assertEquals(0, count("select count(*) from role_user where role_id = " + FIRST_NEW_ROLE));
        assertEquals(2, count("select count(*) from role_user where user_id = 1"));
    }

    @Test
    void attachExistingIdBreaksUniqueIndex() {
        // Arrange
        db.createLinkUniqueIndex();
        TsUser owner = user(1);

        // Act
        assertThrows(UniqueConstraintViolationException.class,
                () -> owner.roles().attach(LINKED_ROLE, attrs("status", "active")));

        // Assert
        assertEquals(2, count("select count(*) from role_user where user_id = 1"));
    }

    @Test
    void attachOnSelfRelationUsesDeclaredKeys() {
        // Arrange
        BtmModels.User owner = BtmModels.User.QUERY.on(db.db).findOrFail(1);
        db.queryLog().clear();

        // Act
        owner.friends().attach(3, attrs("status", "active"));

        // Assert
        assertEquals(Arrays.asList(USER_USER_INSERT), db.queryLog().writes());
        assertEquals(1, count("select count(*) from user_user where user_id = 1 and friend_id = 3"));
    }

    @Test
    void attachUsesCustomParentAndRelatedKeys() {
        // Arrange
        BtmModels.Country france = BtmModels.Country.QUERY.on(db.db).findOrFail("FR");
        BtmModels.Language english = BtmModels.Language.QUERY.on(db.db).findOrFail("en");
        db.queryLog().clear();

        // Act
        france.languages().attach(english);

        // Assert
        assertEquals(Arrays.asList(COUNTRY_LANGUAGE_INSERT), db.queryLog().writes());
        assertEquals(1, count("select count(*) from country_language where country_code = 'FR' "
                + "and language_iso = 'en'"));
    }

    @Test
    void attachInsideCallerTransactionUsesSavepoint() {
        // Arrange
        db.createLinkUniqueIndex();
        boolean[] refused = {false};

        // Act
        db.real.transaction(tx -> {
            TsUser owner = TsUser.QUERY.on(SQLiteQuery.of(tx)).findOrFail(1);
            try {
                owner.roles().attach(idsEndingWithLinkedRole(), attrs("status", "active"));
            } catch (UniqueConstraintViolationException expected) {
                refused[0] = true;
            }
            owner.roles().attach(777, attrs("status", "active"));
            return null;
        });

        // Assert: the failed chunks rolled back to the savepoint, and the caller's later write committed.
        assertTrue(refused[0]);
        assertEquals(0, count("select count(*) from role_user where role_id = " + FIRST_NEW_ROLE));
        assertEquals(1, count("select count(*) from role_user where role_id = 777"));
        assertEquals(3, count("select count(*) from role_user where user_id = 1"));
    }

    @Test
    void attachOnRootBoundParentInsideTransactionIsRefused() {
        // Arrange
        TsUser owner = user(1);

        // Act
        assertThrows(IllegalStateException.class, () -> db.real.transaction(tx -> {
            owner.roles().attach(3, attrs("status", "active"));
            return null;
        }));

        // Assert
        assertEquals(2, count("select count(*) from role_user where user_id = 1"));
    }

    @Test
    void attachRefusesDottedAttributeKey() {
        // Arrange
        TsUser owner = user(1);
        db.queryLog().clear();

        // Act
        assertThrows(IllegalArgumentException.class,
                () -> owner.roles().attach(3, attrs("pivot.status", "active")));

        // Assert
        assertTrue(db.queryLog().writes().isEmpty());
    }

    private TsUser user(int id) {
        return TsUser.QUERY.on(db.db).findOrFail(id);
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

    private static List<Object> idsEndingWithLinkedRole() {
        List<Object> ids = range(FIRST_NEW_ROLE, LAST_NEW_ROLE);
        ids.add(LINKED_ROLE);
        return ids;
    }

    private int count(String sql) {
        return intAt(sql);
    }

    private int intAt(String sql, Object... bindings) {
        return ((Number) db.real.scalar(sql, bindings).orElseThrow()).intValue();
    }
}
