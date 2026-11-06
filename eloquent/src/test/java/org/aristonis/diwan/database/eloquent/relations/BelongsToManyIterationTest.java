package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.query.QueryCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The iterators of a belongsToMany relation: chunks, each, lazy sequences and the cursor. Every role
 * they visit carries its pivot, and the select each read sends follows the appending rule.
 */
class BelongsToManyIterationTest {

    private static final String ALIASES = "\"role_user\".\"user_id\" as \"pivot_user_id\", "
            + "\"role_user\".\"role_id\" as \"pivot_role_id\", "
            + "\"role_user\".\"status\" as \"pivot_status\", "
            + "\"role_user\".\"weight\" as \"pivot_weight\", "
            + "\"role_user\".\"created_at\" as \"pivot_created_at\", "
            + "\"role_user\".\"updated_at\" as \"pivot_updated_at\"";

    private static final String JOIN = " from \"roles\" inner join \"role_user\" on "
            + "\"roles\".\"id\" = \"role_user\".\"role_id\"";

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
    void chunk() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        List<ModelCollection<BtmModels.Role>> chunks = new ArrayList<>();

        boolean completed = roles.chunk(1, page -> {
            chunks.add(page);
            return true;
        });

        assertTrue(completed);
        assertEquals(2, chunks.size());
        for (ModelCollection<BtmModels.Role> page : chunks) {
            assertEquals(1, page.size());
            assertHasPivot(page.get(0));
        }
    }

    @Test
    void chunkStops() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        List<ModelCollection<BtmModels.Role>> chunks = new ArrayList<>();

        boolean completed = roles.chunk(1, page -> {
            chunks.add(page);
            return false;
        });

        assertFalse(completed);
        assertEquals(1, chunks.size());
    }

    @Test
    void chunkAppendsSelect() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().select("roles.name as role_name");
        db.clearLog();

        roles.chunk(10, page -> true);

        String prefix = "select \"roles\".\"name\" as \"role_name\", \"roles\".*, " + ALIASES + JOIN;
        assertTrue(db.sql().get(0).startsWith(prefix), db.sql().get(0));
    }

    @Test
    void chunkByIdDefaults() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        db.clearLog();
        List<Object> visited = new ArrayList<>();

        boolean completed = roles.chunkById(1, page -> visited.addAll(page.modelKeys()));

        assertTrue(completed);
        assertEquals(Arrays.asList(1, 2), visited);
        assertTrue(db.sql().get(0).contains("order by \"roles\".\"id\" asc"), db.sql().get(0));
    }

    @Test
    void chunkByIdCustom() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        db.clearLog();
        List<Object> names = new ArrayList<>();

        roles.chunkById(10, page -> {
            for (BtmModels.Role role : page) {
                names.add(role.getAttribute("name"));
            }
            return true;
        }, "roles.name", "name");

        assertEquals(Arrays.asList("admin", "editor"), names);
        assertTrue(db.sql().get(0).contains("order by \"roles\".\"name\" asc"), db.sql().get(0));
    }

    @Test
    void each() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        List<Object> visited = new ArrayList<>();

        assertTrue(roles.each(role -> collect(role, visited)));
        assertTrue(roles.each(role -> collect(role, visited), 100));

        assertEquals(Arrays.asList(1, 2, 1, 2), visited);
    }

    @Test
    void eachStopsOnFalse() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        List<Object> visited = new ArrayList<>();

        boolean completed = roles.each(role -> {
            visited.add(role.getAttribute("id"));
            return false;
        });

        assertFalse(completed);
        assertEquals(Collections.singletonList(1), visited);
    }

    @Test
    void lazy() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();

        List<Object> defaultChunk = readAll(roles.lazy());
        List<Object> oneAtATime = readAll(roles.lazy(1));

        assertEquals(Arrays.asList(1, 2), defaultChunk);
        assertEquals(Arrays.asList(1, 2), oneAtATime);
    }

    @Test
    void lazyById() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();

        List<Object> byKey = readAll(roles.lazyById(1));
        List<Object> byNamedKey = readAll(roles.lazyById(1, "roles.id", "id"));

        assertEquals(Arrays.asList(1, 2), byKey);
        assertEquals(Arrays.asList(1, 2), byNamedKey);
    }

    @Test
    void cursor() {
        QueryCursor<BtmModels.Role> cursor = user(1).roles().cursor();
        try {
            assertEquals(new HashSet<>(Arrays.asList(1, 2)), new HashSet<>(readAll(cursor)));
        } finally {
            cursor.close();
        }
    }

    @Test
    void cursorWithEagerRefused() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        roles.getQuery().with("permissions");

        assertThrows(IllegalStateException.class, () -> roles.cursor());
    }

    @Test
    void withEagerOnRelation() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        roles.getQuery().with("permissions");
        List<Object> visited = new ArrayList<>();

        roles.chunk(10, page -> {
            for (BtmModels.Role role : page) {
                assertHasPivot(role);
                assertTrue(role.relationLoaded("permissions"));
                visited.add(role.getAttribute("id"));
            }
            return true;
        });

        assertEquals(Arrays.asList(1, 2), visited);
    }

    @Test
    void emptyIterators() {
        BelongsToMany<BtmModels.Role> roles = user(3).roles();
        List<Object> visited = new ArrayList<>();

        assertTrue(roles.chunk(1, page -> visited.addAll(page.modelKeys())));
        assertTrue(roles.each(role -> collect(role, visited)));
        assertTrue(readAll(roles.lazy()).isEmpty());
        QueryCursor<BtmModels.Role> cursor = roles.cursor();
        try {
            assertTrue(readAll(cursor).isEmpty());
        } finally {
            cursor.close();
        }

        assertTrue(visited.isEmpty());
    }

    private BtmModels.User user(int id) {
        return BtmModels.User.QUERY.on(db.db).findOrFail(id);
    }

    private static boolean collect(BtmModels.Role role, List<Object> into) {
        assertHasPivot(role);
        return into.add(role.getAttribute("id"));
    }

    private static List<Object> readAll(Iterable<BtmModels.Role> roles) {
        List<Object> ids = new ArrayList<>();
        for (BtmModels.Role role : roles) {
            assertHasPivot(role);
            ids.add(role.getAttribute("id"));
        }
        return ids;
    }

    private static void assertHasPivot(BtmModels.Role role) {
        assertNotNull(Pivot.of(role));
    }
}
