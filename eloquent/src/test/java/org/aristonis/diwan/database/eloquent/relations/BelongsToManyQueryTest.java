package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lazy belongsToMany read: the SQL it runs, the columns it selects, the results it returns and
 * the relation's own query, which no read changes.
 */
class BelongsToManyQueryTest {

    /** The pivot aliases every read selects, in the order of the default pivot columns. */
    private static final String ALIASES = "\"role_user\".\"user_id\" as \"pivot_user_id\", "
            + "\"role_user\".\"role_id\" as \"pivot_role_id\", "
            + "\"role_user\".\"status\" as \"pivot_status\", "
            + "\"role_user\".\"weight\" as \"pivot_weight\", "
            + "\"role_user\".\"created_at\" as \"pivot_created_at\", "
            + "\"role_user\".\"updated_at\" as \"pivot_updated_at\"";

    private static final String JOIN = " from \"roles\" inner join \"role_user\" on "
            + "\"roles\".\"id\" = \"role_user\".\"role_id\"";

    private static final String USER_1_WHERE = " where \"role_user\".\"user_id\" = ?";

    private static final String LAZY_SQL = "select \"roles\".*, " + ALIASES + JOIN + USER_1_WHERE;

    private static final String NAME_SQL = "select \"roles\".\"name\", " + ALIASES + JOIN + USER_1_WHERE;

    private static final String NAME_ONLY_SQL = "select \"name\", " + ALIASES + JOIN + USER_1_WHERE;

