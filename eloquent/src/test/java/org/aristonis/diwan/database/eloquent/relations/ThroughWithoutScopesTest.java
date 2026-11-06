package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Country;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Statement;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Removing the related model's global scopes from a through relation never exposes trashed through
 * parents; only {@code withTrashedParents()} does.
 */
class ThroughWithoutScopesTest {

    private static final String USERS_LIVE = "\"users\".\"deleted_at\" is null";
    private static final String POSTS_LIVE = "\"posts\".\"deleted_at\" is null";

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        BareCountry.QUERY.clearGlobalScopes();
        BareAllCountry.QUERY.clearGlobalScopes();
        fixtures.close();
    }

    /** A country whose posts relation drops every related scope in its factory. */
    static final class BareCountry extends Model {
        static final ModelQuery<BareCountry> QUERY = ModelQuery.of(BareCountry.class, BareCountry::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(BareCountry.class).add("posts", BareCountry::posts);
        }

        HasManyThrough<Post> posts() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", "id")
                    .withoutGlobalScopes();
        }
    }

    /** The same country that also keeps trashed through users. */
    static final class BareAllCountry extends Model {
        static final ModelQuery<BareAllCountry> QUERY = ModelQuery.of(BareAllCountry.class, BareAllCountry::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(BareAllCountry.class).add("posts", BareAllCountry::posts);
        }

        HasManyThrough<Post> posts() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", "id")
                    .withoutGlobalScopes().withTrashedParents();
        }
    }

    private static List<Long> ids(Iterable<? extends Model> models) {
        List<Long> ids = new ArrayList<>();
        for (Model model : models) {
            ids.add(Long.parseLong(String.valueOf(model.getKey())));
        }
        Collections.sort(ids);
        return ids;
    }

    private static List<Long> longs(long... values) {
        List<Long> list = new ArrayList<>();
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    private void trashUser(long id) {
        assertTrue(fixtures.users().findOrFail(id).delete());
        fixtures.log.clear();
    }

    private String lastRead() {
        List<Statement> reads = fixtures.log.reads();
        return reads.get(reads.size() - 1).sql();
    }

    @Test
    void withoutGlobalScopesKeepsTheThroughParentFilter() {
        trashUser(2);
        Country france = fixtures.countries().findOrFail(1);
        fixtures.log.clear();

        List<Long> posts = ids(france.posts().withoutGlobalScopes().get());

        assertEquals(longs(1, 2, 4, 6), posts);
        assertTrue(lastRead().contains(USERS_LIVE), lastRead());
        assertFalse(lastRead().contains(POSTS_LIVE), lastRead());
        assertFalse(lastRead().contains("\"tenant_id\""), lastRead());
    }

    @Test
    void withTrashedParentsDropsTheThroughParentFilter() {
        trashUser(2);
        Country france = fixtures.countries().findOrFail(1);
        fixtures.log.clear();

        List<Long> posts = ids(france.posts().withoutGlobalScopes().withTrashedParents().get());

        assertEquals(longs(1, 2, 3, 4, 6), posts);
        assertFalse(lastRead().contains(USERS_LIVE), lastRead());
    }

    @Test
    void removingOneScopeLeavesTheThroughParentFilter() {
        trashUser(2);
        Country france = fixtures.countries().findOrFail(1);

        List<Long> noTenant = ids(france.posts().withoutGlobalScope(ScopeFixtures.TENANT).get());
        List<Long> noSoft = ids(france.posts().withoutGlobalScope(SoftDeletingScope.class).get());
        List<Long> allButTenant = ids(france.posts().withoutGlobalScopesExcept(
                Collections.singletonList(ScopeFixtures.TENANT)).get());

        assertEquals(longs(1, 2, 6), noTenant);
        assertEquals(longs(1, 2, 4), noSoft);
        assertEquals(longs(1, 2, 4), allButTenant);
    }

    @Test
    void factoryWithoutScopesKeepsTheFilterOnEagerAndExistence() {
        trashUser(3);

        List<BareCountry> eager = BareCountry.QUERY.on(fixtures.db).query().with("posts").get();
        List<Long> has = ids(BareCountry.QUERY.on(fixtures.db).query().has("posts").get());

        assertEquals(longs(1, 2, 3, 4, 6), ids(eager.get(0).relatedMany("posts")));
        assertTrue(eager.get(1).relatedMany("posts").isEmpty());
        assertEquals(longs(1), has);
    }

    @Test
    void factoryWithTrashedParentsKeepsTrashedThroughRows() {
        trashUser(3);

        List<BareAllCountry> eager = BareAllCountry.QUERY.on(fixtures.db).query().with("posts").get();
        List<Long> has = ids(BareAllCountry.QUERY.on(fixtures.db).query().has("posts").get());

        assertEquals(longs(5), ids(eager.get(1).relatedMany("posts")));
        assertEquals(longs(1, 2), has);
    }

    @Test
    void constraintThatDropsScopesDoesNotExposeTrashedParents() {
        trashUser(3);

        List<Long> countries = ids(fixtures.countries().query()
                .whereHas("posts", q -> q.withoutGlobalScopes()).get());

        assertEquals(longs(1), countries);
    }
}
