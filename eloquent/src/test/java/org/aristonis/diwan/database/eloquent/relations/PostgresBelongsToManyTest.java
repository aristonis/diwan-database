package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.CursorModelPage;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelNotFoundException;
import org.aristonis.diwan.database.eloquent.ModelPage;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.eloquent.SimpleModelPage;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * belongsToMany reads executed on a throwaway PostgreSQL: the lazy shape, driver types of the
 * pivot columns, eager loads with inlined and bound keys, existence, the self relation, aggregates,
 * finders, pages and chunks. Skipped when the server binaries are missing, and failing when
 * {@code DIWAN_PG_REQUIRED} is set. The schema mirrors the SQLite fixture, with PostgreSQL column
 * types: {@code numeric} weight and {@code timestamp} created and updated columns.
 */
class PostgresBelongsToManyTest {

    private static final int STRING_KEY_COUNT = 2_000;

    /** The countries the fixture seeds, which count toward {@link #STRING_KEY_COUNT}. */
    private static final int SEEDED_COUNTRY_COUNT = 2;

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

    private final QueryLog log = new QueryLog();
    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void open() {
        connection = PostgresServer.newDatabase();
        db = PostgresQuery.of(log.wrap(connection));
        BtmModels.register();
        createTables();
        seedFixture();
    }

    private void createTables() {
        connection.statement("create table users (id bigserial primary key, name varchar(255))");
        connection.statement("create table roles (id bigserial primary key, name varchar(255))");
        connection.statement("create table role_user (user_id bigint not null, role_id bigint not null,"
                + " status varchar(255), weight numeric, created_at timestamp, updated_at timestamp)");
        connection.statement("create table user_user (user_id bigint not null, friend_id bigint not null,"
                + " status varchar(255))");
        connection.statement("create table countries (code varchar(255) primary key, name varchar(255))");
        connection.statement("create table languages (iso varchar(255) primary key, name varchar(255))");
        connection.statement("create table country_language (country_code varchar(255),"
                + " language_iso varchar(255))");
    }

    private void seedFixture() {
        connection.statement("insert into users (id, name) values (1, 'User 1'), (2, 'User 2'),"
                + " (3, 'User 3'), (4, 'User 4')");
        connection.statement("insert into roles (id, name) values (1, 'admin'), (2, 'editor')");
        connection.statement("insert into role_user (user_id, role_id, status, weight, created_at, updated_at)"
                + " values (1, 1, 'active', 5, '2024-01-01 10:00:00', '2024-01-01 10:00:00'),"
                + " (1, 2, 'pending', 1, '2024-01-01 11:00:00', '2024-01-01 11:00:00'),"
                + " (2, 1, 'active', 3, '2024-01-01 12:00:00', '2024-01-01 12:00:00')");
        connection.statement("insert into user_user (user_id, friend_id, status) values (1, 2, 'active'),"
                + " (2, 1, 'active')");
        connection.statement("insert into languages (iso, name) values ('en', 'English'), ('fr', 'French')");
        connection.statement("insert into countries (code, name) values ('US', 'United States'), ('FR', 'France')");
        connection.statement("insert into country_language (country_code, language_iso)"
                + " values ('US', 'en'), ('FR', 'fr')");
    }

    // ---- lazy reads

    @Test
    void lazyShape() {
        BtmModels.User ann = user(1);
        log.clear();

        ModelCollection<BtmModels.Role> roles = ann.roles().get();

        assertEquals(Collections.singletonList(LAZY_SQL), log.sql());
        assertEquals(Collections.singletonList(1), log.bindingCounts());
        assertEquals(Arrays.asList(1L, 2L), sorted(keysOf(roles)));
    }

