package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Existence constraints on a belongsToMany relation: the shapes of {@code has} and
 * {@code whereHas}, the closure and pivot-column forms, custom keys and the refusal of a closure
 * that adds a join. SQL is asserted on the SQLite and the PostgreSQL grammar; results are read
 * back from SQLite.
 */
class BelongsToManyExistenceTest {

    private static final String USERS = "select * from \"users\" where ";
    private static final String ROLES_OF_USER = "from \"roles\" inner join \"role_user\""
            + " on \"roles\".\"id\" = \"role_user\".\"role_id\""
            + " where \"users\".\"id\" = \"role_user\".\"user_id\"";
    private static final String EXISTS_ROLES = "exists (select * " + ROLES_OF_USER + ")";
    private static final String NOT_EXISTS_ROLES = "not exists (select * " + ROLES_OF_USER + ")";
    private static final String PERMISSIONS_OF_ROLE = "exists (select * from \"permissions\""
            + " inner join \"permission_role\" on \"permissions\".\"id\" = \"permission_role\".\"permission_id\""
            + " where \"roles\".\"id\" = \"permission_role\".\"role_id\")";

    /** A user whose active roles are filtered by a pivot clause recorded in the relation method. */
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
            return RelationMap.of(ActiveUser.class).add("activeRoles", ActiveUser::activeRoles);
        }

        BelongsToMany<BtmModels.Role> activeRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .wherePivot("status", "active");
        }
    }

    /** A user whose roles are active or archived: the second pivot condition is an {@code or}. */
    static final class MixedUser extends Model {
        static final ModelQuery<MixedUser> QUERY = ModelQuery.of(MixedUser.class, MixedUser::new);

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
            return RelationMap.of(MixedUser.class).add("mixedRoles", MixedUser::mixedRoles);
        }

        BelongsToMany<BtmModels.Role> mixedRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .wherePivot("status", "active")
                    .orWherePivot("status", "archived");
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

    private EloquentBuilder<BtmModels.User> postgresUsers() {
        return BtmModels.User.QUERY.on(PostgresQuery.of(db.real)).query();
    }

    @Test
    void hasShape() {
        EloquentBuilder<BtmModels.User> query = users().has("roles");

        assertEquals(USERS + EXISTS_ROLES, query.toSql());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void hasShapeOnPostgresGrammar() {
        assertEquals(USERS + EXISTS_ROLES, postgresUsers().has("roles").toSql());
    }

    @Test
    void doesntHave() {
        EloquentBuilder<BtmModels.User> query = users().doesntHave("roles");

        assertEquals(USERS + NOT_EXISTS_ROLES, query.toSql());
        assertEquals(Arrays.asList(3, 4), query.get().modelKeys());
    }

    @Test
    void countForm() {
        EloquentBuilder<BtmModels.User> query = users().has("roles", ">=", 2);

        assertEquals(USERS + "(select count(*) " + ROLES_OF_USER + ") >= ?", query.toSql());
        assertEquals(Collections.singletonList(2L), query.getBindings());
        assertEquals(Collections.singletonList(1), query.get().modelKeys());
    }

    @Test
    void whereHasClosure() {
        EloquentBuilder<BtmModels.User> query = users().whereHas("roles", q -> q.where("name", "admin"));

        assertEquals(USERS + "exists (select * " + ROLES_OF_USER + " and (\"name\" = ?))", query.toSql());
        assertEquals(Collections.singletonList("admin"), query.getBindings());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void pivotColumnInClosure() {
        EloquentBuilder<BtmModels.User> query = users().whereHas("roles",
                q -> q.where("role_user.status", "active"));

        assertEquals(USERS + "exists (select * " + ROLES_OF_USER + " and (\"role_user\".\"status\" = ?))",
                query.toSql());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void whereRelation() {
        EloquentBuilder<BtmModels.User> query = users()
                .whereRelation("roles", "role_user.status", "active")
                .orWhereRelation("roles", "role_user.status", "pending");

        assertEquals(USERS + "exists (select * " + ROLES_OF_USER + " and (\"role_user\".\"status\" = ?))"
                + " or exists (select * " + ROLES_OF_USER + " and (\"role_user\".\"status\" = ?))", query.toSql());
        assertEquals(Arrays.asList("active", "pending"), query.getBindings());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void orForms() {
        assertEquals(USERS + "\"id\" = ? or " + EXISTS_ROLES, users().where("id", 3).orHas("roles").toSql());
        assertEquals(USERS + "\"id\" = ? or " + NOT_EXISTS_ROLES,
                users().where("id", 3).orDoesntHave("roles").toSql());
        assertEquals(USERS + "\"id\" = ? or exists (select * " + ROLES_OF_USER + " and (\"name\" = ?))",
                users().where("id", 3).orWhereHas("roles", q -> q.where("name", "admin")).toSql());
        assertEquals(USERS + "\"id\" = ? or not exists (select * " + ROLES_OF_USER + " and (\"name\" = ?))",
                users().where("id", 3).orWhereDoesntHave("roles", q -> q.where("name", "admin")).toSql());
    }

    @Test
    void nestedPath() {
        EloquentBuilder<BtmModels.User> query = users().has("roles.permissions");

        assertEquals(USERS + "exists (select * " + ROLES_OF_USER + " and (" + PERMISSIONS_OF_ROLE + "))",
                query.toSql());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void nestedPathDoesntHaveNegatesOnlyTheHead() {
        EloquentBuilder<BtmModels.User> query = users().doesntHave("roles.permissions");

        assertEquals(USERS + "not exists (select * " + ROLES_OF_USER + " and (" + PERMISSIONS_OF_ROLE + "))",
                query.toSql());
        assertEquals(Arrays.asList(3, 4), query.get().modelKeys());
    }

    @Test
    void relationPivotWhere() {
        EloquentBuilder<ActiveUser> query = ActiveUser.QUERY.on(db.db).query().has("activeRoles");

        assertEquals(USERS + "exists (select * " + ROLES_OF_USER + " and (\"role_user\".\"status\" = ?))",
                query.toSql());
        assertEquals(Collections.singletonList("active"), query.getBindings());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void declaredOrWherePivotStaysCorrelated() {
        db.seed("role_user", Map.of("user_id", 3, "role_id", 1, "status", "archived", "weight", 1));
        EloquentBuilder<MixedUser> query = MixedUser.QUERY.on(db.db).query().has("mixedRoles");

        assertEquals(USERS + "exists (select * " + ROLES_OF_USER
                + " and (\"role_user\".\"status\" = ? or \"role_user\".\"status\" = ?))", query.toSql());
        assertEquals(Arrays.asList("active", "archived"), query.getBindings());
        assertEquals(Arrays.asList(1, 2, 3), query.get().modelKeys());
    }

    @Test
    void orWhereGrouped() {
        EloquentBuilder<BtmModels.User> query = users().whereHas("roles",
                q -> q.where("name", "admin").orWhere("name", "editor"));

        assertEquals(USERS + "exists (select * " + ROLES_OF_USER + " and (\"name\" = ? or \"name\" = ?))",
                query.toSql());
        assertEquals(Arrays.asList("admin", "editor"), query.getBindings());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void closureJoinRefused() {
        assertThrows(IllegalStateException.class, () -> users().whereHas("roles",
                q -> q.join("permission_role", "roles.id", "=", "permission_role.role_id")));
    }

    @Test
    void ownJoinAccepted() {
        List<Object> keys = assertDoesNotThrow(() -> users()
                .whereHas("roles", q -> q.where("name", "admin")).get().modelKeys());

        assertEquals(Arrays.asList(1, 2), keys);
    }

    @Test
    void customParentKey() {
        String sql = BtmModels.Country.QUERY.on(db.db).query().has("languages").toSql();

        assertTrue(sql.contains("where \"countries\".\"code\" = \"country_language\".\"country_code\")"), sql);
    }

    @Test
    void customRelatedKey() {
        EloquentBuilder<BtmModels.Country> query = BtmModels.Country.QUERY.on(db.db).query()
                .has("languages").orderBy("code");

        assertEquals("select * from \"countries\" where exists (select * from \"languages\""
                + " inner join \"country_language\" on \"languages\".\"iso\" = \"country_language\".\"language_iso\""
                + " where \"countries\".\"code\" = \"country_language\".\"country_code\") order by \"code\" asc",
                query.toSql());
        assertEquals(Arrays.asList("FR", "US"), query.get().modelKeys());
    }

    @Test
    void withEager() {
        List<BtmModels.User> found = users().has("roles").with("roles").get();

        assertEquals(2, found.size());
        assertEquals(2, ((List<?>) found.get(0).getRelation("roles")).size());
        assertEquals(1, ((List<?>) found.get(1).getRelation("roles")).size());
    }
}
