package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A self belongsToMany relation ({@code User} to {@code User} through {@code user_user}): the
 * related table is aliased {@code laravel_reserved_N} from the outer builder's counter, the pivot
 * table is never aliased, and building a query leaves no alias on the model class.
 */
class BelongsToManySelfJoinTest {

    private static final String FRIENDS_0 = "from \"users\" as \"laravel_reserved_0\""
            + " inner join \"user_user\" on \"laravel_reserved_0\".\"id\" = \"user_user\".\"friend_id\""
            + " where \"users\".\"id\" = \"user_user\".\"user_id\"";
    private static final String FRIENDS_1 = "from \"users\" as \"laravel_reserved_1\""
            + " inner join \"user_user\" on \"laravel_reserved_1\".\"id\" = \"user_user\".\"friend_id\""
            + " where \"users\".\"id\" = \"user_user\".\"user_id\"";
    private static final String SELECT_USERS = "select \"users\".*, ";
    private static final String FROM_USERS = " from \"users\"";
    private static final String PLAIN_USERS = "select * from \"users\" where ";

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<BtmModels.User> users() {
        return BtmModels.User.QUERY.on(db.db).query();
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> one(String name, Consumer<EloquentBuilder<?>> constraint) {
        Map<String, Consumer<EloquentBuilder<?>>> relations = new LinkedHashMap<>();
        relations.put(name, constraint);
        return relations;
    }

    private static long number(BtmModels.User user, String key) {
        Object value = user.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    @Test
    void hasShape() {
        String expected = PLAIN_USERS + "exists (select * " + FRIENDS_0 + ")";

        assertEquals(expected, users().has("friends").toSql());
        assertEquals(expected, BtmModels.User.QUERY.on(PostgresQuery.of(db.real)).query().has("friends").toSql());
    }

    @Test
    void sameSqlOnSecondBuild() {
        String first = users().has("friends").toSql();

        assertEquals(first, users().has("friends").toSql());
    }

    @Test
    void secondUseInOneQueryIncrementsAndNextQueryStartsAtZero() {
        assertEquals(PLAIN_USERS + "exists (select * " + FRIENDS_0 + ") and exists (select * " + FRIENDS_1 + ")",
                users().has("friends").has("friends").toSql());

        String again = users().has("friends").toSql();

        assertEquals(PLAIN_USERS + "exists (select * " + FRIENDS_0 + ")", again);
    }

    @Test
    void pivotTableNeverAliased() {
        String sql = users().has("friends").has("friends").toSql();

        assertFalse(sql.contains("\"user_user\" as"), sql);
        assertTrue(sql.contains("\"laravel_reserved_0\""), sql);
        assertTrue(sql.contains("\"laravel_reserved_1\""), sql);
    }

    @Test
    void closureSeesAlias() {
        EloquentBuilder<BtmModels.User> query = users().whereHas("friends",
                q -> q.where(q.getModel().qualifyColumn("name"), "x"));

        assertEquals(PLAIN_USERS + "exists (select * " + FRIENDS_0 + " and \"laravel_reserved_0\".\"name\" = ?)",
                query.toSql());
        assertEquals(Collections.singletonList("x"), query.getBindings());
    }

    @Test
    void noStateLeftOnModelClass() {
        users().has("friends").get();
        BtmModels.User ann = BtmModels.User.QUERY.on(db.db).findOrFail(1);
        db.clearLog();

        List<BtmModels.User> friends = ann.friends().get();

        assertEquals(1, friends.size());
        assertEquals(2L, number(friends.get(0), "id"));
        String lazy = db.sql().get(0);
        assertTrue(lazy.contains("from \"users\" inner join \"user_user\""), lazy);
        assertFalse(lazy.contains("laravel_reserved"), lazy);
    }

    @Test
    void nestedSelfQualifiesParentKeyAgainstOuterAlias() {
        EloquentBuilder<BtmModels.User> query = users().whereHas("friends", q -> q.has("friends"));

        assertEquals(PLAIN_USERS + "exists (select * " + FRIENDS_0 + " and exists (select * from \"users\" as"
                + " \"laravel_reserved_1\" inner join \"user_user\" on \"laravel_reserved_1\".\"id\""
                + " = \"user_user\".\"friend_id\" where \"laravel_reserved_0\".\"id\" = \"user_user\".\"user_id\"))",
                query.toSql());
    }

    @Test
    void countAlias() {
        EloquentBuilder<BtmModels.User> query = users().withCount("friends").orderBy("id");

        assertEquals(SELECT_USERS + "(select count(*) " + FRIENDS_0 + ") as \"friends_count\"" + FROM_USERS
                + " order by \"id\" asc", query.toSql());
        List<BtmModels.User> found = query.get();
        assertEquals(1, number(found.get(0), "friends_count"));
        assertEquals(1, number(found.get(1), "friends_count"));
        assertEquals(0, number(found.get(2), "friends_count"));
    }

    @Test
    void countAliasOnPostgresGrammar() {
        EloquentBuilder<BtmModels.User> query = BtmModels.User.QUERY.on(PostgresQuery.of(db.real)).query()
                .withCount("friends");

        assertEquals(SELECT_USERS + "(select count(*) " + FRIENDS_0 + ") as \"friends_count\"" + FROM_USERS,
                query.toSql());
    }

    @Test
    void sumQualifiedToAlias() {
        EloquentBuilder<BtmModels.User> query = users().withSum("friends", "id").orderBy("id");

        assertEquals(SELECT_USERS + "(select sum(\"laravel_reserved_0\".\"id\") " + FRIENDS_0
                + ") as \"friends_sum_id\"" + FROM_USERS + " order by \"id\" asc", query.toSql());
        assertEquals(2, number(query.get().get(0), "friends_sum_id"));
    }

    @Test
    void existsStar() {
        EloquentBuilder<BtmModels.User> query = users().withExists("friends").orderBy("id");

        assertEquals(SELECT_USERS + "exists(select * " + FRIENDS_0 + ") as \"friends_exists\"" + FROM_USERS
                + " order by \"id\" asc", query.toSql());
        List<BtmModels.User> found = query.get();
        assertEquals(Boolean.TRUE, found.get(0).getAttribute("friends_exists"));
        assertEquals(Boolean.FALSE, found.get(2).getAttribute("friends_exists"));
    }

    @Test
    void sharedWithHas() {
        String sql = users().has("friends").withCount("friends").toSql();

        assertEquals(SELECT_USERS + "(select count(*) " + FRIENDS_1 + ") as \"friends_count\"" + FROM_USERS
                + " where exists (select * " + FRIENDS_0 + ")", sql);
    }

    @Test
    void withCountClosureUnderAlias() {
        EloquentBuilder<BtmModels.User> query = users().withCount(
                one("friends", q -> q.where(q.getModel().qualifyColumn("name"), "User 2")));

        assertEquals(SELECT_USERS + "(select count(*) " + FRIENDS_0 + " and \"laravel_reserved_0\".\"name\" = ?)"
                + " as \"friends_count\"" + FROM_USERS, query.toSql());
        assertEquals(Collections.singletonList("User 2"), query.getBindings());
        List<BtmModels.User> found = query.orderBy("id").get();
        assertEquals(1, number(found.get(0), "friends_count"));
        assertEquals(0, number(found.get(1), "friends_count"));
        assertEquals(0, number(found.get(2), "friends_count"));
    }
}
