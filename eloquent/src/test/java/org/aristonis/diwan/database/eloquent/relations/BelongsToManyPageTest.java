package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.CursorModelPage;
import org.aristonis.diwan.database.eloquent.ModelPage;
import org.aristonis.diwan.database.eloquent.SimpleModelPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The page reads of a belongsToMany relation: offset pages with a total, simple pages and cursor
 * pages. Each page carries its pivot, the total count drops the select list, and a cursor page refuses
 * an order on a pivot-table column.
 */
class BelongsToManyPageTest {

    private static final String ALIASES = "\"role_user\".\"user_id\" as \"pivot_user_id\", "
            + "\"role_user\".\"role_id\" as \"pivot_role_id\", "
            + "\"role_user\".\"status\" as \"pivot_status\", "
            + "\"role_user\".\"weight\" as \"pivot_weight\", "
            + "\"role_user\".\"created_at\" as \"pivot_created_at\", "
            + "\"role_user\".\"updated_at\" as \"pivot_updated_at\"";

    private static final String JOIN = " from \"roles\" inner join \"role_user\" on "
            + "\"roles\".\"id\" = \"role_user\".\"role_id\"";

    private static final String USER_1_WHERE = " where \"role_user\".\"user_id\" = ?";

    private static final String ROLES_SELECT = "select \"roles\".*, " + ALIASES + JOIN + USER_1_WHERE;

    private static final String COUNT_SQL = "select count(*) as \"aggregate\"" + JOIN + USER_1_WHERE;

    private static final String FIRST_PAGE_SQL = ROLES_SELECT + " limit 1 offset 0";

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
    void paginate() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("roles.id");

        ModelPage<BtmModels.Role> page = roles.paginate(1);

        assertEquals(Collections.singletonList(1), page.items().modelKeys());
        assertHasPivot(page.items().get(0));
        assertEquals(2L, page.total());
        assertTrue(page.hasMorePages());
    }

    @Test
    void countQueryShape() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        db.clearLog();

        roles.paginate(1);

        assertEquals(Arrays.asList(COUNT_SQL, FIRST_PAGE_SQL), db.sql());
    }

    @Test
    void pastTheEnd() {
        ModelPage<BtmModels.Role> page = user(1).roles().paginate(1, 3);

        assertTrue(page.items().isEmpty());
        assertEquals(2L, page.total());
    }

    @Test
    void simplePaginate() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("roles.id");
        db.clearLog();

        SimpleModelPage<BtmModels.Role> page = roles.simplePaginate(1);

        assertEquals(Collections.singletonList(1), page.items().modelKeys());
        assertHasPivot(page.items().get(0));
        assertTrue(page.hasMorePages());
        assertEquals(1, db.sql().size());
    }

    @Test
    void appendSelect() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().select("roles.name as role_name");
        db.clearLog();

        roles.paginate(5);

        String page = "select \"roles\".\"name\" as \"role_name\", \"roles\".*, " + ALIASES + JOIN + USER_1_WHERE
                + " limit 5 offset 0";
        assertEquals(Arrays.asList(COUNT_SQL, page), db.sql());
    }

    @Test
    void repeatedCallSameSql() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        db.clearLog();

        roles.paginate(1);
        roles.paginate(1);

        assertEquals(Arrays.asList(COUNT_SQL, FIRST_PAGE_SQL, COUNT_SQL, FIRST_PAGE_SQL), db.sql());
    }

    @Test
    void cursorPaginateRelatedKey() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("roles.id");

        CursorModelPage<BtmModels.Role> first = roles.cursorPaginate(1);
        CursorModelPage<BtmModels.Role> second = roles.cursorPaginate(1, first.nextCursor().get());

        assertEquals(Collections.singletonList(1), first.items().modelKeys());
        assertHasPivot(first.items().get(0));
        assertTrue(first.nextCursor().isPresent());
        assertEquals(Collections.singletonList(2), second.items().modelKeys());
        assertHasPivot(second.items().get(0));
        assertFalse(second.nextCursor().isPresent());
    }

    @Test
    void cursorPaginateWithoutOrderOrdersByRelatedKey() {
        CursorModelPage<BtmModels.Role> page = user(1).roles().cursorPaginate(5);

        assertEquals(Arrays.asList(1, 2), page.items().modelKeys());
    }

    @Test
    void cursorPivotOrderRefused() {
        BelongsToMany<?> roles = (BelongsToMany<?>) user(1).relation("roles");
        roles.orderByPivot("weight");
        db.clearLog();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> roles.cursorPaginate(1));

        assertTrue(failure.getMessage().contains("relation [roles]"), failure.getMessage());
        assertTrue(failure.getMessage().contains("role_user.weight"), failure.getMessage());
        assertTrue(failure.getMessage().contains("pivot_weight"), failure.getMessage());
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void cursorPivotOrderRefusedWhateverTheCase() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("ROLE_USER.weight");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> roles.cursorPaginate(1));

        assertTrue(failure.getMessage().contains("ROLE_USER.weight"), failure.getMessage());
    }

    @Test
    void cursorPivotOrderRefusedNamesPivotTableWithoutRelationName() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("role_user.weight");
        db.clearLog();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> roles.cursorPaginate(2));

        assertTrue(failure.getMessage().contains("relation [role_user]"), failure.getMessage());
        assertFalse(failure.getMessage().contains("[null]"), failure.getMessage());
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void cursorPivotOrderRefusedWhenQuoted() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("\"role_user\".\"weight\"");
        db.clearLog();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> roles.cursorPaginate(1));

        assertTrue(failure.getMessage().contains("pivot column [role_user.weight]"), failure.getMessage());
        assertFalse(failure.getMessage().contains("\""), failure.getMessage());
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void cursorPaginateRefusesPivotOrder() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("role_user.weight");
        db.clearLog();

        assertThrows(IllegalStateException.class, () -> roles.cursorPaginate());

        assertTrue(db.sql().isEmpty());
    }

    @Test
    void cursorPaginateWithCursorRefusesPivotOrder() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("role_user.weight");
        db.clearLog();

        assertThrows(IllegalStateException.class, () -> roles.cursorPaginate(1, null));

        assertTrue(db.sql().isEmpty());
    }

    @Test
    void cursorPaginateOrdersByPivotAlias() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("pivot_weight");

        CursorModelPage<BtmModels.Role> first = roles.cursorPaginate(1);
        CursorModelPage<BtmModels.Role> second = roles.cursorPaginate(1, first.nextCursor().get());

        assertEquals(Collections.singletonList(2), first.items().modelKeys());
        assertEquals("pending", Pivot.of(first.items().get(0)).getAttribute("status"));
        assertTrue(first.nextCursor().isPresent());
        assertEquals(Collections.singletonList(1), second.items().modelKeys());
        assertEquals("active", Pivot.of(second.items().get(0)).getAttribute("status"));
        assertFalse(second.nextCursor().isPresent());
    }

    private BtmModels.User user(int id) {
        return BtmModels.User.QUERY.on(db.db).findOrFail(id);
    }

    private static void assertHasPivot(BtmModels.Role role) {
        assertNotNull(Pivot.of(role));
    }
}