    private static final String RAW_SQL = "select * from \"roles\" inner join \"role_user\" on \"roles\".\"id\" = "
            + "\"role_user\".\"role_id\"" + USER_1_WHERE;

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        ModelQuery.preventLazyLoading(false);
        db.close();
    }

    @Test
    void lazyShapeSelectsRelatedColumnsAndPivotAliases() {
        BtmModels.User user1 = user(1);
        db.clearLog();

        ModelCollection<BtmModels.Role> roles = user1.roles().get();

        assertEquals(Collections.singletonList(LAZY_SQL), db.sql());
        assertEquals(Collections.singletonList(1), db.queries().get(0).bindings());
        assertEquals(ids(1, 2), idSet(roles));
    }

    @Test
    void postgresGrammarCompilesTheSameLazyShape() {
        BtmModels.User user = BtmModels.User.QUERY.on(postgres()).newModel();
        user.setAttribute("id", 1);

        assertEquals(LAZY_SQL, user.roles().readQuery().toSql());
    }

    @Test
    void nullParentKeyGivesEmptyResultWithoutQuery() {
        BtmModels.User unsaved = BtmModels.User.QUERY.on(db.db).newModel();
        db.clearLog();

        ModelCollection<BtmModels.Role> roles = unsaved.roles().getResults();

        assertTrue(roles.isEmpty());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void getRunsQueryAsWrittenForNullParentKey() {
        BtmModels.User unsaved = BtmModels.User.QUERY.on(db.db).newModel();
        db.clearLog();

        ModelCollection<BtmModels.Role> roles = unsaved.roles().get();

        assertTrue(roles.isEmpty());
        assertEquals(1, db.sql().size());
        assertTrue(db.sql().get(0).contains("\"role_user\".\"user_id\""));
    }

    @Test
    void userWithNoRolesGivesEmptyResultInOneQuery() {
        BtmModels.User user3 = user(3);
        db.clearLog();

        ModelCollection<BtmModels.Role> roles = user3.roles().get();

        assertTrue(roles.isEmpty());
        assertEquals(1, db.sql().size());
    }

    @Test
    void withPivotRepeatingAnAliasAddsNoDuplicate() {
        BtmModels.User user1 = user(1);
        db.clearLog();

        user1.roles().withPivot("user_id").get();

        assertEquals(Collections.singletonList(LAZY_SQL), db.sql());
    }

    @Test
    void defaultSelectPutsRelatedColumnsBeforeAliases() {
        BtmModels.User user1 = user(1);
        db.clearLog();

        user1.roles().get();

        assertTrue(db.sql().get(0).startsWith("select \"roles\".*, \"role_user\".\"user_id\" as \"pivot_user_id\", "));
    }

    @Test
    void selectedUserColumnsReplaceRelatedColumnsAndKeepAliases() {
        BtmModels.User user1 = user(1);
        db.clearLog();

        ModelCollection<BtmModels.Role> roles = user1.roles().select("roles.name").get();

        assertEquals(Collections.singletonList(NAME_SQL), db.sql());
        assertEquals(new HashSet<>(Arrays.asList("admin", "editor")), namesOf(roles));
    }

    @Test
    void getColumnsParameterReplacesRelatedColumnsWhenNothingIsSelected() {
        BtmModels.User user1 = user(1);
        db.clearLog();

        user1.roles().get("name");

        assertEquals(Collections.singletonList(NAME_ONLY_SQL), db.sql());
    }

    @Test
    void getColumnsParameterIgnoredWhenUserSelected() {
        BtmModels.User user1 = user(1);
        db.clearLog();

        user1.roles().select("roles.name").get("id");

        assertEquals(Collections.singletonList(NAME_SQL), db.sql());
    }

    @Test
    void repeatedGetSendsIdenticalSqlAndKeepsRelationQueryRaw() {
        BtmModels.User user1 = user(1);
        BelongsToMany<BtmModels.Role> relation = user1.roles();
        db.clearLog();

        ModelCollection<BtmModels.Role> first = relation.get();
        ModelCollection<BtmModels.Role> second = relation.get();

        assertEquals(Arrays.asList(LAZY_SQL, LAZY_SQL), db.sql());
        assertEquals(idSet(first), idSet(second));
        assertEquals(RAW_SQL, relation.getQuery().toSql());
    }

    @Test
    void getQueryIsRawWithoutAliasesOrHydration() {
        BtmModels.User user1 = user(1);
        db.clearLog();

        ModelCollection<BtmModels.Role> roles = user1.roles().getQuery().get();

        assertEquals(Collections.singletonList(RAW_SQL), db.sql());
        assertNull(Pivot.of(roles.get(0)));
    }

    @Test
    void resultsComeInQueryOrderAndOrderByPivotChangesIt() {
        BtmModels.User user1 = user(1);

        ModelCollection<BtmModels.Role> roles = user1.roles().orderByPivot("weight").get();

        assertEquals(Arrays.asList(2, 1), roles.modelKeys());
    }

    @Test
    void relationOwnConstraintAppliesToLazyQuery() {
        BtmModels.User user1 = user(1);
        db.clearLog();

        ModelCollection<BtmModels.Role> roles = user1.roles().where("roles.name", "admin").get();

        assertEquals(Collections.singletonList(1), roles.modelKeys());
        assertEquals(Collections.singletonList(
                "select \"roles\".*, " + ALIASES + JOIN + USER_1_WHERE + " and \"roles\".\"name\" = ?"),
                db.sql());
    }

    @Test
    void customParentKeyWithNullValueGivesEmptyResultWithoutQuery() {
        BtmModels.Country country = BtmModels.Country.QUERY.on(db.db).newModel();
        db.clearLog();

        ModelCollection<BtmModels.Language> languages = country.languages().getResults();

        assertTrue(languages.isEmpty());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void lazyLoadingPreventionRefusesRelationOnMultiRowResult() {
        ModelQuery.preventLazyLoading(true);
        ModelCollection<BtmModels.User> users = BtmModels.User.QUERY.on(db.db).query().get();
        db.clearLog();

        assertThrows(LazyLoadingViolationException.class, () -> users.get(0).related("roles"));
        assertEquals(Collections.emptyList(), db.sql());
    }

    private BtmModels.User user(int id) {
        return BtmModels.User.QUERY.on(db.db).findOrFail(id);
    }

    private static Set<Object> ids(Object... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    private static Set<Object> idSet(ModelCollection<?> models) {
        return new HashSet<>(models.modelKeys());
    }

    private static Set<Object> namesOf(ModelCollection<BtmModels.Role> roles) {
        Set<Object> names = new HashSet<>();
        for (BtmModels.Role role : roles) {
            names.add(role.getAttribute("name"));
        }
        return names;
    }

    private static QueryFactory postgres() {
        return PostgresQuery.of(stub());
    }

    private static Connection stub() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTablePrefix":
                            return "";
                        case "getName":
                            return "test";
                        case "toString":
                            return "stub connection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