    @Test
    void driverTypesKept() {
        BtmModels.Role admin = roleWithId(user(1).roles().get(), 1);
        Pivot pivot = pivotOf(admin);

        assertEquals("active", pivot.getAttribute("status"));
        assertEquals(Long.class, pivot.getAttribute("user_id").getClass());
        assertEquals(BigDecimal.class, pivot.getAttribute("weight").getClass());
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) pivot.getAttribute("weight")));
        assertEquals(LocalDateTime.class, pivot.getAttribute("created_at").getClass());
        assertEquals(LocalDateTime.parse("2024-01-01T10:00:00"), pivot.getAttribute("created_at"));
        assertDriverValue("select weight as v from role_user where user_id = 1 and role_id = 1",
                pivot.getAttribute("weight"));
        assertDriverValue("select created_at as v from role_user where user_id = 1 and role_id = 1",
                pivot.getAttribute("created_at"));
        assertDriverValue("select updated_at as v from role_user where user_id = 1 and role_id = 1",
                pivot.getAttribute("updated_at"));
    }

    // ---- eager loads

    @Test
    void eager() {
        log.clear();

        ModelCollection<BtmModels.User> users = users().with("roles").orderBy("id").get();

        assertEquals(2, log.sql().size());
        assertEquals(Arrays.asList(0, 0), log.bindingCounts());
        assertTrue(log.sql().get(1).contains("\"role_user\".\"user_id\" in (1, 2, 3, 4)"), log.sql().get(1));
        ModelCollection<BtmModels.Role> annRoles = users.get(0).relatedMany("roles");
        assertEquals(Arrays.asList(1L, 2L), sorted(keysOf(annRoles)));
        assertEquals("pending", pivotOf(roleWithId(annRoles, 2)).getAttribute("status"));
        assertEquals(Collections.singletonList(1L), keysOf(users.get(1).<BtmModels.Role>relatedMany("roles")));
        assertTrue(users.get(2).<BtmModels.Role>relatedMany("roles").isEmpty());
        assertTrue(users.get(3).<BtmModels.Role>relatedMany("roles").isEmpty());
    }

    @Test
    void stringKeysLoadInOneQuery() {
        connection.statement("insert into countries (code, name) select 'C' || lpad(g::text, 4, '0'), 'Country'"
                + " from generate_series(0, " + (STRING_KEY_COUNT - SEEDED_COUNTRY_COUNT - 1) + ") g");
        connection.statement("insert into country_language (country_code, language_iso)"
                + " select code, 'en' from countries where code like 'C%'");
        log.clear();

        ModelCollection<BtmModels.Country> countries = BtmModels.Country.QUERY.on(db).query()
                .with("languages").get();

        assertEquals(STRING_KEY_COUNT, countries.size());
        assertEquals(2, log.sql().size());
        assertTrue(log.sql().get(1).startsWith("select \"languages\".*"), log.sql().get(1));
        assertEquals(Arrays.asList(0, STRING_KEY_COUNT), log.bindingCounts());
        for (BtmModels.Country country : countries) {
            ModelCollection<BtmModels.Language> languages = country.relatedMany("languages");
            assertEquals(1, languages.size());
            assertEquals(country.getKey(), pivotOf(languages.get(0)).getAttribute("country_code"));
        }
    }

    // ---- existence

    @Test
    void has() {
        ModelCollection<BtmModels.User> withRoles = users().has("roles").orderBy("id").get();
        ModelCollection<BtmModels.User> pending = users()
                .whereHas("roles", q -> q.where("role_user.status", "pending"))
                .orderBy("id").get();

        assertEquals(Arrays.asList(1L, 2L), keysOf(withRoles));
        assertEquals(Collections.singletonList(1L), keysOf(pending));
    }

    @Test
    void selfRelationFriendsCountsAndFilters() {
        ModelCollection<BtmModels.User> counted = users().withCount("friends").orderBy("id").get();
        BtmModels.User ann = counted.get(0);

        assertEquals(1L, number(ann, "friends_count"));
        assertEquals(1L, number(counted.get(1), "friends_count"));
        assertEquals(0L, number(counted.get(2), "friends_count"));
        assertDriverValue("select count(*) as v from user_user where user_id = 1", ann.getAttribute("friends_count"));
        assertEquals(Arrays.asList(1L, 2L), keysOf(users().has("friends").orderBy("id").get()));
    }

    // ---- aggregates

    @Test
    void aggregates() {
        ModelCollection<BtmModels.User> users = users().withCount("roles")
                .withSum("roles", "role_user.weight").withExists("roles").orderBy("id").get();
        BtmModels.User ann = users.get(0);

        assertEquals(2L, number(ann, "roles_count"));
        assertEquals(6L, number(ann, "roles_sum_role_userweight"));
        assertDriverValue("select count(*) as v from role_user where user_id = 1", ann.getAttribute("roles_count"));
        assertDriverValue("select sum(weight) as v from role_user where user_id = 1",
                ann.getAttribute("roles_sum_role_userweight"));
        assertEquals(Boolean.TRUE, ann.getAttribute("roles_exists"));
        assertEquals(0L, number(users.get(3), "roles_count"));
        assertNull(users.get(3).getAttribute("roles_sum_role_userweight"));
        assertEquals(Boolean.FALSE, users.get(3).getAttribute("roles_exists"));
    }

    // ---- finders

    @Test
    void finders() {
        BtmModels.User ann = user(1);

        Optional<BtmModels.Role> pending = ann.roles().find(2);
        ModelCollection<BtmModels.Role> distinct = ann.roles().findOrFail(Arrays.asList(1, 1, 2));

        assertTrue(pending.isPresent());
        assertEquals("pending", pivotOf(pending.get()).getAttribute("status"));
        assertFalse(ann.roles().find(99).isPresent());
        assertEquals(Arrays.asList(1L, 2L), sorted(keysOf(distinct)));
        assertThrows(ModelNotFoundException.class, () -> ann.roles().findOrFail(99));
        assertThrows(ModelNotFoundException.class, () -> ann.roles().findOrFail(Arrays.asList(1, 99)));
    }

    // ---- pages

    @Test
    void pages() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("roles.id");

        ModelPage<BtmModels.Role> first = roles.paginate(1);
        ModelPage<BtmModels.Role> second = roles.paginate(1, 2);
        SimpleModelPage<BtmModels.Role> simple = roles.simplePaginate(1);
        CursorModelPage<BtmModels.Role> cursor = roles.cursorPaginate(1);

        assertEquals(Collections.singletonList(1L), keysOf(first.items()));
        assertEquals("active", pivotOf(first.items().get(0)).getAttribute("status"));
        assertEquals(2L, first.total());
        assertTrue(first.hasMorePages());
        assertEquals(Collections.singletonList(2L), keysOf(second.items()));
        assertEquals("pending", pivotOf(second.items().get(0)).getAttribute("status"));
        assertFalse(second.hasMorePages());
        assertEquals(Collections.singletonList(1L), keysOf(simple.items()));
        assertTrue(simple.hasMorePages());
        assertEquals(Collections.singletonList(1L), keysOf(cursor.items()));
        assertTrue(cursor.nextCursor().isPresent());
    }

    @Test
    void cursorPaginateOrdersByPivotAliasAcrossPages() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles().orderBy("pivot_weight");

        CursorModelPage<BtmModels.Role> first = roles.cursorPaginate(1);
        CursorModelPage<BtmModels.Role> second = roles.cursorPaginate(1, first.nextCursor().get());

        assertEquals(Collections.singletonList(2L), keysOf(first.items()));
        BigDecimal firstWeight = (BigDecimal) pivotOf(first.items().get(0)).getAttribute("weight");
        BigDecimal secondWeight = (BigDecimal) pivotOf(second.items().get(0)).getAttribute("weight");
        assertEquals(0, new BigDecimal("1").compareTo(firstWeight));
        assertTrue(first.nextCursor().isPresent());
        assertEquals(Collections.singletonList(1L), keysOf(second.items()));
        assertEquals(0, new BigDecimal("5").compareTo(secondWeight));
        assertFalse(second.nextCursor().isPresent());
    }

    // ---- chunks

    @Test
    void chunkByIdVisitsEachRelatedRowInKeyOrder() {
        BelongsToMany<BtmModels.Role> roles = user(1).roles();
        List<Long> visited = new ArrayList<>();
        List<Object> pivotStatuses = new ArrayList<>();

        boolean completed = roles.chunkById(1, page -> {
            for (BtmModels.Role role : page) {
                visited.add(keyOf(role));
                pivotStatuses.add(pivotOf(role).getAttribute("status"));
            }
            return true;
        });

        assertTrue(completed);
        assertEquals(Arrays.asList(1L, 2L), visited);
        assertEquals(Arrays.asList("active", "pending"), pivotStatuses);
    }

    // ---- helpers

    private BtmModels.User user(int id) {
        return BtmModels.User.QUERY.on(db).findOrFail(id);
    }

    private EloquentBuilder<BtmModels.User> users() {
        return BtmModels.User.QUERY.on(db).query();
    }

    private Object cell(String sql) {
        Object value = connection.selectOne(sql).orElseThrow(IllegalStateException::new).get("v");
        assertNotNull(value, sql);
        return value;
    }

    /** Asserts that a value read through a model has the Java type and value of the column read raw. */
    private void assertDriverValue(String sql, Object actual) {
        Object raw = cell(sql);
        assertNotNull(actual, sql);
        assertEquals(raw.getClass(), actual.getClass(), sql);
        assertEquals(raw, actual, sql);
    }

    private static Pivot pivotOf(Model related) {
        Pivot pivot = Pivot.of(related);
        assertNotNull(pivot, "pivot of a related model");
        return pivot;
    }

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    private static long keyOf(Model model) {
        return ((Number) model.getKey()).longValue();
    }

    private static List<Long> keysOf(Iterable<? extends Model> models) {
        List<Long> keys = new ArrayList<>();
        for (Model model : models) {
            keys.add(keyOf(model));
        }
        return keys;
    }

    private static List<Long> sorted(List<Long> keys) {
        List<Long> copy = new ArrayList<>(keys);
        Collections.sort(copy);
        return copy;
    }

    private static BtmModels.Role roleWithId(Iterable<BtmModels.Role> roles, long id) {
        for (BtmModels.Role role : roles) {
            if (keyOf(role) == id) {
                return role;
            }
        }
        return fail("role " + id + " is not in the collection");
    }
}
