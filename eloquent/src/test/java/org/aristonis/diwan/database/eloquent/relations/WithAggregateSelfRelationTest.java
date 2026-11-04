package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.relations.RelationExistenceTest.Category;
import org.aristonis.diwan.database.jdbc.JdbcConnectionProvider;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Aggregates on a self relation: the related table is aliased
 * {@code laravel_reserved_N} from the outer builder's counter, and the aggregated column is
 * qualified with that alias. SQL is asserted on the SQLite and the PostgreSQL grammar.
 */
class WithAggregateSelfRelationTest {

    private static final String CHILDREN_0 = "from \"categories\" as \"laravel_reserved_0\" where"
            + " \"categories\".\"id\" = \"laravel_reserved_0\".\"parent_id\"";
    private static final String CHILDREN_1 = "from \"categories\" as \"laravel_reserved_1\" where"
            + " \"categories\".\"id\" = \"laravel_reserved_1\".\"parent_id\"";
    private static final String SELECT = "select \"categories\".*, ";
    private static final String FROM = " from \"categories\"";

    private RelationDb db;
    private QueryFactory postgres;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        postgres = postgresGrammarOnly();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    /** A PostgreSQL factory whose connection is never opened: for SQL text only. */
    static QueryFactory postgresGrammarOnly() {
        Connection connection = Connection.builder().name("pgsql").connectionProvider(new JdbcConnectionProvider() {
            @Override
            public java.sql.Connection acquire() throws SQLException {
                throw new SQLException("SQL text only", "08001");
            }

            @Override
            public void release(java.sql.Connection connection) {
                // nothing was acquired
            }
        }).build();
        return PostgresQuery.of(connection);
    }

    private List<EloquentBuilder<Category>> both() {
        return Arrays.asList(Category.QUERY.on(db.db).query(), Category.QUERY.on(postgres).query());
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> one(String name, Consumer<EloquentBuilder<?>> constraint) {
        Map<String, Consumer<EloquentBuilder<?>>> relations = new LinkedHashMap<>();
        relations.put(name, constraint);
        return relations;
    }

    @Test
    void countAlias() {
        for (EloquentBuilder<Category> query : both()) {
            assertEquals(SELECT + "(select count(*) " + CHILDREN_0 + ") as \"children_count\"" + FROM,
                    query.withCount("children").toSql());
        }
    }

    @Test
    void maxQualified() {
        for (EloquentBuilder<Category> query : both()) {
            assertEquals(SELECT + "(select max(\"laravel_reserved_0\".\"id\") " + CHILDREN_0
                    + ") as \"children_max_id\"" + FROM, query.withMax("children", "id").toSql());
        }
    }

    @Test
    void existsStar() {
        for (EloquentBuilder<Category> query : both()) {
            assertEquals(SELECT + "exists(select * " + CHILDREN_0 + ") as \"children_exists\"" + FROM,
                    query.withExists("children").toSql());
        }
    }

    @Test
    void belongsToSelf() {
        for (EloquentBuilder<Category> query : both()) {
            assertEquals(SELECT + "(select count(*) from \"categories\" as \"laravel_reserved_0\" where"
                    + " \"laravel_reserved_0\".\"id\" = \"categories\".\"parent_id\") as \"parent_count\"" + FROM,
                    query.withCount("parent").toSql());
        }
    }

    @Test
    void counterIncrements() {
        for (EloquentBuilder<Category> query : both()) {
            String sql = query.withCount("children").withCount("children as c2").toSql();

            assertEquals(SELECT + "(select count(*) " + CHILDREN_0 + ") as \"children_count\", (select count(*) "
                    + CHILDREN_1 + ") as \"c2\"" + FROM, sql);
        }
    }

    @Test
    void oneCallWithTwoAliasesIncrements() {
        String sql = Category.QUERY.on(db.db).query().withCount("children", "children as c2").toSql();

        assertEquals(SELECT + "(select count(*) " + CHILDREN_0 + ") as \"children_count\", (select count(*) "
                + CHILDREN_1 + ") as \"c2\"" + FROM, sql);
    }

    @Test
    void sharedWithHas() {
        for (EloquentBuilder<Category> query : both()) {
            String sql = query.has("children").withCount("children").toSql();

            assertEquals(SELECT + "(select count(*) " + CHILDREN_1 + ") as \"children_count\"" + FROM
                    + " where exists (select * " + CHILDREN_0 + ")", sql);
        }
    }

    @Test
    void deterministic() {
        String first = Category.QUERY.on(db.db).query().withCount("children").toSql();
        String second = Category.QUERY.on(db.db).query().withCount("children").toSql();
        EloquentBuilder<Category> twice = Category.QUERY.on(db.db).query().withCount("children");
        String before = twice.toSql();

        twice.withCount("children as again");

        assertEquals(first, second);
        assertEquals(first, before);
        assertNotEquals(before, twice.toSql());
        assertEquals(first, Category.QUERY.on(db.db).query().withCount("children").toSql());
    }

    @Test
    void closureSeesAlias() {
        for (EloquentBuilder<Category> query : both()) {
            EloquentBuilder<Category> counted = query.withCount(
                    one("children", q -> q.where(q.getModel().qualifyColumn("name"), "x")));

            assertEquals(SELECT + "(select count(*) " + CHILDREN_0 + " and (\"laravel_reserved_0\".\"name\" = ?))"
                    + " as \"children_count\"" + FROM, counted.toSql());
            assertEquals(Collections.singletonList("x"), counted.getBindings());
        }
    }

    @Test
    void dottedSelfColumnIsKeptAsWritten() {
        String sql = Category.QUERY.on(db.db).query().withMax("children", "categories.id").toSql();

        assertEquals(SELECT + "(select max(\"categories\".\"id\") " + CHILDREN_0
                + ") as \"children_max_categoriesid\"" + FROM, sql);
    }

    @Test
    void countsRunOnSqlite() {
        db.real.statement("create table categories (id integer primary key, parent_id integer, name varchar(255))");
        db.real.statement("insert into categories (id, parent_id, name) values"
                + " (1, null, 'root'), (2, 1, 'x'), (3, 1, 'y'), (4, 2, 'z')");

        List<Category> rows = Category.QUERY.on(db.db).query().withCount("children").withExists("children")
                .orderBy("id").get();

        assertEquals(Arrays.asList(2L, 1L, 0L, 0L), Arrays.asList(count(rows.get(0)), count(rows.get(1)),
                count(rows.get(2)), count(rows.get(3))));
        assertEquals(Boolean.TRUE, rows.get(0).getAttribute("children_exists"));
        assertEquals(Boolean.FALSE, rows.get(3).getAttribute("children_exists"));
    }

    private static long count(Category category) {
        return ((Number) category.getAttribute("children_count")).longValue();
    }
}
