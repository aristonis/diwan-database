package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.ScopeFixtures;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Country;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Statement;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Through relations over a soft-deleting through model: trashed through parents are hidden on every
 * path, {@code withTrashedParents()} lifts that on every path and only before the first read, and the
 * self and through-self existence branches test the right table or alias.
 */
class ThroughSoftDeleteTest {

    private static final String POSTS_LIVE = "\"posts\".\"deleted_at\" is null";
    private static final String USERS_LIVE = "\"users\".\"deleted_at\" is null";
    private static final String ALIAS_LIVE = "\"laravel_reserved_0\".\"deleted_at\" is null";
    private static final Consumer<EloquentBuilder<?>> KEEP = q -> { };

    private ScopeFixtures fixtures;

    @BeforeEach
    void open() throws SQLException {
        fixtures = ScopeFixtures.open();
        fixtures.seed();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        TrashedParentsCountry.QUERY.clearGlobalScopes();
        AuthorPost.QUERY.clearGlobalScopes();
        ColleagueUser.QUERY.clearGlobalScopes();
        fixtures.close();
    }

    /** A country whose posts relation keeps trashed through users. */
    static final class TrashedParentsCountry extends Model {
        static final ModelQuery<TrashedParentsCountry> QUERY =
                ModelQuery.of(TrashedParentsCountry.class, TrashedParentsCountry::new);

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
            return RelationMap.of(TrashedParentsCountry.class).add("posts", TrashedParentsCountry::posts);
        }

