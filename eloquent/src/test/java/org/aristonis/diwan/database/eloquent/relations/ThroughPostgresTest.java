package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
 * Through relations on a throwaway PostgreSQL: the lazy read SQL, existence queries with aliases,
 * the qualified key in {@code find}, aggregate sub-selects and the savepoint in
 * {@code createOrFirst}. Skipped when the server binaries are missing, and failing when
 * {@code DIWAN_PG_REQUIRED} is set.
 */
class ThroughPostgresTest {

    private ThroughDb db;

    /** A post whose title can be mass assigned. */
    static final class OpenPost extends Model {
        static final ModelQuery<OpenPost> QUERY = ModelQuery.of(OpenPost.class, OpenPost::new);

        @Override
        protected String table() { return "posts"; }

        @Override
        protected boolean timestamps() { return false; }

        @Override
        protected List<String> fillable() { return Collections.singletonList("title"); }
    }

    /** A country that reaches fillable posts through its users. */
    static final class OpenCountry extends Model {
        static final ModelQuery<OpenCountry> QUERY = ModelQuery.of(OpenCountry.class, OpenCountry::new);

        @Override
        protected String table() { return "countries"; }

        @Override
        protected boolean timestamps() { return false; }

        HasManyThrough<OpenPost> posts() {
            return hasManyThrough(OpenPost.class, ThroughModels.User.class, "country_id", "user_id", "id", "id");
        }
    }

    @BeforeEach
    void open() {
        db = ThroughDb.openPostgres();
        db.seed();
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    private static List<Object> keys(List<? extends Model> models) {
        List<Object> keys = new ArrayList<>();
        for (Model model : models) {
            keys.add(((Number) model.getKey()).longValue());
        }
        Collections.sort(keys, (a, b) -> Long.compare((Long) a, (Long) b));
        return keys;
    }

    private static Map<String, Object> title(String value) {
        return Collections.<String, Object>singletonMap("title", value);
    }

    @Test
    void lazyHasManyThroughReadJoinsWithQuotedNames() {
        ThroughModels.Country country = ThroughModels.Country.QUERY.on(db.db).find(1L).get();
        db.log.clear();

        List<ThroughModels.Post> posts = country.posts().get();

        assertEquals(Arrays.<Object>asList(1L, 2L, 3L), keys(posts));
        assertEquals(Collections.singletonList("select \"posts\".*, \"users\".\"country_id\" as \"laravel_through_key\""
                + " from \"posts\" inner join \"users\" on \"users\".\"id\" = \"posts\".\"user_id\""
                + " where \"users\".\"country_id\" = ?"), db.log.sql());
    }

    @Test
    void lazyHasOneThroughReadReturnsTheOwnerOfTheCar() {
        ThroughModels.Mechanic mechanic = ThroughModels.Mechanic.QUERY.on(db.db).find(1L).get();
        db.log.clear();

        ThroughModels.Owner owner = mechanic.owner().getResults();

        assertNotNull(owner);
        assertEquals("Olga", owner.getAttribute("name"));
        assertEquals(Collections.singletonList("select \"owners\".*, \"cars\".\"mechanic_id\" as \"laravel_through_key\""
                + " from \"owners\" inner join \"cars\" on \"cars\".\"id\" = \"owners\".\"car_id\""
                + " where \"cars\".\"mechanic_id\" = ? limit 1"), db.log.sql());
    }

    @Test
    void hasSqlIsAnExistsSubSelectWithTheJoin() {
        EloquentBuilder<ThroughModels.Country> query = ThroughModels.Country.QUERY.on(db.db).query().has("posts");

        assertEquals("select * from \"countries\" where exists (select * from \"posts\" inner join \"users\""
                + " on \"users\".\"id\" = \"posts\".\"user_id\""
                + " where \"countries\".\"id\" = \"users\".\"country_id\")", query.toSql());
        assertEquals(Arrays.<Object>asList(1L, 2L), keys(query.get()));
    }

    @Test
    void selfRelationAliasesTheRelatedTable() {
        EloquentBuilder<ThroughModels.Staff> query = ThroughModels.Staff.QUERY.on(db.db).query().has("subordinates");

        assertTrue(query.toSql().contains("\"staff\" as \"laravel_reserved_0\""), query.toSql());
        assertEquals(Arrays.<Object>asList(1L, 3L), keys(query.get()));
    }

    @Test
    void throughSelfRelationAliasesTheThroughTable() {
        EloquentBuilder<ThroughModels.Person> query = ThroughModels.Person.QUERY.on(db.db).query().has("reportPosts");

        assertTrue(query.toSql().contains("\"people\" as \"laravel_reserved_0\""), query.toSql());
        assertEquals(Arrays.<Object>asList(1L), keys(query.get()));
    }

    @Test
    void findQualifiesTheKeyAgainstTheJoinedTables() {
        ThroughModels.Country country = ThroughModels.Country.QUERY.on(db.db).find(1L).get();
        db.log.clear();

        ThroughModels.Post post = country.posts().find(3L).get();

        assertEquals(3L, ((Number) post.getKey()).longValue());
        assertTrue(db.log.sql().get(0).contains("\"posts\".\"id\" = ?"), db.log.sql().get(0));
        assertTrue(country.posts().find(4L).isEmpty());
    }

    @Test
    void aggregatesUseTheJoinedSubSelect() {
        List<ThroughModels.Country> found = ThroughModels.Country.QUERY.on(db.db).query().withCount("posts")
                .withSum("posts", "id").withMax("posts", "id").orderBy("id").get();

        assertEquals(3, found.size());
        assertEquals(3L, ((Number) found.get(0).getAttribute("posts_count")).longValue());
        assertEquals(6L, ((Number) found.get(0).getAttribute("posts_sum_id")).longValue());
        assertEquals(3L, ((Number) found.get(0).getAttribute("posts_max_id")).longValue());
        assertEquals(0L, ((Number) found.get(2).getAttribute("posts_count")).longValue());
    }

    @Test
    void createOrFirstUsesASavepointInsideATransaction() {
        db.real.statement("create unique index posts_title_unique on posts (title)");
        db.real.statement("select setval('posts_id_seq', (select max(id) from posts))");
        long survivors = db.real.transaction(c -> {
            OpenCountry country = OpenCountry.QUERY.on(PostgresQuery.of(c)).find(1L).get();
            OpenPost existing = country.posts().createOrFirst(title("Post 1"));
            OpenPost created = country.posts().createOrFirst(title("Fresh"));
            assertEquals(1L, ((Number) existing.getKey()).longValue());
            assertNotNull(created.getKey());
            return ((Number) c.selectOne("select count(*) as v from posts").get().get("v")).longValue();
        });

        assertEquals(7L, survivors);
    }
}
