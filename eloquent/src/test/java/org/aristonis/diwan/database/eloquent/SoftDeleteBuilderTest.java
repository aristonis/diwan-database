package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Statement;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Tag;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The soft-delete extensions of the builder: scoped reads, bulk writes, restores and destroys. */
class SoftDeleteBuilderTest {

    private static final String MOMENT = "2026-11-05 10:15:30";
    private static final String USERS_ALL = "select * from \"users\"";
    private static final String USERS_LIVE = "select * from \"users\" where \"users\".\"deleted_at\" is null";
    private static final String USERS_TRASHED = "select * from \"users\" where \"users\".\"deleted_at\" is not null";
    private static final String USERS_SOFT_DELETE = "update \"users\" set \"deleted_at\" = ?, \"updated_at\" = ?"
            + " where \"users\".\"deleted_at\" is null";
    private static final String USERS_RESTORE = "update \"users\" set \"deleted_at\" = ?, \"updated_at\" = ?"
            + " where \"users\".\"deleted_at\" is not null";
    private static final String POSTS_FORCE_DELETE = "delete from \"posts\" where \"tenant_id\" = ?";
    private static final String UNIQUE_NAMES = "create unique index users_name_unique on users (name)";

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

    private void trash(String table, int id) {
        fixtures.real.statement("update \"" + table + "\" set \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT
                + "' where \"id\" = " + id);
    }

    private long count(String table, String where) {
        List<Map<String, Object>> rows = fixtures.real.select(
                "select count(*) as c from " + table + " where " + where, Collections.emptyList());
        return ((Number) rows.get(0).get("c")).longValue();
    }

    private static List<Long> keysOf(Iterable<? extends Model> models) {
        List<Long> keys = new ArrayList<>();
        for (Model model : models) {
            keys.add(keyOf(model));
        }
        return keys;
    }

    private static long keyOf(Model model) {
        return Long.parseLong(String.valueOf(model.getKey()));
    }

    // ---- design section 5: exact SQL of the builder rows ----

    @Test
    void defaultGetHidesTrashedRows() {
        trash("users", 1);

        EloquentBuilder<User> query = fixtures.users().query();

        assertEquals(USERS_LIVE, query.toSql());
        assertEquals(Arrays.asList(2L, 3L), keysOf(query.get()));
    }

    @Test
    void withTrashedSelectsEveryRow() {
        trash("users", 1);

        String sql = fixtures.users().query().withTrashed().toSql();

        assertEquals(USERS_ALL, sql);
    }

    @Test
    void onlyTrashedSelectsOnlyTrashedRows() {
        trash("users", 1);

        EloquentBuilder<User> query = fixtures.users().query().onlyTrashed();

        assertEquals(USERS_TRASHED, query.toSql());
        assertEquals(Collections.singletonList(1L), keysOf(query.get()));
    }

    @Test
    void withoutTrashedMatchesTheDefault() {
        trash("users", 1);

        assertEquals(USERS_LIVE, fixtures.users().query().withoutTrashed().toSql());
        assertEquals(USERS_LIVE, fixtures.users().query().withTrashed().withoutTrashed().toSql());
    }

    @Test
    void builderDeleteIsASoftUpdate() {
        int rows = fixtures.users().query().delete();

        assertEquals(3, rows);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals(USERS_SOFT_DELETE, writes.get(0).sql());
        assertEquals(Arrays.<Object>asList(MOMENT, MOMENT), writes.get(0).bindings());
        assertEquals(0, count("users", "\"deleted_at\" is null"));
    }

    @Test
    void builderForceDeleteRemovesRowsForGood() {
        trash("users", 1);

        int rows = fixtures.users().query().where("id", 1).forceDelete();

        assertEquals(1, rows);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals("delete from \"users\" where \"id\" = ?", writes.get(0).sql());
        assertEquals(Collections.singletonList(1), writes.get(0).bindings());
        assertEquals(0, count("users", "\"id\" = 1"));
    }

    @Test
    void builderRestoreClearsTheColumn() {
        trash("users", 1);
        trash("users", 2);

        int rows = fixtures.users().query().onlyTrashed().restore();

        assertEquals(2, rows);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals(USERS_RESTORE, writes.get(0).sql());
        assertEquals(Arrays.asList(null, MOMENT), writes.get(0).bindings());
        assertEquals(3, count("users", "\"deleted_at\" is null"));
    }

