package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.eloquent.page.ModelPage;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.Expression;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code with*} aggregate methods of the Eloquent builder. */
class WithAggregateTest {

    private static final String POSTS_OF_USER = "from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\"";
    private static final String COUNT_POSTS = "(select count(*) " + POSTS_OF_USER + ") as \"posts_count\"";
    private static final String WITH_COUNT = "select \"users\".*, " + COUNT_POSTS + " from \"users\"";
    private static final String PLAIN = "select * from \"users\"";

    /** A writer over the users table whose relation name is camel case. */
    static final class Writer extends Model {
        static final ModelQuery<Writer> QUERY = ModelQuery.of(Writer.class, Writer::new);

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
            return RelationMap.of(Writer.class).add("blogPosts", Writer::blogPosts);
        }

        public HasMany<Post> blogPosts() {
            return hasMany(Post.class, "user_id");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("users", Map.of("id", 3, "name", "Cid"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<User> users() {
        return User.QUERY.on(db.db).query();
    }

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    // ---- SQL shapes

    @Test
    void defaultsToTableStar() {
        assertEquals(WITH_COUNT, users().withCount("posts").toSql());
    }

    @Test
    void sameShapeOnPostgresGrammar() {
        QueryFactory pg = PostgresQuery.of(db.real);

        assertEquals(WITH_COUNT, User.QUERY.on(pg).query().withCount("posts").toSql());
    }

    @Test
    void keepsExistingColumns() {
        assertEquals("select \"id\", " + COUNT_POSTS + " from \"users\"",
                users().select("id").withCount("posts").toSql());
    }

    @Test
    void selectAfterDropsAggregate() {
        assertEquals("select \"id\" from \"users\"", users().withCount("posts").select("id").toSql());
    }

    @Test
    void addSelectAfterKeepsAggregate() {
        assertEquals("select \"users\".*, " + COUNT_POSTS + ", \"name\" from \"users\"",
                users().withCount("posts").addSelect("name").toSql());
    }

    @Test
    void aliasIsSnakeCase() {
        assertTrue(Writer.QUERY.on(db.db).query().withCount("blogPosts").toSql()
                .endsWith(" as \"blog_posts_count\" from \"users\""));
    }

    @Test
    void maxAlias() {
        assertEquals("select \"users\".*, (select max(\"posts\".\"id\") " + POSTS_OF_USER
                + ") as \"posts_max_id\" from \"users\"", users().withMax("posts", "id").toSql());
    }

    @Test
    void minSumAvgAliases() {
        assertTrue(users().withMin("posts", "id").toSql().contains("min(\"posts\".\"id\")"));
        assertTrue(users().withSum("posts", "id").toSql().contains(") as \"posts_sum_id\""));
        assertTrue(users().withAvg("posts", "id").toSql().contains("avg(\"posts\".\"id\")"));
    }

    @Test
    void mapFormsTakeConstraints() {
        Map<String, Consumer<EloquentBuilder<?>>> titled = Collections.singletonMap("posts",
                q -> q.where("title", "a"));

        assertTrue(users().withMax(titled, "id").toSql().contains("max(\"posts\".\"id\")"));
        assertTrue(users().withMin(titled, "id").toSql().contains("min(\"posts\".\"id\")"));
        assertTrue(users().withSum(titled, "id").toSql().contains("sum(\"posts\".\"id\")"));
        assertTrue(users().withAvg(titled, "id").toSql().contains("avg(\"posts\".\"id\")"));
        assertTrue(users().withAggregate(titled, "id", "max").toSql().contains("max(\"posts\".\"id\")"));
        assertEquals(Collections.singletonList("a"), users().withExists(titled).getBindings());
    }

    @Test
    void explicitAliasKeepsCase() {
        assertTrue(users().withCount("posts as Total").toSql().endsWith(") as \"Total\" from \"users\""));
    }

    @Test
    void noFunctionTakesOneRow() {
        assertEquals("select \"users\".*, (select \"posts\".\"title\" " + POSTS_OF_USER
                + " limit 1) as \"posts_title\" from \"users\"", users().withAggregate("posts", "title").toSql());
    }

    @Test
    void nullFunctionEqualsNoFunction() {
        assertEquals(users().withAggregate("posts", "title").toSql(),
                users().withAggregate("posts", "title", null).toSql());
    }

    @Test
    void existsIsBoolean() {
        EloquentBuilder<User> query = users().withExists("posts").orderBy("id");

        assertEquals("select \"users\".*, exists(select * " + POSTS_OF_USER
                + ") as \"posts_exists\" from \"users\" order by \"id\" asc", query.toSql());
        ModelCollection<User> found = query.get();
        assertEquals(Boolean.TRUE, found.get(0).getAttribute("posts_exists"));
        assertEquals(Boolean.FALSE, found.get(2).getAttribute("posts_exists"));
    }

    @Test
    void belongsToShape() {
        assertEquals("select \"posts\".*, (select count(*) from \"users\" where \"posts\".\"user_id\""
                + " = \"users\".\"id\") as \"author_count\" from \"posts\"",
                Post.QUERY.on(db.db).query().withCount("author").toSql());
    }

    @Test
    void bindingsGoSelectBeforeWhere() {
        EloquentBuilder<User> query = users().where("name", "Ann")
                .withCount(Collections.singletonMap("posts", q -> q.where("title", "a")));

        assertEquals("select \"users\".*, (select count(*) " + POSTS_OF_USER
                + " and \"title\" = ?) as \"posts_count\" from \"users\" where \"name\" = ?", query.toSql());
        assertEquals(Arrays.asList("a", "Ann"), query.getBindings());
    }

    // ---- refusals

    @Test
    void unknownRelation() {
        EloquentBuilder<User> query = users();

        assertThrows(RelationNotFoundException.class, () -> query.withCount("postz"));
        assertEquals(PLAIN, query.toSql());
    }

    @Test
    void nestedPathRejected() {
        EloquentBuilder<User> query = users();

        assertThrows(RelationNotFoundException.class, () -> query.withCount("posts", "posts.comments"));
        assertEquals(PLAIN, query.toSql());
    }

    @Test
    void badFunctionRejected() {
        assertThrows(IllegalArgumentException.class, () -> users().withAggregate("posts", "id", "group_concat"));
    }

    @Test
    void functionCaseInsensitive() {
        assertTrue(users().withAggregate("posts", "id", "MAX").toSql().contains("max(\"posts\".\"id\")"));
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> users().withMax("posts", (String) null));
        assertThrows(NullPointerException.class, () -> users().withSum("posts", (Expression) null));
        assertThrows(NullPointerException.class, () -> users().withAggregate((String) null, "id"));
    }

    @Test
    void expressionColumn() {
        String sql = users().withSum("posts", Expression.raw("price * qty")).toSql();

        assertTrue(sql.contains("(select sum(price * qty) " + POSTS_OF_USER + ") as \"posts_sum_price_qty\""), sql);
    }

    @Test
    void expressionFormsOfEveryFunction() {
        Expression id = Expression.raw("id");

        assertTrue(users().withMax("posts", id).toSql().contains("max(id)"));
        assertTrue(users().withMin("posts", id).toSql().contains("min(id)"));
        assertTrue(users().withAvg("posts", id).toSql().contains("avg(id)"));
        assertTrue(users().withAggregate("posts", id, "count").toSql().contains("count(id)"));
    }

    @Test
    void emptyListNoop() {
        assertEquals(PLAIN, users().withCount().toSql());
        assertEquals(PLAIN, users().withExists(Collections.emptyMap()).toSql());
    }

    // ---- reads

    @Test
    void paginateTotalIgnoresAggregates() {
        db.clearLog();

        ModelPage<User> page = users().withCount("posts").orderBy("id").paginate(2, 1);

        assertEquals(3, page.total());
        assertEquals(2, number(page.items().get(0), "posts_count"));
        String totalQuery = db.sql().get(0);
        assertFalse(totalQuery.contains("posts_count"), totalQuery);
    }

    @Test
    void countIgnoresAggregates() {
        assertEquals(3, users().withCount("posts").count());
    }

    @Test
    void existsAndPluckKeepSubselects() {
        db.clearLog();

        assertTrue(users().withCount("posts").exists());
        assertTrue(db.sql().get(0).contains("posts_count"));
        List<Long> counts = new ArrayList<>();
        for (Object value : users().withCount("posts").orderBy("id").pluck("posts_count")) {
            counts.add(((Number) value).longValue());
        }
        assertEquals(Arrays.asList(2L, 1L, 0L), counts);
    }

    @Test
    void withEagerLoadTogether() {
        db.clearLog();

        User ann = users().withCount("posts").with("posts").orderBy("id").first().orElseThrow();

        assertEquals(2, number(ann, "posts_count"));
        assertEquals(2, ((ModelCollection<?>) ann.getRelation("posts")).size());
        String eager = db.sql().get(1);
        assertFalse(eager.contains("posts_count"), eager);
    }

    @Test
    void everyReadPathCarriesAggregates() {
        assertEquals(2, number(users().withCount("posts").orderBy("id").first().orElseThrow(), "posts_count"));
        assertEquals(1, number(users().withCount("posts").find(2).orElseThrow(), "posts_count"));
        List<Long> chunked = new ArrayList<>();
        users().withCount("posts").orderBy("id").chunk(2, models -> {
            for (int i = 0; i < models.size(); i++) {
                chunked.add(number(models.get(i), "posts_count"));
            }
            return true;
        });
        assertEquals(Arrays.asList(2L, 1L, 0L), chunked);
        List<Long> lazy = new ArrayList<>();
        for (User user : users().withCount("posts").orderBy("id").lazy(2)) {
            lazy.add(number(user, "posts_count"));
        }
        assertEquals(Arrays.asList(2L, 1L, 0L), lazy);
        List<Long> cursor = new ArrayList<>();
        try (QueryCursor<User> rows = users().withCount("posts").orderBy("id").cursor()) {
            for (User user : rows) {
                cursor.add(number(user, "posts_count"));
            }
        }
        assertEquals(Arrays.asList(2L, 1L, 0L), cursor);
    }

    @Test
    void aggregateNotDirty() {
        User ann = users().withCount("posts").orderBy("id").first().orElseThrow();

        assertFalse(ann.isDirty());
        assertTrue(ann.toMap().containsKey("posts_count"));
    }

    @Test
    void sameRelationUnderTwoAliases() {
        User ann = users().withCount("posts as all_posts")
                .withCount(Collections.singletonMap("posts as a_posts", q -> q.where("title", "a")))
                .orderBy("id").first().orElseThrow();

        assertEquals(2, number(ann, "all_posts"));
        assertEquals(1, number(ann, "a_posts"));
    }
}
