package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Writes of a scoped query on SQLite: the tenant and soft-delete conditions reach every statement. */
class SQLiteScopesTest {

    private static final String MOMENT = "2026-11-05 10:15:30";
    private static final String POSTS_LIVE_TENANT = "\"posts\".\"deleted_at\" is null and \"tenant_id\" = ?";

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

    @Test
    void updateCarriesTheScope() {
        int rows = fixtures.posts().query().update(Collections.singletonMap("title", "x"));

        assertEquals(3, rows);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals("update \"posts\" set \"title\" = ?, \"updated_at\" = ? where " + POSTS_LIVE_TENANT,
                writes.get(0).sql());
        assertEquals(Arrays.<Object>asList("x", MOMENT, ScopeFixtures.TENANT_ID), writes.get(0).bindings());
        assertEquals(1, count("posts", "\"id\" = 5 and \"title\" = 'Post 5'"));
    }

    @Test
    void deleteSoftDeletesOnlyTheScopedRows() {
        int rows = fixtures.posts().query().delete();

        assertEquals(3, rows);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals("update \"posts\" set \"deleted_at\" = ?, \"updated_at\" = ? where " + POSTS_LIVE_TENANT,
                writes.get(0).sql());
        assertEquals(Arrays.<Object>asList(MOMENT, MOMENT, ScopeFixtures.TENANT_ID), writes.get(0).bindings());
        assertEquals(2, count("posts", "\"tenant_id\" = 8 and \"deleted_at\" is null"));
        assertEquals(1, count("posts", "\"id\" = 4 and \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT + "'"));
    }

    @Test
    void incrementCarriesTheScope() {
        int rows = fixtures.posts().query().increment("views");

        assertEquals(3, rows);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals("update \"posts\" set \"views\" = \"views\" + 1, \"updated_at\" = ? where " + POSTS_LIVE_TENANT,
                writes.get(0).sql());
        assertEquals(1, count("posts", "\"id\" = 1 and \"views\" = 1"));
        assertEquals(1, count("posts", "\"id\" = 6 and \"views\" = 0"));
    }

    @Test
    void updateSkipsTrashedRowsOutsideTheScope() {
        int rows = fixtures.posts().query().whereKey(4).update(Collections.singletonMap("title", "x"));

        assertEquals(0, rows);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).sql().contains("\"posts\".\"deleted_at\" is null"), writes.get(0).sql());
        assertEquals(1, count("posts", "\"id\" = 4 and \"title\" = 'Post 4'"));
    }

    @Test
    void updateCannotReachAnotherTenantsRows() {
        int rows = fixtures.posts().query().whereKey(5).update(Collections.singletonMap("title", "x"));

        assertEquals(0, rows);
        assertEquals(1, count("posts", "\"id\" = 5 and \"title\" = 'Post 5'"));
    }
}