    // ---- scenario rows: builder extensions and bulk writes ----

    @Test
    void withTrashedFalseHidesTrashed() {
        trash("users", 1);

        EloquentBuilder<User> query = fixtures.users().query().withTrashed(false);

        assertEquals(USERS_LIVE, query.toSql());
        assertEquals(2L, query.count());
    }

    @Test
    void onlyTrashedAfterWithTrashed() {
        trash("users", 1);

        EloquentBuilder<User> query = fixtures.users().query().withTrashed().onlyTrashed();

        assertEquals(USERS_TRASHED, query.toSql());
        assertEquals(1L, query.count());
    }

    @Test
    void extensionsNeedSoftDeletes() {
        String tagName = Tag.class.getName();

        IllegalStateException withTrashed = assertThrows(IllegalStateException.class,
                () -> fixtures.tags().query().withTrashed());
        IllegalStateException withoutTrashed = assertThrows(IllegalStateException.class,
                () -> fixtures.tags().query().withoutTrashed());
        IllegalStateException onlyTrashed = assertThrows(IllegalStateException.class,
                () -> fixtures.tags().query().onlyTrashed());

        assertTrue(withTrashed.getMessage().contains(tagName), withTrashed.getMessage());
        assertTrue(withoutTrashed.getMessage().contains(tagName), withoutTrashed.getMessage());
        assertTrue(onlyTrashed.getMessage().contains(tagName), onlyTrashed.getMessage());
    }

    @Test
    void bulkDeleteSkipsTrashedRows() {
        int rows = fixtures.posts().query().whereKey(Arrays.asList(1, 2, 4)).delete();

        assertEquals(2, rows);
        assertEquals(2, count("posts", "\"id\" in (1, 2) and \"deleted_at\" is not null"));
        assertEquals(1, count("posts", "\"id\" = 4 and \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT + "'"));
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).sql().contains("\"posts\".\"deleted_at\" is null"), writes.get(0).sql());
    }

