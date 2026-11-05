package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Country;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Global scopes and soft deletes on a throwaway PostgreSQL: scoped reads and writes, eager loads,
 * existence queries, the stored {@code deleted_at} text, through relations over soft parents and
 * rollback of a soft delete. Skipped when the server binaries are missing, and failing when
 * {@code DIWAN_PG_REQUIRED} is set.
 */
class PostgresScopesTest {

    private ScopeFixtures fx;

    @BeforeEach
    void open() {
        fx = ScopeFixtures.openPostgres();
        fx.seed();
    }

    @AfterEach
    void close() throws Exception {
        ScopeFixtures.clearScopes();
        fx.close();
    }

    private static List<Long> keys(List<? extends Model> models) {
        List<Long> keys = new ArrayList<>();
        for (Model model : models) {
            keys.add(((Number) model.getKey()).longValue());
        }
        Collections.sort(keys);
        return keys;
    }

    private long number(String sql) {
        return ((Number) fx.real.selectOne(sql).get().get("v")).longValue();
    }

    private Object deletedAt(long postId) {
        return fx.real.selectOne("select cast(deleted_at as varchar) as v from posts where id = " + postId)
                .get().get("v");
    }

    private static Map<String, Object> single(String column, Object value) {
        return Collections.<String, Object>singletonMap(column, value);
    }

    private ModelQuery<Post> postsOn(org.aristonis.diwan.database.Connection connection) {
        return Post.QUERY.on(PostgresQuery.of(connection)).withClock(ModelClock.fixed(ScopeFixtures.NOW));
    }

