package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import org.aristonis.diwan.database.query.Builder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Global scopes applied lazily at each terminal: what a builder keeps, drops and repeats. */
class GlobalScopeApplyTest {

    private static final String LIVE_ONLY = "\"deleted_at\" is null";
    private static final String TENANT_SQL = "\"tenant_id\" = ?";

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

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static List<Object> idsOf(Iterable<Post> posts) {
        List<Object> ids = new ArrayList<>();
        for (Post post : posts) {
            ids.add(Long.parseLong(String.valueOf(post.getKey())));
        }
        return ids;
    }

    private int rowCount(String where) {
        List<Map<String, Object>> rows = fixtures.real.select(
                "select count(*) as c from posts where " + where, Collections.emptyList());
        return ((Number) rows.get(0).get("c")).intValue();
    }

    @Test
    void copyKeepsScopeState() {
        EloquentBuilder<Post> original = fixtures.posts().query().withoutGlobalScope(ScopeFixtures.TENANT);

        EloquentBuilder<Post> copy = original.copy();
        copy.where("views", 0);

        assertEquals(Collections.singletonList(ScopeFixtures.TENANT), copy.removedScopes());
        assertTrue(copy.toSql().contains(LIVE_ONLY));
        assertFalse(copy.toSql().contains(TENANT_SQL));
        assertFalse(original.toSql().contains("\"views\""), "a change to the copy must not reach the original");
        assertEquals(Collections.singletonList(ScopeFixtures.TENANT), original.removedScopes());
    }

    @Test
    void terminalsDoNotMutateTheBuilder() {
        EloquentBuilder<Post> query = fixtures.posts().query();
        String before = query.toSql();

        int rows = query.get().size();
        long count = query.count();

        assertEquals(3, rows);
        assertEquals(3L, count);
        assertEquals(before, query.toSql());
        List<Statement> reads = fixtures.log.reads();
        assertEquals(2, reads.size());
        for (Statement read : reads) {
            assertEquals(1, occurrences(read.sql(), LIVE_ONLY), read.toString());
            assertEquals(1, occurrences(read.sql(), TENANT_SQL), read.toString());
        }
        assertEquals(reads.get(0).bindings(), reads.get(1).bindings());
    }

    @Test
    void removalSurvivesLaterWheres() {
        EloquentBuilder<Post> query = fixtures.posts().query().withoutGlobalScope(ScopeFixtures.TENANT)
                .where("views", 0);

        String sql = query.toSql();

        assertTrue(sql.contains(LIVE_ONLY));
        assertTrue(sql.contains("\"views\" = ?"));
        assertFalse(sql.contains("tenant_id"));
        assertEquals(Arrays.<Object>asList(1L, 2L, 3L, 5L, 6L), idsOf(query.orderBy("id").get()));
    }

    @Test
    void removingUnknownNameIsRecorded() {
        EloquentBuilder<Post> query = fixtures.posts().query();
        String before = query.toSql();

        EloquentBuilder<Post> same = query.withoutGlobalScope("nobody");

        assertEquals(Collections.singletonList("nobody"), same.removedScopes());
        assertEquals(before, same.toSql());
    }

    @Test
    void exceptKeepsOnlyTheNamedScopes() {
        Post.QUERY.addGlobalScope("positive", base -> base.where("views", ">=", 0));
        EloquentBuilder<Post> everything = fixtures.posts().query();
        assertTrue(everything.toSql().contains("\"views\" >= ?"));

        String sql = fixtures.posts().query()
                .withoutGlobalScopesExcept(Collections.singletonList(ScopeFixtures.TENANT)).toSql();

        assertTrue(sql.contains(TENANT_SQL));
        assertFalse(sql.contains(LIVE_ONLY));
        assertFalse(sql.contains("\"views\" >= ?"));
    }

    @Test
    void scopeRemovingALaterScope() {
        Post.QUERY.addGlobalScope("first", base -> base.withoutGlobalScope("second").where("views", 1));
        Post.QUERY.addGlobalScope("second", base -> base.where("title", "never"));

        String sql = fixtures.posts().query().toSql();

        assertTrue(sql.contains("\"views\" = ?"));
        assertFalse(sql.contains("\"title\""), "the removed scope must be skipped in that pass");
    }

    @Test
    void throwingScopeLeavesBuilderUsable() {
        Post.QUERY.addGlobalScope("boom", base -> {
            throw new IllegalStateException("boom");
        });
        EloquentBuilder<Post> query = fixtures.posts().query();

        IllegalStateException failure = assertThrows(IllegalStateException.class, query::get);

        assertEquals("boom", failure.getMessage());
        assertTrue(fixtures.log.reads().isEmpty(), "no statement may run after a failing scope");
        assertEquals(Arrays.<Object>asList(1L, 2L, 3L), idsOf(query.withoutGlobalScope("boom").orderBy("id").get()));
    }

    @Test
    void noScopeNoExtraWork() {
        EloquentBuilder<Tag> query = Tag.QUERY.on(fixtures.db).query();

        Builder first = query.toBase();
        Builder second = query.toBase();
        first.where("name", "x");

        assertNotSame(first, second);
        assertTrue(second.getWheres().isEmpty());
        assertEquals("select * from \"tags\"", query.toSql());
        assertEquals(Collections.emptyList(), query.removedScopes());
    }

    @Test
    void tenantScenario() {
        ModelQuery<Post> posts = fixtures.posts();

        assertEquals(Arrays.<Object>asList(1L, 2L, 3L), idsOf(posts.query().orderBy("id").get()));
        assertEquals(3L, posts.query().count());
        assertEquals(3, posts.query().update(Collections.singletonMap("title", "Renamed")));
        assertEquals(0, rowCount("tenant_id = 8 and title = 'Renamed'"));
        assertEquals(3, posts.query().delete());

        assertEquals(0, posts.query().count());
        assertEquals(2, rowCount("tenant_id = 8 and deleted_at is null"));
        assertEquals(4, rowCount("tenant_id = 7 and deleted_at is not null"));
    }
}
