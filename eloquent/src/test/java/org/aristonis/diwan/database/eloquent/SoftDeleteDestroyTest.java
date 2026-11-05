package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Model destroy and forceDestroy on soft-deleting and plain models, with their SQL and transactions. */
class SoftDeleteDestroyTest {

    private static final String MOMENT = "2026-11-05 10:15:30";
    private static final String USER_SOFT_DELETE = "update \"users\" set \"deleted_at\" = ?, \"updated_at\" = ?"
            + " where \"id\" = ?";
    private static final String POST_HARD_DELETE = "delete from \"posts\" where \"id\" = ?";
    private static final String TAG_HARD_DELETE = "delete from \"tags\" where \"id\" = ?";

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures.close();
    }

    private long count(String table, String where) {
        List<Map<String, Object>> rows = fixtures.real.select(
                "select count(*) as c from " + table + " where " + where, Collections.emptyList());
        return ((Number) rows.get(0).get("c")).longValue();
    }

    private static Set<String> keysBound(List<Statement> writes) {
        Set<String> keys = new HashSet<>();
        for (Statement write : writes) {
            List<Object> bindings = write.bindings();
            keys.add(String.valueOf(bindings.get(bindings.size() - 1)));
        }
        return keys;
    }

    // ---- destroy: soft delete per live model ----

    @Test
    void destroyRunsASoftDeleteForEachLiveModel() {
        int deleted = fixtures.users().destroy(1, 2);

        assertEquals(2, deleted);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(2, writes.size());
        for (Statement write : writes) {
            assertEquals(USER_SOFT_DELETE, write.sql());
            assertEquals(MOMENT, write.bindings().get(0));
            assertEquals(MOMENT, write.bindings().get(1));
        }
        assertEquals(new HashSet<>(Arrays.asList("1", "2")), keysBound(writes));
        assertEquals(1, count("users", "\"id\" = 3 and \"deleted_at\" is null"));
    }

    @Test
    void destroyStampsOneMomentInDeletedAtAndUpdatedAt() {
        fixtures.users().destroy(2);

        assertEquals(1, count("users", "\"id\" = 2 and \"deleted_at\" is not null"));
        assertEquals(1, count("users", "\"id\" = 2 and \"deleted_at\" like '" + MOMENT + "%'"));
        assertEquals(1, count("users", "\"id\" = 2 and \"updated_at\" like '" + MOMENT + "%'"));
    }

    @Test
    void destroyWithNoIdsRunsNoQuery() {
        int deleted = fixtures.users().destroy();

        assertEquals(0, deleted);
        assertTrue(fixtures.log.all().isEmpty());
    }

    @Test
    void destroyOfOnlyTrashedKeysReturnsZero() {
        int deleted = fixtures.posts().destroy(4);

        assertEquals(0, deleted);
        assertEquals(1, count("posts", "\"id\" = 4 and \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT + "'"));
        assertTrue(fixtures.log.writes().isEmpty());
    }

    @Test
    void destroyDoesNotReachAnotherTenant() {
        int deleted = fixtures.posts().destroy(5);

        assertEquals(0, deleted);
        assertEquals(1, count("posts", "\"id\" = 5 and \"deleted_at\" is null"));
    }

    @Test
    void destroyOfNonSoftModelDeletesRowsForGood() {
        int deleted = fixtures.tags().destroy(1);

        assertEquals(1, deleted);
        assertEquals(0, count("tags", "\"id\" = 1"));
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals(TAG_HARD_DELETE, writes.get(0).sql());
    }

    // ---- forceDestroy: hard delete, trashed rows included ----

    @Test
    void forceDestroyDeletesTrashedRowsForGood() {
        int deleted = fixtures.posts().forceDestroy(1, 4);

        assertEquals(2, deleted);
        assertEquals(0, count("posts", "\"id\" in (1, 4)"));
        assertEquals(4, count("posts", "\"id\" > 0"));
        List<Statement> writes = fixtures.log.writes();
        assertEquals(2, writes.size());
        for (Statement write : writes) {
            assertEquals(POST_HARD_DELETE, write.sql());
        }
        assertEquals(new HashSet<>(Arrays.asList("1", "4")), keysBound(writes));
    }

    @Test
    void forceDestroyDoesNotReachAnotherTenant() {
        int deleted = fixtures.posts().forceDestroy(5);

        assertEquals(0, deleted);
        assertEquals(1, count("posts", "\"id\" = 5"));
    }

    // ---- transactions: a rolled-back callback leaves every row as it was ----

    @Test
    void destroyInsideRolledBackTransactionKeepsLiveRows() {
        assertThrows(IllegalStateException.class, () -> fixtures.db.connection().transaction(c -> {
            int deleted = fixtures.users().on(c).destroy(1, 2);
            assertEquals(2, deleted);
            throw new IllegalStateException("roll back");
        }));

        assertEquals(3, count("users", "\"deleted_at\" is null"));
    }

    @Test
    void forceDestroyInsideRolledBackTransactionKeepsTheRow() {
        assertThrows(IllegalStateException.class, () -> fixtures.db.connection().transaction(c -> {
            int deleted = fixtures.posts().on(c).forceDestroy(4);
            assertEquals(1, deleted);
            throw new IllegalStateException("roll back");
        }));

        assertEquals(1, count("posts", "\"id\" = 4 and \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT + "'"));
    }
}
