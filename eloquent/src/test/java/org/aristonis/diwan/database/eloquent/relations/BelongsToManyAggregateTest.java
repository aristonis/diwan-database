package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelPage;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aggregate subselects over a belongsToMany relation: counts, sums, maxima and existence, the
 * pivot-column and closure forms, custom keys, and the refusals. SQL is asserted on the SQLite
 * and the PostgreSQL grammar; values are read back from SQLite.
 */
class BelongsToManyAggregateTest {

    private static final String ROLE_JOIN = "from \"roles\" inner join \"role_user\""
            + " on \"roles\".\"id\" = \"role_user\".\"role_id\""
            + " where \"users\".\"id\" = \"role_user\".\"user_id\"";
    private static final String SELECT_USERS = "select \"users\".*, ";
    private static final String FROM_USERS = " from \"users\"";
    private static final String PLAIN_USERS = "select * from \"users\"";

    /** A user whose relation methods carry a pivot clause and a pivot ordering. */
    static final class ActiveUser extends Model {
        static final ModelQuery<ActiveUser> QUERY = ModelQuery.of(ActiveUser.class, ActiveUser::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(ActiveUser.class)
                    .add("activeRoles", ActiveUser::activeRoles)
                    .add("weightedRoles", ActiveUser::weightedRoles);
        }

        BelongsToMany<BtmModels.Role> activeRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .wherePivot("status", "active");
        }

