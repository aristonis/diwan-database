package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.Role;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.User;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code wherePivot} family, {@code orderByPivot} and {@code withPivotValue} on the SQL the
 * lazy, eager, existence and aggregate paths run. The SQLite tests read the generated SQL, and
 * the nested PostgreSQL group checks the same paths on the PostgreSQL grammar.
 */
class WherePivotTest {

    private static final String KEY = "where \"role_user\".\"user_id\" = ?";
    private static final String STATUS = "\"role_user\".\"status\"";
    private static final String WEIGHT = "\"role_user\".\"weight\"";

    /** A user with roles whose pivot rows are filtered by a condition declared in the method. */
    static final class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

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
            return RelationMap.of(Member.class)
                    .add("activeRoles", Member::activeRoles)
                    .add("weightedRoles", Member::weightedRoles);
        }

        public BelongsToMany<Role> activeRoles() {
            return belongsToMany(Role.class, "role_user", "user_id", "role_id")
                    .wherePivot("status", "active");
        }

        public BelongsToMany<Role> weightedRoles() {
            return belongsToMany(Role.class, "role_user", "user_id", "role_id")
                    .orderByPivot("weight");
        }
    }

    private BtmDb db;
    private User user;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
        user = User.QUERY.on(db.db).findOrFail(1);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void twoArgument() {
        BelongsToMany<Role> roles = user.roles().wherePivot("status", "active");

        assertEndsWith(sql(roles), KEY + " and " + STATUS + " = ?");
        assertEquals("active", lastBinding(roles));
    }

    @Test
    void threeArgument() {
        BelongsToMany<Role> roles = user.roles().wherePivot("weight", ">", 2);

        assertEndsWith(sql(roles), KEY + " and " + WEIGHT + " > ?");
        assertEquals(2, lastBinding(roles));
    }

    @Test
    void dottedKept() {
        BelongsToMany<Role> roles = user.roles().wherePivot("role_user.status", "x");

        assertEndsWith(sql(roles), KEY + " and " + STATUS + " = ?");
    }

    @Test
    void orForm() {
        BelongsToMany<Role> roles = user.roles().wherePivot("status", "active").orWherePivot("status", "pending");

        assertEndsWith(sql(roles), KEY + " and " + STATUS + " = ? or " + STATUS + " = ?");
        assertEquals("pending", lastBinding(roles));
    }

    @Test
    void inForms() {
        BelongsToMany<Role> roles = user.roles()
                .wherePivotIn("status", Arrays.asList("active", "pending"))
                .wherePivotNotIn("weight", Collections.singletonList(9))
                .orWherePivotIn("status", Collections.singletonList("x"))
                .orWherePivotNotIn("weight", Collections.singletonList(8));

        assertEndsWith(sql(roles), KEY + " and " + STATUS + " in (?, ?) and " + WEIGHT + " not in (?)"
                + " or " + STATUS + " in (?) or " + WEIGHT + " not in (?)");
    }

    @Test
    void emptyIn() {
        BelongsToMany<Role> roles = user.roles().wherePivotIn("status", Collections.emptyList());

        assertEndsWith(sql(roles), KEY + " and 0 = 1");
    }

    @Test
    void betweenForms() {
        List<Object> range = Arrays.<Object>asList(1, 5);
        BelongsToMany<Role> roles = user.roles()
                .wherePivotBetween("weight", range)
                .wherePivotNotBetween("weight", range)
                .orWherePivotBetween("weight", range)
                .orWherePivotNotBetween("weight", range);

        assertEndsWith(sql(roles), KEY + " and " + WEIGHT + " between ? and ? and " + WEIGHT
                + " not between ? and ? or " + WEIGHT + " between ? and ? or " + WEIGHT
                + " not between ? and ?");
    }

    @Test
    void betweenBadList() {
        BelongsToMany<Role> roles = user.roles().wherePivotBetween("weight", Collections.singletonList(1));

        assertThrows(IllegalArgumentException.class, () -> roles.getQuery());
    }

    @Test
    void nullForms() {
        BelongsToMany<Role> roles = user.roles()
                .wherePivotNull("status")
                .orWherePivotNull("weight")
                .wherePivotNotNull("status")
                .orWherePivotNotNull("weight");

        assertEndsWith(sql(roles), KEY + " and " + STATUS + " is null or " + WEIGHT + " is null and "
                + STATUS + " is not null or " + WEIGHT + " is not null");
    }

    @Test
    void orderForms() {
        BelongsToMany<Role> roles = user.roles()
                .orderByPivot("weight")
                .orderByPivot("weight", "desc")
                .orderByPivotDesc("weight");

        assertEndsWith(sql(roles), "order by " + WEIGHT + " asc, " + WEIGHT + " desc, " + WEIGHT + " desc");
    }

    @Test
    void badDirection() {
        BelongsToMany<Role> roles = user.roles().orderByPivot("weight", "sideways");

        assertThrows(IllegalArgumentException.class, () -> roles.getQuery());
    }

    @Test
    void pivotValue() {
        BelongsToMany<Role> roles = user.roles().withPivotValue("status", "active");

        assertEndsWith(sql(roles), KEY + " and " + STATUS + " = ?");
        assertEquals(Collections.singletonMap("status", "active"), roles.pivotValues());
    }

    @Test
    void pivotValueNull() {
        BelongsToMany<Role> roles = user.roles();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> roles.withPivotValue("status", null));

        assertTrue(error.getMessage().contains("The provided value may not be null"));
        assertTrue(error.getMessage().contains("status"));
        assertTrue(roles.pivotValues().isEmpty());
        assertTrue(roles.pivotClauses().isEmpty());
    }

    @Test
    void pivotValueMap() {
        BelongsToMany<Role> roles = user.roles();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", "active");
        values.put("weight", null);

        assertThrows(IllegalArgumentException.class, () -> roles.withPivotValue(values));

        assertTrue(roles.pivotValues().isEmpty());
        assertTrue(roles.pivotClauses().isEmpty());
        assertFalse(sql(roles).contains(STATUS + " = ?"));
    }

    @Test
    void appliesToEager() {
        db.clearLog();

        Member member = Member.QUERY.on(db.db).with("activeRoles").findOrFail(1);

        ModelCollection<Role> roles = member.relatedMany("activeRoles");
        assertEquals(1, roles.size());
        assertEquals("admin", roles.get(0).getAttribute("name"));
        assertTrue(anyContains(db.sql(), STATUS + " = ?"));
    }

    @Test
    void appliesToExistence() {
        String sql = Member.QUERY.on(db.db).query().has("activeRoles").toSql();

        assertTrue(sql.contains("exists ("));
        assertTrue(sql.contains(STATUS + " = ?"));
        assertEquals(2, Member.QUERY.on(db.db).query().has("activeRoles").get().size());
    }

    @Test
    void ordersDroppedInAggregates() {
        String sql = Member.QUERY.on(db.db).query().withCount("weightedRoles").toSql();

        assertTrue(sql.contains("role_user"));
        assertFalse(sql.contains("order by"));
    }

    @Test
    void mixedWithRelatedWhere() {
        BelongsToMany<Role> roles = user.roles().where("roles.name", "admin").orWherePivot("status", "pending");

        assertEndsWith(sql(roles), KEY + " and \"roles\".\"name\" = ? or " + STATUS + " = ?");
        assertEquals("pending", lastBinding(roles));
    }

    /**
     * The same lazy, eager and existence shapes on the PostgreSQL grammar, over a throwaway
     * database. Skipped when the server binaries are missing.
     */
    @Nested
    class OnPostgres {

        private QueryFactory pg;

        @BeforeEach
        void openPostgres() {
            Connection connection = PostgresServer.newDatabase();
            pg = PostgresQuery.of(connection);
            connection.statement("create table users (id bigserial primary key, name varchar(255) not null)");
            connection.statement("create table roles (id bigserial primary key, name varchar(255) not null)");
            connection.statement("create table role_user (user_id bigint not null, role_id bigint not null,"
                    + " status varchar(255), weight bigint, created_at timestamp, updated_at timestamp)");
            connection.statement("insert into users (id, name) values (1, 'Ann')");
            connection.statement("insert into roles (id, name) values (1, 'admin'), (2, 'editor')");
            connection.statement("insert into role_user (user_id, role_id, status, weight) values"
                    + " (1, 1, 'active', 5), (1, 2, 'pending', 1)");
        }

        @Test
        void lazyTailOnPostgres() {
            Member member = Member.QUERY.on(pg).findOrFail(1);

            assertEndsWith(sql(member.activeRoles()), KEY + " and " + STATUS + " = ?");
        }

        @Test
        void eagerOnPostgres() {
            Member member = Member.QUERY.on(pg).with("activeRoles").findOrFail(1);

            assertEquals(1, member.relatedMany("activeRoles").size());
        }

        @Test
        void existenceOnPostgres() {
            ModelCollection<Member> members = Member.QUERY.on(pg).query().has("activeRoles").get();

            assertEquals(1, members.size());
        }
    }

    private static String sql(BelongsToMany<?> relation) {
        return relation.getQuery().toSql();
    }

    private static Object lastBinding(BelongsToMany<?> relation) {
        List<Object> bindings = relation.getQuery().getBindings();
        return bindings.get(bindings.size() - 1);
    }

    private static boolean anyContains(List<String> statements, String fragment) {
        for (String statement : statements) {
            if (statement.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static void assertEndsWith(String sql, String tail) {
        assertTrue(sql.endsWith(tail), () -> "expected [" + sql + "] to end with [" + tail + "]");
    }
}
