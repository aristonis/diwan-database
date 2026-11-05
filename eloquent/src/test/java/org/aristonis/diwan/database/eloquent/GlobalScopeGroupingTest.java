package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How global scope clauses join the clauses the caller wrote: a scope never widens a query. */
class GlobalScopeGroupingTest {

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

    @Test
    void globalScopeNeverWidensTheQuery() {
        Post.QUERY.addGlobalScope("either", base -> base.orWhere("title", "Post 2").orWhere("title", "Post 3"));
        EloquentBuilder<Post> query = fixtures.posts().query().where("title", "Post 1");

        String sql = query.toSql();

        assertTrue(sql.endsWith("and (\"title\" = ? or \"title\" = ?)"), sql);
        assertFalse(sql.contains(" or \"title\" = ? and"), sql);
        assertEquals(Arrays.<Object>asList("Post 1", ScopeFixtures.TENANT_ID, "Post 2", "Post 3"),
                query.getBindings());
        assertTrue(query.get().isEmpty(), "the user's own condition must still narrow the result");
    }

    @Test
    void userOrConditionsAreGroupedBeforeTheScopes() {
        EloquentBuilder<Post> query = fixtures.posts().query().where("title", "Post 1").orWhere("views", 9);

        String sql = query.toSql();

        assertTrue(sql.contains("where (\"title\" = ? or \"views\" = ?) and \"posts\".\"deleted_at\" is null"), sql);
        assertTrue(sql.contains(TENANT_SQL));
        assertEquals(Arrays.<Object>asList("Post 1", 9, ScopeFixtures.TENANT_ID), query.getBindings());
    }

    @Test
    void scopeWithoutWheresChangesNothing() {
        Post.QUERY.addGlobalScope("newest", base -> base.join("users", "users.id", "=", "posts.user_id")
                .orderBy("id", "desc"));

        String sql = fixtures.posts().query().toSql();

        assertTrue(sql.contains("inner join \"users\" on \"users\".\"id\" = \"posts\".\"user_id\""), sql);
        assertTrue(sql.endsWith("order by \"id\" desc"), sql);
        assertTrue(sql.contains(LIVE_ONLY));
        assertTrue(sql.contains(TENANT_SQL));
        assertFalse(sql.contains("("), "no group may appear when the scope adds no where: " + sql);
    }

    @Test
    void removalInsidePlainWhereGroupIsIgnored() {
        EloquentBuilder<Post> query = fixtures.posts().query()
                .whereGroup(group -> group.withoutGlobalScope(ScopeFixtures.TENANT));

        String sql = query.toSql();

        assertTrue(sql.contains(TENANT_SQL), sql);
        assertTrue(sql.contains(LIVE_ONLY), sql);
        assertTrue(query.removedScopes().isEmpty());
    }

    @Test
    void removalInsidePlainWhereGroupWithConditionsKeepsTheScopes() {
        EloquentBuilder<Post> query = fixtures.posts().query()
                .whereGroup(group -> group.where("title", "Post 1").withoutGlobalScope(ScopeFixtures.TENANT));

        String sql = query.toSql();

        assertTrue(sql.contains("(\"title\" = ?)"), sql);
        assertTrue(sql.contains(TENANT_SQL), sql);
        assertTrue(query.getBindings().contains("Post 1"));
    }

    @Test
    void removingEveryScopeInsidePlainWhereGroupKeepsTheOuterScopes() {
        EloquentBuilder<Post> query = fixtures.posts().query().whereGroup(group -> group.withoutGlobalScopes());

        String sql = query.toSql();

        assertTrue(sql.contains(TENANT_SQL), sql);
        assertTrue(sql.contains(LIVE_ONLY), sql);
    }
}
