package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The closure of {@code has} and {@code whereHas} on a throwaway PostgreSQL: grouping with having,
 * limit, an or-where, several bindings, a join, and the count form that clears orders and
 * refuses groups. Skipped when the server binaries are missing, and failing when
 * {@code DIWAN_PG_REQUIRED} is set.
 */
class PostgresExistenceClosureTest {

    /** A user over the {@code pgx_users} table. */
    static final class PgxUser extends Model {
        static final ModelQuery<PgxUser> QUERY = ModelQuery.of(PgxUser.class, PgxUser::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected String table() {
            return "pgx_users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(PgxUser.class).add("posts", PgxUser::posts);
        }

        public HasMany<PgxPost> posts() {
            return hasMany(PgxPost.class, "user_id");
        }
    }

    /** A post over the {@code pgx_posts} table. */
    static final class PgxPost extends Model {
        static final ModelQuery<PgxPost> QUERY = ModelQuery.of(PgxPost.class, PgxPost::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected String table() {
            return "pgx_posts";
        }
    }

    private Connection real;
    private QueryFactory db;

    @BeforeEach
    void open() {
        real = PostgresServer.newDatabase();
        db = PostgresQuery.of(real);
        real.statement("create table pgx_users (id integer primary key, name varchar(50) not null)");
        real.statement("create table pgx_posts (id integer primary key, user_id integer, title varchar(50) not null)");
        real.statement("create table pgx_comments (id integer primary key, post_id integer, body varchar(50) not null)");
        real.statement("insert into pgx_users (id, name) values (1, 'Ann'), (2, 'Bob'), (3, 'Cid')");
        real.statement("insert into pgx_posts (id, user_id, title) values (1, 1, 'a'), (2, 1, 'b'), (3, 2, 'c')");
        real.statement("insert into pgx_comments (id, post_id, body) values (1, 3, 'x')");
        PgxPost.QUERY.getGlobalScopes();
    }

    private EloquentBuilder<PgxUser> users() {
        return PgxUser.QUERY.on(db).query();
    }

    private static List<Long> keys(EloquentBuilder<PgxUser> query) {
        List<Long> keys = new ArrayList<>();
        for (Object key : query.get().modelKeys()) {
            keys.add(((Number) key).longValue());
        }
        Collections.sort(keys);
        return keys;
    }

    @Test
    void groupByAndHavingFilterInsideExists() {
        EloquentBuilder<PgxUser> query = users().whereHas("posts",
                q -> q.select("user_id").groupBy("user_id").query(b -> b.havingRaw("count(*) > ?", Collections.singletonList(1))));

        assertEquals(Collections.singletonList(1L), keys(query));
    }

    @Test
    void limitRunsInsideExists() {
        EloquentBuilder<PgxUser> query = users().whereHas("posts", q -> q.orderBy("title", "desc").limit(1));

        assertEquals(Arrays.asList(1L, 2L), keys(query));
    }

    @Test
    void orWhereInsideClosureStaysGrouped() {
        EloquentBuilder<PgxUser> query = users().whereHas("posts",
                q -> q.where("title", "a").orWhere("title", "b"));

        assertEquals(Collections.singletonList(1L), keys(query));
    }

    @Test
    void severalClosureBindingsBesideTheRelationKeyReturnTheRightRows() {
        EloquentBuilder<PgxUser> any = users()
                .whereHas("posts", q -> q.whereIn("title", Arrays.asList("a", "b")).orWhere("id", 3));
        EloquentBuilder<PgxUser> named = users()
                .where("name", "Ann")
                .whereHas("posts", q -> q.whereIn("title", Arrays.asList("a", "b")).orWhere("id", 3))
                .where("id", 1);

        assertEquals(Arrays.asList(1L, 2L), keys(any));
        assertEquals(Collections.singletonList(1L), keys(named));
    }

    @Test
    void joinInsideClosureRunsAndFilters() {
        EloquentBuilder<PgxUser> query = users().whereHas("posts",
                q -> q.join("pgx_comments", "pgx_comments.post_id", "=", "pgx_posts.id")
                        .where("pgx_comments.body", "x"));

        assertEquals(Collections.singletonList(2L), keys(query));
    }

    @Test
    void countFormClearsOrdersAndRuns() {
        EloquentBuilder<PgxUser> query = users().whereHas("posts",
                q -> q.orderBy("title").limit(1).query(b -> b.offset(5)), ">", 1);

        assertEquals(Collections.singletonList(1L), keys(query));
    }

    @Test
    void countFormRefusesGroupByBeforeTheDatabaseIsReached() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> users().whereHas("posts", q -> q.groupBy("user_id"), ">", 1));

        assertTrue(e.getMessage().contains("groupBy/having"), e.getMessage());
    }
}
