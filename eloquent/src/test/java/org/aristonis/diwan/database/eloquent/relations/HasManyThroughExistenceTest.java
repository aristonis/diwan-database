package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Existence constraints on a has-many-through relation: the sub-select shape on both grammars,
 * the aliasing of the related table or the through table when it is also the outer table, an
 * aliased outer query, nested paths, and the closure rules. Self and through-self cases read the
 * rows back as well as the SQL.
 */
class HasManyThroughExistenceTest {

    private static final String COUNTRIES = "select * from \"countries\" where ";
    private static final String POSTS_OF_COUNTRY = "from \"posts\" inner join \"users\""
            + " on \"users\".\"id\" = \"posts\".\"user_id\""
            + " where \"countries\".\"id\" = \"users\".\"country_id\"";
    private static final String EXISTS_POSTS = "exists (select * " + POSTS_OF_COUNTRY + ")";
    private static final String STAFF = "select * from \"staff\" where ";
    private static final String SUBORDINATES_0 = "from \"staff\" as \"laravel_reserved_0\" inner join \"teams\""
            + " on \"teams\".\"id\" = \"laravel_reserved_0\".\"team_id\""
            + " where \"staff\".\"id\" = \"teams\".\"lead_id\"";

    private ThroughDb db;

    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
        db.seed();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<ThroughModels.Country> countries() {
        return ThroughModels.Country.QUERY.on(db.db).query();
    }

    private EloquentBuilder<ThroughModels.Staff> staff() {
        return ThroughModels.Staff.QUERY.on(db.db).query();
    }

    private EloquentBuilder<ThroughModels.Person> people() {
        return ThroughModels.Person.QUERY.on(db.db).query();
    }

    private static List<Long> keys(List<? extends Model> models) {
        List<Long> keys = new java.util.ArrayList<>();
        for (Model model : models) {
            keys.add(((Number) model.getKey()).longValue());
        }
        Collections.sort(keys);
        return keys;
    }