        HasManyThrough<Post> posts() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", "id").withTrashedParents();
        }
    }

    /** A post (no tenant scope) that reaches posts of the same author through the soft-deleting users. */
    static final class AuthorPost extends Model {
        static final ModelQuery<AuthorPost> QUERY = ModelQuery.of(AuthorPost.class, AuthorPost::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(AuthorPost.class).add("sameAuthor", AuthorPost::sameAuthor);
        }

        HasManyThrough<AuthorPost> sameAuthor() {
            return hasManyThrough(AuthorPost.class, User.class, "id", "user_id", "user_id", "id");
        }
    }

    /** A user whose through table is its own table: posts of the users in the same country. */
    static final class ColleagueUser extends Model {
        static final ModelQuery<ColleagueUser> QUERY = ModelQuery.of(ColleagueUser.class, ColleagueUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(ColleagueUser.class).add("colleaguePosts", ColleagueUser::colleaguePosts);
        }

        HasManyThrough<Post> colleaguePosts() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "country_id", "id");
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

    private static int number(Model model, String attribute) {
        return ((Number) model.getAttribute(attribute)).intValue();
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> constraint(String relation,
            Consumer<EloquentBuilder<?>> clause) {
        return Collections.<String, Consumer<EloquentBuilder<?>>>singletonMap(relation, clause);
    }

    private void trashUser(long id) {
        assertTrue(fixtures.users().findOrFail(id).delete());
        fixtures.log.clear();
    }

    private String lastRead() {
        List<Statement> reads = fixtures.log.reads();
        return reads.get(reads.size() - 1).sql();
    }

    // ---- hidden through parents

    @Test
    void trashedThroughParentIsHidden() {
        trashUser(2);
        Country france = fixtures.countries().findOrFail(1);
        fixtures.log.clear();

        List<Long> posts = ids(france.posts().get());

        assertEquals(longs(1, 2), posts);
        assertTrue(lastRead().contains(USERS_LIVE), lastRead());
    }

    @Test
    void trashedThroughParentIsHiddenOnEagerExistenceAndCount() {
        trashUser(2);

        List<Country> eager = fixtures.countries().query().where("id", 1).with("posts").get();
        List<Long> has = ids(fixtures.countries().query().has("posts").get());
        Country counted = fixtures.countries().query().withCount("posts").where("id", 1).first().get();

        assertEquals(longs(1, 2), ids(eager.get(0).relatedMany("posts")));
        assertEquals(longs(1), has);
        assertEquals(2, number(counted, "posts_count"));
    }

    @Test
    void withTrashedParentsCoversEveryPath() {
        fixtures.insert("posts", "id", 7, "tenant_id", ScopeFixtures.TENANT_ID, "user_id", 3, "writer_id", null,
                "title", "Post 7");
        trashUser(2);
        trashUser(3);
        TrashedParentsCountry france = TrashedParentsCountry.QUERY.on(fixtures.db).findOrFail(1);

        List<TrashedParentsCountry> eager = TrashedParentsCountry.QUERY.on(fixtures.db).query().with("posts").get();
        List<Long> has = ids(TrashedParentsCountry.QUERY.on(fixtures.db).query().has("posts").get());
        List<Long> whereHas = ids(TrashedParentsCountry.QUERY.on(fixtures.db).query().whereHas("posts", KEEP).get());
        List<Long> plainHas = ids(fixtures.countries().query().has("posts").get());

        assertEquals(longs(1, 2, 3), ids(france.posts().get()));
        assertEquals(longs(1, 2, 3), ids(eager.get(0).relatedMany("posts")));
        assertEquals(longs(7), ids(eager.get(1).relatedMany("posts")));
        assertEquals(longs(1, 2), has);
        assertEquals(longs(1, 2), whereHas);
        assertEquals(longs(1), plainHas);
    }

    @Test
    void withTrashedParentsAfterReadIsRefused() {
        Country france = fixtures.countries().findOrFail(1);
        HasManyThrough<Post> read = france.posts();
        read.get();
        HasManyThrough<Post> fresh = france.posts();

        IllegalStateException failure = assertThrows(IllegalStateException.class, read::withTrashedParents);

        assertTrue(failure.getMessage().contains("withTrashedParents"), failure.getMessage());
        assertSame(fresh, fresh.withTrashedParents());
    }

    @Test
    void plainThroughModelAddsNothing() {
        Country france = fixtures.countries().findOrFail(1);
        HasManyThrough<Post> plain = france.plainPosts();
        fixtures.log.clear();

        List<Long> posts = ids(plain.get());

        assertFalse(plain.throughParentSoftDeletes());
        assertTrue(france.posts().throughParentSoftDeletes());
        assertEquals(longs(1), posts);
        assertFalse(lastRead().contains("\"writers\".\"deleted_at\""), lastRead());
    }

    @Test
    void selfAndThroughSelfExistence() {
        fixtures.insert("users", "id", 4, "country_id", 2, "name", "Dan");
        fixtures.insert("posts", "id", 8, "tenant_id", ScopeFixtures.TENANT_ID, "user_id", 4, "writer_id", null,
                "title", "Post 8");
        trashUser(4);
        trashUser(2);

        EloquentBuilder<AuthorPost> self = AuthorPost.QUERY.on(fixtures.db).query().has("sameAuthor");
        EloquentBuilder<ColleagueUser> throughSelf = ColleagueUser.QUERY.on(fixtures.db).query()
                .has("colleaguePosts");

        String selfSql = self.toSql();
        String throughSelfSql = throughSelf.toSql();
        assertTrue(selfSql.contains(USERS_LIVE), selfSql);
        assertTrue(selfSql.contains(ALIAS_LIVE), selfSql);
        assertTrue(throughSelfSql.contains(ALIAS_LIVE), throughSelfSql);
        assertEquals(longs(1, 2, 5, 6), ids(self.get()));
        assertEquals(longs(1), ids(throughSelf.get()));
    }

    @Test
    void relatedAndThroughBothHideTrashed() {
        trashUser(2);
        Country france = fixtures.countries().findOrFail(1);
        fixtures.log.clear();

        List<Long> posts = ids(france.posts().get());

        assertEquals(longs(1, 2), posts);
        assertTrue(lastRead().contains(POSTS_LIVE), lastRead());
        assertTrue(lastRead().contains(USERS_LIVE), lastRead());
    }

    @Test
    void throughModelOtherScopesAreNotApplied() {
        User.QUERY.addGlobalScope("not-ann", builder -> builder.where("name", "<>", "Ann"));
        Country france = fixtures.countries().findOrFail(1);
        fixtures.log.clear();

        List<Long> posts = ids(france.posts().get());

        assertEquals(longs(1, 2, 3), posts);
        assertFalse(lastRead().contains("<>"), lastRead());
    }

    @Test
    void withCountThroughTrashedParents() {
        trashUser(2);

        Country live = fixtures.countries().query().withCount("posts").where("id", 1).first().get();
        TrashedParentsCountry all = TrashedParentsCountry.QUERY.on(fixtures.db).query().withCount("posts")
                .where("id", 1).first().get();
        TrashedParentsCountry lifted = TrashedParentsCountry.QUERY.on(fixtures.db).query()
                .withCount(constraint("posts", KEEP)).where("id", 1).first().get();

        assertEquals(2, number(live, "posts_count"));
        assertEquals(3, number(all, "posts_count"));
        assertEquals(3, number(lifted, "posts_count"));
    }

    @Test
    void countryScenario() {
        trashUser(1);
        Country france = fixtures.countries().findOrFail(1);
        TrashedParentsCountry withParents = TrashedParentsCountry.QUERY.on(fixtures.db).findOrFail(1);

        List<Long> hidden = ids(france.posts().get());
        List<Long> shown = ids(withParents.posts().get());

        assertEquals(longs(3), hidden);
        assertEquals(longs(1, 2, 3), shown);
    }
}
