package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Role;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lookup half of {@code firstOrCreate} and {@code updateOrCreate} on a many-to-many relation is
 * scoped: a trashed related row is not a match, unless the relation asks for trashed rows.
 */
class PivotWriteScopeTest {

    private static final String EDITOR = "editor";

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

    private static Map<String, Object> named(String name) {
        return Collections.<String, Object>singletonMap("name", name);
    }

    private long count(String sql) {
        List<Map<String, Object>> rows = fixtures.real.select(sql, Collections.emptyList());
        return ((Number) rows.get(0).get("c")).longValue();
    }

    private static String keyOf(Role role) {
        return String.valueOf(role.getKey());
    }

    @Test
    void linkedLookupSkipsATrashedRelatedRow() {
        User ann = fixtures.users().findOrFail(1);

        Role created = ann.roles().firstOrCreate(named(EDITOR));

        assertNotEquals("2", keyOf(created));
        assertTrue(created.wasRecentlyCreated());
        assertEquals(2L, count("select count(*) as c from roles where name = 'editor'"));
        assertEquals(1L, count("select count(*) as c from role_user where user_id = 1 and role_id = "
                + keyOf(created)));
    }

    @Test
    void wholeTableLookupSkipsATrashedRelatedRow() {
        User ben = fixtures.users().findOrFail(2);

        Role created = ben.roles().firstOrCreate(named(EDITOR));

        assertNotEquals("2", keyOf(created));
        assertEquals(0L, count("select count(*) as c from role_user where user_id = 2 and role_id = 2"));
        assertEquals(2L, count("select count(*) as c from roles where name = 'editor'"));
    }

    @Test
    void relationThatKeepsTrashedRowsFindsTheTrashedMatch() {
        User ann = fixtures.users().findOrFail(1);

        Role found = ann.roles().withTrashed().firstOrCreate(named(EDITOR));

        assertEquals("2", keyOf(found));
        assertEquals(1L, count("select count(*) as c from roles where name = 'editor'"));
    }

    @Test
    void liveMatchOutsideTheLinksIsLinkedWithoutANewRow() {
        User cleo = fixtures.users().findOrFail(3);

        Role found = cleo.roles().firstOrCreate(named("admin"));

        assertEquals("1", keyOf(found));
        assertEquals(1L, count("select count(*) as c from roles where name = 'admin'"));
        assertEquals(1L, count("select count(*) as c from role_user where user_id = 3 and role_id = 1"));
    }

    @Test
    void updateOrCreateDoesNotUpdateATrashedRow() {
        User ann = fixtures.users().findOrFail(1);

        Role created = ann.roles().updateOrCreate(named(EDITOR), named("editor-two"));

        assertNotEquals("2", keyOf(created));
        assertEquals(3L, count("select count(*) as c from roles"));
        assertEquals(1L, count("select count(*) as c from roles where id = 2 and name = 'editor'"));
    }
}