    private static List<Long> longs(long... values) {
        List<Long> list = new java.util.ArrayList<>();
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    @Test
    void hasSql() {
        EloquentBuilder<ThroughModels.Country> query = countries().has("posts");

        assertEquals(COUNTRIES + EXISTS_POSTS, query.toSql());
        assertEquals(longs(1, 2), keys(query.get()));
    }

    @Test
    void hasSqlOnThePostgresGrammar() {
        EloquentBuilder<ThroughModels.Country> query =
                ThroughModels.Country.QUERY.on(PostgresQuery.of(db.real)).query().has("posts");

        assertEquals(COUNTRIES + EXISTS_POSTS, query.toSql());
    }

    @Test
    void hasTwiceGivesIdenticalSql() {
        ThroughModels.Country country = ThroughModels.Country.QUERY.on(db.db).findOrFail(1);
        String before = country.relation("posts").getQuery().toSql();

        String one = countries().has("posts").toSql();
        String two = countries().has("posts").toSql();
        String selfOne = staff().has("subordinates").toSql();
        String selfTwo = staff().has("subordinates").toSql();

        assertEquals(one, two);
        assertEquals(selfOne, selfTwo);
        assertEquals(before, country.relation("posts").getQuery().toSql());
        assertFalse(before.contains("laravel_reserved"), before);
    }

    @Test
    void doesntHaveAndOrHaveWork() {
        EloquentBuilder<ThroughModels.Country> none = countries().doesntHave("posts");
        EloquentBuilder<ThroughModels.Country> either = countries().where("name", "Japan").orHas("posts");
        EloquentBuilder<ThroughModels.Country> neither = countries().has("posts").orDoesntHave("posts");

        assertEquals(COUNTRIES + "not exists (select * " + POSTS_OF_COUNTRY + ")", none.toSql());
        assertEquals(longs(3), keys(none.get()));
        assertEquals(COUNTRIES + "\"name\" = ? or " + EXISTS_POSTS, either.toSql());
        assertEquals(longs(1, 2, 3), keys(either.get()));
        assertEquals(longs(1, 2, 3), keys(neither.get()));
    }

    @Test
    void nestedDotPathWorks() {
        EloquentBuilder<ThroughModels.Country> query = countries().has("users.posts");

        String sql = query.toSql();
        assertTrue(sql.contains("exists (select * from \"users\" where \"countries\".\"id\" = \"users\".\"country_id\""
                + " and (exists (select * from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\")))"), sql);
        assertEquals(longs(1, 2), keys(query.get()));
    }

    @Test
    void whereHasConstraintFiltersTheFarRows() {
        EloquentBuilder<ThroughModels.Country> query =
                countries().whereHas("posts", q -> q.where("title", "Post 4"));

        assertEquals(COUNTRIES + "exists (select * " + POSTS_OF_COUNTRY + " and (\"title\" = ?))", query.toSql());
        assertEquals(Collections.singletonList("Post 4"), query.getBindings());
        assertEquals(longs(2), keys(query.get()));
    }

    @Test
    void selfRelationAliasesTheRelatedTable() {
        EloquentBuilder<ThroughModels.Staff> query = staff().has("subordinates");

        assertEquals(STAFF + "exists (select * " + SUBORDINATES_0 + ")", query.toSql());
        assertEquals(longs(1, 3), keys(query.get()));
    }

    @Test
    void selfRelationDoesntHaveReturnsTheRest() {
        EloquentBuilder<ThroughModels.Staff> query = staff().doesntHave("subordinates");

        assertEquals(STAFF + "not exists (select * " + SUBORDINATES_0 + ")", query.toSql());
        assertEquals(longs(2, 4), keys(query.get()));
    }

    @Test
    void selfRelationConstraintSeesTheAlias() {
        EloquentBuilder<ThroughModels.Staff> query = staff().whereHas("subordinates",
                q -> q.where(q.getModel().qualifyColumn("name"), "Sam"));

        assertEquals(STAFF + "exists (select * " + SUBORDINATES_0
                + " and (\"laravel_reserved_0\".\"name\" = ?))", query.toSql());
        assertEquals(longs(1), keys(query.get()));
    }

    @Test
    void nestedSelfRelationUsesTwoAliases() {
        EloquentBuilder<ThroughModels.Staff> query = staff().has("subordinates.subordinates");

        String sql = query.toSql();
        assertTrue(sql.contains("\"laravel_reserved_0\""), sql);
        assertTrue(sql.contains("\"laravel_reserved_1\""), sql);
        assertTrue(sql.contains("\"laravel_reserved_0\".\"id\" = \"teams\".\"lead_id\""), sql);
        assertTrue(sql.contains("\"laravel_reserved_1\".\"team_id\""), sql);
        assertEquals(longs(1, 3), keys(query.get()));
    }

    @Test
    void nestedSelfRelationFindsOnlyRowsWithBothHops() {
        db.real.statement("update teams set lead_id = 2 where id = 2");

        EloquentBuilder<ThroughModels.Staff> query = staff().has("subordinates.subordinates");

        assertEquals(longs(1), keys(query.get()));
    }

    @Test
    void throughSelfRelationAliasesTheThroughTable() {
        EloquentBuilder<ThroughModels.Person> query = people().has("reportPosts");

        assertEquals("select * from \"people\" where exists (select * from \"posts\" inner join \"people\""
                + " as \"laravel_reserved_0\" on \"laravel_reserved_0\".\"id\" = \"posts\".\"person_id\""
                + " where \"people\".\"id\" = \"laravel_reserved_0\".\"manager_id\")", query.toSql());
        assertEquals(longs(1), keys(query.get()));
    }

    @Test
    void throughSelfRelationDoesntHaveReturnsTheRest() {
        EloquentBuilder<ThroughModels.Person> query = people().doesntHave("reportPosts");

        assertTrue(query.toSql().contains("not exists (select * from \"posts\""), query.toSql());
        assertEquals(longs(2, 3), keys(query.get()));
    }

    @Test
    void outerAliasIsHonoured() {
        EloquentBuilder<ThroughModels.Country> query =
                countries().query(b -> b.from("countries as c")).has("posts");

        assertEquals("select * from \"countries\" as \"c\" where exists (select * from \"posts\" inner join"
                + " \"users\" on \"users\".\"id\" = \"posts\".\"user_id\""
                + " where \"c\".\"id\" = \"users\".\"country_id\")", query.toSql());
        assertEquals(longs(1, 2), keys(query.get()));
    }

    @Test
    void outerAliasIsHonouredOnASelfRelation() {
        EloquentBuilder<ThroughModels.Staff> query = staff().query(b -> b.from("staff as s")).has("subordinates");

        assertEquals("select * from \"staff\" as \"s\" where exists (select * " + "from \"staff\" as"
                + " \"laravel_reserved_0\" inner join \"teams\" on \"teams\".\"id\" = \"laravel_reserved_0\".\"team_id\""
                + " where \"s\".\"id\" = \"teams\".\"lead_id\")", query.toSql());
        assertEquals(longs(1, 3), keys(query.get()));
    }

    @Test
    void joinInsideConstraintIsRefused() {
        assertThrows(IllegalStateException.class, () -> countries().whereHas("posts",
                q -> q.join("teams", "teams.id", "=", "posts.id")));
    }

    @Test
    void limitInsideConstraintIsDropped() {
        EloquentBuilder<ThroughModels.Country> query = countries().whereHas("posts", q -> q.limit(1));

        assertFalse(query.toSql().contains("limit"), query.toSql());
        assertEquals(Arrays.asList(1L, 2L), keys(query.get()));
    }
}