    @Test
    void scopedReadHidesOtherTenantsAndTrashedRows() {
        List<Post> live = fx.posts().query().get();
        List<Post> all = fx.posts().query().withTrashed().get();
        List<Post> trashed = fx.posts().query().onlyTrashed().get();

        assertEquals(Arrays.asList(1L, 2L, 3L), keys(live));
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L), keys(all));
        assertEquals(Collections.singletonList(4L), keys(trashed));
        assertEquals(3L, fx.posts().query().count());
    }

    @Test
    void writesCarryTheScope() {
        int updated = fx.posts().query().update(single("title", "Renamed"));
        int incremented = fx.posts().query().increment("views", 2);
        int deleted = fx.posts().query().delete();

        assertEquals(3, updated);
        assertEquals(3, incremented);
        assertEquals(3, deleted);
        assertEquals(3L, number("select count(*) as v from posts where title = 'Renamed'"));
        assertEquals(6L, number("select sum(views) as v from posts where tenant_id = 7"));
        assertEquals(0L, number("select sum(views) as v from posts where tenant_id = 8"));
        assertEquals(0L, number("select count(*) as v from posts where title = 'Renamed' and tenant_id = 8"));
        assertEquals(4L, number("select count(*) as v from posts where deleted_at is not null and tenant_id = 7"));
        assertEquals(0L, number("select count(*) as v from posts where deleted_at is not null and tenant_id = 8"));
        assertEquals(6L, number("select count(*) as v from posts"));
    }

    @Test
    void eagerLoadAppliesTheScopeToTheRelatedRows() {
        List<User> users = fx.users().query().with("posts").orderBy("id").get();

        assertEquals(3, users.size());
        assertEquals(Arrays.asList(1L, 2L), keys(users.get(0).<Post>relatedMany("posts")));
        assertEquals(Collections.singletonList(3L), keys(users.get(1).<Post>relatedMany("posts")));
        assertTrue(users.get(2).<Post>relatedMany("posts").isEmpty());
    }

    @Test
    void whereHasAndHasApplyTheScopeInsideTheExistsSubSelect() {
        List<User> withPosts = fx.users().query().has("posts").get();
        List<User> titled = fx.users().query().whereHas("posts", q -> q.where("title", "Post 3")).get();
        List<User> viaTrashed = fx.users().query().whereHas("posts", q -> q.withTrashed().where("title", "Post 4"))
                .get();

        assertEquals(Arrays.asList(1L, 2L), keys(withPosts));
        assertEquals(Collections.singletonList(2L), keys(titled));
        assertEquals(Collections.singletonList(1L), keys(viaTrashed));
    }

    @Test
    void softDeleteRoundTripOnPostgres() {
        Post post = fx.posts().query().find(1L).get();

        assertTrue(post.delete());
        Object stored = deletedAt(1L);
        boolean hiddenAfterDelete = fx.posts().query().find(1L).isPresent();
        Post trashed = fx.posts().query().onlyTrashed().find(1L).get();
        boolean restored = trashed.restore();

        assertEquals("2026-11-05 10:15:30", stored);
        assertFalse(hiddenAfterDelete);
        assertFalse(trashed.trashed());
        assertTrue(restored);
        assertNull(deletedAt(1L));
        assertTrue(fx.posts().query().find(1L).isPresent());
        assertEquals(1L, number("select count(*) as v from posts where id = 1 and deleted_at is null"));
    }

    @Test
    void seededTrashedRowKeepsItsTextAndRestoresToNull() {
        Post trashed = fx.posts().query().withTrashed().find(4L).get();

        assertEquals(ScopeFixtures.TRASHED_AT, deletedAt(4L));
        assertTrue(trashed.trashed());
        assertTrue(trashed.restore());
        assertNull(deletedAt(4L));
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L), keys(fx.posts().query().get()));
    }

    @Test
    void throughRelationSkipsTrashedParentsUnlessAsked() {
        User ben = fx.users().query().find(2L).get();
        assertTrue(ben.delete());
        Country france = fx.countries().query().find(1L).get();

        List<Post> live = france.posts().get();
        List<Post> withParents = fx.countries().query().find(1L).get().posts().withTrashedParents().get();

        assertEquals(Arrays.asList(1L, 2L), keys(live));
        assertEquals(Arrays.asList(1L, 2L, 3L), keys(withParents));
    }

    @Test
    void throughRelationOverPlainParentsStillAppliesTheChildScope() {
        Country france = fx.countries().query().find(1L).get();

        List<Post> viaWriters = france.plainPosts().get();

        assertEquals(Collections.singletonList(1L), keys(viaWriters));
    }

    @Test
    void softDeleteInsideARolledBackTransactionLeavesTheRowUnchanged() {
        assertThrows(IllegalStateException.class, () -> fx.real.transaction(c -> {
            Post post = postsOn(c).query().find(1L).get();
            assertTrue(post.delete());
            assertTrue(post.trashed());
            assertEquals("2026-11-05 10:15:30",
                    c.selectOne("select cast(deleted_at as varchar) as v from posts where id = 1").get().get("v"));
            throw new IllegalStateException("roll back");
        }));

        assertNull(deletedAt(1L));
        assertTrue(fx.posts().query().find(1L).isPresent());
    }

    @Test
    void restoreInsideARolledBackTransactionLeavesTheRowTrashed() {
        assertThrows(IllegalStateException.class, () -> fx.real.transaction(c -> {
            Post post = postsOn(c).query().withTrashed().find(4L).get();
            assertTrue(post.restore());
            assertFalse(post.trashed());
            assertNull(c.selectOne("select cast(deleted_at as varchar) as v from posts where id = 4").get().get("v"));
            throw new IllegalStateException("roll back");
        }));

        assertEquals(ScopeFixtures.TRASHED_AT, deletedAt(4L));
        assertFalse(fx.posts().query().find(4L).isPresent());
    }

    @Test
    void softDeleteAndRestoreCommittedInATransactionStick() {
        fx.real.transaction(c -> {
            Post post = postsOn(c).query().find(2L).get();
            post.delete();
            Post back = postsOn(c).query().onlyTrashed().find(2L).get();
            back.restore();
            Post gone = postsOn(c).query().find(3L).get();
            gone.delete();
            return null;
        });

        assertNull(deletedAt(2L));
        assertEquals("2026-11-05 10:15:30", deletedAt(3L));
    }
}