        BelongsToMany<BtmModels.Role> weightedRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .orderByPivot("weight");
        }
    }

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

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    @Test
    void countShape() {
        EloquentBuilder<BtmModels.User> query = users().withCount("roles").orderBy("id");

        assertEquals(SELECT_USERS + "(select count(*) " + ROLE_JOIN + ") as \"roles_count\"" + FROM_USERS
                + " order by \"id\" asc", query.toSql());
        List<BtmModels.User> found = query.get();
        assertEquals(2L, number(found.get(0), "roles_count"));
        assertEquals(1L, number(found.get(1), "roles_count"));
        assertEquals(0L, number(found.get(2), "roles_count"));
    }

    @Test
    void countShapeOnPostgresGrammar() {
        EloquentBuilder<BtmModels.User> query = BtmModels.User.QUERY.on(PostgresQuery.of(db.real)).query()
                .withCount("roles");

        assertEquals(SELECT_USERS + "(select count(*) " + ROLE_JOIN + ") as \"roles_count\"" + FROM_USERS,
                query.toSql());
    }

    @Test
    void joinNotRefused() {
        List<BtmModels.User> found = assertDoesNotThrow(() -> users()
                .withCount("roles").withSum("roles", "role_user.weight").orderBy("id").get());

        assertEquals(2L, number(found.get(0), "roles_count"));
        assertEquals(6L, number(found.get(0), "roles_sum_role_userweight"));
    }

    @Test
    void pivotColumnSum() {
        EloquentBuilder<BtmModels.User> query = users().withSum("roles", "role_user.weight").orderBy("id");

        assertEquals(SELECT_USERS + "(select sum(\"role_user\".\"weight\") " + ROLE_JOIN
                + ") as \"roles_sum_role_userweight\"" + FROM_USERS + " order by \"id\" asc", query.toSql());
        assertEquals(6L, number(query.get().get(0), "roles_sum_role_userweight"));
    }

    @Test
    void relatedColumnMax() {
        EloquentBuilder<BtmModels.User> query = users().withMax("roles", "id").orderBy("id");

        assertEquals(SELECT_USERS + "(select max(\"roles\".\"id\") " + ROLE_JOIN + ") as \"roles_max_id\""
                + FROM_USERS + " order by \"id\" asc", query.toSql());
        assertEquals(2L, number(query.get().get(0), "roles_max_id"));
    }

    @Test
    void existsBoolean() {
        EloquentBuilder<BtmModels.User> query = users().withExists("roles").orderBy("id");

        assertEquals(SELECT_USERS + "exists(select * " + ROLE_JOIN + ") as \"roles_exists\"" + FROM_USERS
                + " order by \"id\" asc", query.toSql());
        List<BtmModels.User> found = query.get();
        assertEquals(Boolean.TRUE, found.get(0).getAttribute("roles_exists"));
        assertEquals(Boolean.FALSE, found.get(2).getAttribute("roles_exists"));
    }

    @Test
    void pivotFilteredCount() {
        EloquentBuilder<BtmModels.User> query = users().withCount(
                one("roles", q -> q.where("role_user.status", "active"))).orderBy("id");

        assertEquals(SELECT_USERS + "(select count(*) " + ROLE_JOIN + " and (\"role_user\".\"status\" = ?))"
                + " as \"roles_count\"" + FROM_USERS + " order by \"id\" asc", query.toSql());
        assertEquals(Collections.singletonList("active"), query.getBindings());
        assertEquals(1L, number(query.get().get(0), "roles_count"));
    }

    @Test
    void existsWithClosure() {
        List<BtmModels.User> found = users().withExists(
                one("roles", q -> q.where("role_user.status", "gone"))).orderBy("id").get();

        for (BtmModels.User user : found) {
            assertEquals(Boolean.FALSE, user.getAttribute("roles_exists"));
        }
    }

    @Test
    void relationPivotWhere() {
        EloquentBuilder<ActiveUser> query = ActiveUser.QUERY.on(db.db).query().withCount("activeRoles")
                .orderBy("id");

        assertEquals(SELECT_USERS + "(select count(*) " + ROLE_JOIN + " and (\"role_user\".\"status\" = ?))"
                + " as \"active_roles_count\"" + FROM_USERS + " order by \"id\" asc", query.toSql());
        assertEquals(Collections.singletonList("active"), query.getBindings());
        List<ActiveUser> found = query.get();
        assertEquals(1L, number(found.get(0), "active_roles_count"));
        assertEquals(1L, number(found.get(1), "active_roles_count"));
        assertEquals(0L, number(found.get(2), "active_roles_count"));
    }

    @Test
    void ordersDroppedInAggregates() {
        String sql = ActiveUser.QUERY.on(db.db).query().withCount("weightedRoles").toSql();

        assertFalse(sql.contains("order by"), sql);
        assertTrue(sql.contains("(select count(*) " + ROLE_JOIN + ") as \"weighted_roles_count\""), sql);
    }

    @Test
    void explicitAlias() {
        EloquentBuilder<BtmModels.User> query = users().withCount("roles as role_total");

        assertTrue(query.toSql().endsWith(") as \"role_total\"" + FROM_USERS), query.toSql());
    }

    @Test
    void closureJoinRefused() {
        EloquentBuilder<BtmModels.User> query = users();

        assertThrows(IllegalStateException.class, () -> query.withCount(
                one("roles", q -> q.join("permission_role", "roles.id", "=", "permission_role.role_id"))));
        assertEquals(PLAIN_USERS, query.toSql());
    }

    @Test
    void closureCarryOver() {
        EloquentBuilder<BtmModels.User> query = users().withCount(
                one("roles", q -> q.limit(1).select("name").orderBy("name")));

        String sql = query.toSql();
        assertTrue(sql.contains(ROLE_JOIN + " limit 1) as \"roles_count\""), sql);
        assertFalse(sql.contains("order by"), sql);
        assertFalse(sql.contains("\"name\""), sql);
        assertEquals(2L, number(query.get().get(0), "roles_count"));
    }

    @Test
    void customKeys() {
        EloquentBuilder<BtmModels.Country> query = BtmModels.Country.QUERY.on(db.db).query()
                .withCount("languages").orderBy("code");

        assertEquals("select \"countries\".*, (select count(*) from \"languages\" inner join \"country_language\""
                + " on \"languages\".\"iso\" = \"country_language\".\"language_iso\""
                + " where \"countries\".\"code\" = \"country_language\".\"country_code\") as \"languages_count\""
                + " from \"countries\" order by \"code\" asc", query.toSql());
        List<BtmModels.Country> found = query.get();
        assertEquals(1L, number(found.get(0), "languages_count"));
        assertEquals(1L, number(found.get(1), "languages_count"));
    }

    @Test
    void paginateKeepsCount() {
        db.clearLog();

        ModelPage<BtmModels.User> page = users().withCount("roles").orderBy("id").paginate(5);

        assertEquals(4, page.total());
        assertEquals(2L, number(page.items().get(0), "roles_count"));
        String totalQuery = db.sql().get(0);
        assertFalse(totalQuery.contains("roles_count"), totalQuery);
    }

    @Test
    void unknownRelation() {
        EloquentBuilder<BtmModels.User> query = users();

        assertThrows(RelationNotFoundException.class, () -> query.withCount("rolez"));
        assertThrows(RelationNotFoundException.class, () -> query.withCount("roles.permissions"));
        assertEquals(PLAIN_USERS, query.toSql());
    }
}