    @Test
    void withTrashedDeleteStillSoftDeletes() {
        int rows = fixtures.posts().query().withTrashed().whereKey(4).delete();

        assertEquals(1, rows);
        assertEquals(1, count("posts", "\"id\" = 4"));
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).sql().startsWith("update \"posts\" set "), writes.get(0).sql());
        assertFalse(writes.get(0).sql().contains("delete from"), writes.get(0).sql());
    }

    @Test
    void forceDeleteKeepsTheTenantScope() {
        int rows = fixtures.posts().query().forceDelete();

        assertEquals(4, rows);
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertEquals(POSTS_FORCE_DELETE, writes.get(0).sql());
        assertEquals(Collections.singletonList(ScopeFixtures.TENANT_ID), writes.get(0).bindings());
        assertEquals(0, count("posts", "\"tenant_id\" = 7"));
        assertEquals(2, count("posts", "\"tenant_id\" = 8"));
    }

    @Test
    void restoreOnAJoinedQuery() {
        int rows = fixtures.posts().query().onlyTrashed()
                .join("users", "users.id", "=", "posts.user_id").restore();

        assertEquals(1, rows);
        assertEquals(1, count("posts", "\"id\" = 4 and \"deleted_at\" is null"));
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).sql().contains("\"posts\".\"deleted_at\" is not null"), writes.get(0).sql());
    }

    @Test
    void updateOfDeletedAtColumn() {
        int rows = fixtures.posts().query().update(Collections.singletonMap("deleted_at", null));

        assertEquals(3, rows);
        assertEquals(1, count("posts", "\"id\" = 4 and \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT + "'"));
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).sql().contains("\"deleted_at\" = ?"), writes.get(0).sql());
        assertTrue(writes.get(0).sql().contains("\"posts\".\"deleted_at\" is null"), writes.get(0).sql());
        assertEquals(Arrays.asList(null, MOMENT, ScopeFixtures.TENANT_ID), writes.get(0).bindings());
    }

    // ---- scenario rows: restoreOrCreate, createOrRestore and destroy ----

    @Test
    void restoreOrCreateFindsTrashed() {
        trash("users", 1);

        User found = fixtures.users().query().restoreOrCreate(Collections.singletonMap("name", "Ann"));

        assertEquals(1L, keyOf(found));
        assertFalse(found.wasRecentlyCreated());
        assertFalse(found.trashed());
        List<Statement> writes = fixtures.log.writes();
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).sql().startsWith("update \"users\""), writes.get(0).sql());
        assertEquals(3, count("users", "1 = 1"));
    }

    @Test
    void restoreOrCreateCreatesLive() {
        User created = fixtures.users().query().restoreOrCreate(Collections.singletonMap("name", "Dan"),
                Collections.singletonMap("country_id", 2));

        assertTrue(created.wasRecentlyCreated());
        assertFalse(created.trashed());
        // SQLite returns the key from the insert itself, so the log records it as a read.
        List<Statement> inserts = new ArrayList<>();
        for (Statement statement : fixtures.log.all()) {
            if (statement.sql().startsWith("insert into \"users\"")) {
                inserts.add(statement);
            }
        }
        assertEquals(1, inserts.size(), fixtures.log.all().toString());
        assertEquals(4, count("users", "1 = 1"));
    }

    @Test
    void createOrRestoreAfterUniqueViolation() {
        fixtures.real.statement(UNIQUE_NAMES);
        trash("users", 1);

        User found = fixtures.users().query().createOrRestore(Collections.singletonMap("name", "Ann"),
                Collections.singletonMap("country_id", 2));

        assertEquals(1L, keyOf(found));
        assertFalse(found.wasRecentlyCreated());
        assertFalse(found.trashed());
        assertEquals(3, count("users", "1 = 1"));
        assertEquals(1, count("users", "\"name\" = 'Ann' and \"deleted_at\" is null"));
    }

    @Test
    void destroySkipsTrashedKeys() {
        int deleted = fixtures.posts().destroy(1, 4);

        assertEquals(1, deleted);
        assertEquals(1, count("posts", "\"id\" = 1 and \"deleted_at\" is not null"));
        assertEquals(1, count("posts", "\"id\" = 4 and \"deleted_at\" = '" + ScopeFixtures.TRASHED_AT + "'"));
    }

    @Test
    void forceDestroyCases() {
        assertEquals(0, fixtures.posts().forceDestroy());

        assertEquals(1, fixtures.posts().forceDestroy(4));
        assertEquals(0, count("posts", "\"id\" = 4"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> fixtures.tags().forceDestroy(1));
        assertTrue(refused.getMessage().contains(Tag.class.getName()), refused.getMessage());
    }

    @Test
    void batchLifecycle() {
        int batch = fixtures.posts().query().where("user_id", 1).delete();

        assertEquals(2, batch);
        assertEquals(1, count("posts", "\"id\" = 2 and \"deleted_at\" is not null"));
        assertEquals(1, fixtures.posts().forceDestroy(1));
        assertEquals(0, count("posts", "\"id\" = 1"));
    }

    @Test
    void adminListsAllTrashed() {
        trash("posts", 5);

        EloquentBuilder<Post> query = fixtures.posts().query().withoutGlobalScope(ScopeFixtures.TENANT).onlyTrashed();

        assertEquals(Arrays.asList(4L, 5L), keysOf(query.get()));
    }

    @Test
    void extensionsWorkAfterRemovingTheSoftDeletingScope() {
        trash("users", 1);

        EloquentBuilder<User> trashedQuery = fixtures.users().query()
                .withoutGlobalScope(SoftDeletingScope.class).onlyTrashed();
        EloquentBuilder<User> liveQuery = fixtures.users().query()
                .withoutGlobalScope(SoftDeletingScope.class).withoutTrashed();

        assertEquals(1L, trashedQuery.count());
        assertEquals(USERS_LIVE, liveQuery.toSql());
    }

    @Test
    void softOperationsRefuseANonSoftModel() {
        String tagName = Tag.class.getName();

        assertTrue(assertThrows(IllegalStateException.class, () -> fixtures.tags().query().forceDelete())
                .getMessage().contains(tagName));
        assertTrue(assertThrows(IllegalStateException.class, () -> fixtures.tags().query().restore())
                .getMessage().contains(tagName));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> fixtures.tags().query().restoreOrCreate(Collections.singletonMap("name", "x")))
                .getMessage().contains(tagName));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> fixtures.tags().query().createOrRestore(Collections.singletonMap("name", "x")))
                .getMessage().contains(tagName));
    }
}
