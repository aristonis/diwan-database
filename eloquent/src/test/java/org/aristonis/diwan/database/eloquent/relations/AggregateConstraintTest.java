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
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.relations.ChainedFactoryTest.Blogger;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.query.Expression;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The aggregate constraint closure, the function allow-list and the column
 * forms: the closure runs on the aggregate subselect with the rules of {@code whereHas}, so joins are
 * allowed, its wheres are grouped, its columns, orders, limit and offset are cleared, and a groupBy,
 * having, distinct or union is refused before the outer query changes.
 */
class AggregateConstraintTest {

    private static final String SELECT = "select \"users\".*, ";
    private static final String POSTS = "from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\"";
    private static final String FROM = " from \"users\"";
    private static final String PLAIN_COUNT = SELECT + "(select count(*) " + POSTS + ") as \"posts_count\"" + FROM;

    private RelationDb db;
    private QueryFactory postgres;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        postgres = WithAggregateSelfRelationTest.postgresGrammarOnly();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c"));
        db.seed("posts", Map.of("id", 4, "user_id", 1, "title", "b"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<User> users() {
        return User.QUERY.on(db.db).query();
    }

    private List<EloquentBuilder<User>> both() {
        return Arrays.asList(users(), User.QUERY.on(postgres).query());
    }

    private static Map<String, Consumer<EloquentBuilder<?>>> one(String name, Consumer<EloquentBuilder<?>> constraint) {
        Map<String, Consumer<EloquentBuilder<?>>> relations = new LinkedHashMap<>();
        relations.put(name, constraint);
        return relations;
    }

    private static String countWith(String tail) {
        return SELECT + "(select count(*) " + POSTS + tail + ") as \"posts_count\"" + FROM;
    }

    // ---- constraint closure

    @Test
    void closureWhereGrouped() {
        for (EloquentBuilder<User> query : both()) {
            EloquentBuilder<User> counted = query.withCount(one("posts", q -> q.where("published", true)));

            assertEquals(countWith(" and \"published\" = ?"), counted.toSql());
            assertEquals(Collections.singletonList(true), counted.getBindings());
        }
    }

    @Test
    void closureOrWhereCannotEscapeKey() {
        for (EloquentBuilder<User> query : both()) {
            String sql = query.withCount(one("posts", q -> q.where("a", 1).orWhere("b", 2))).toSql();

            assertEquals(countWith(" and (\"a\" = ? or \"b\" = ?)"), sql);
        }
    }

    @Test
    void closureOrdersCleared() {
        String sql = users().withCount(one("posts", q -> q.orderBy("votes"))).toSql();

        assertEquals(PLAIN_COUNT, sql);
    }

    @Test
    void closureSelectIgnored() {
        String sql = users().withMax(one("posts", q -> q.select("id", "title")), "votes").toSql();

        assertEquals(SELECT + "(select max(\"posts\".\"votes\") " + POSTS + ") as \"posts_max_votes\"" + FROM, sql);
    }

    @Test
    void closureLimitAndOffsetCleared() {
        for (EloquentBuilder<User> query : both()) {
            assertEquals(PLAIN_COUNT, query.withCount(one("posts", q -> q.limit(1).offset(2))).toSql());
        }
    }

    @Test
    void closureOrderByAndLimitRenderNoneAndCountAllRows() {
        List<User> rows = users().withCount(one("posts", q -> q.orderBy("title").limit(1))).orderBy("id").get();

        assertEquals(PLAIN_COUNT, users().withCount(one("posts", q -> q.orderBy("title").limit(1))).toSql());
        assertEquals(3L, ((Number) rows.get(0).getAttribute("posts_count")).longValue());
        assertEquals(1L, ((Number) rows.get(1).getAttribute("posts_count")).longValue());
    }

    @Test
    void closureJoinIsAllowedAndRunsOnSqlite() {
        db.seed("comments", Map.of("id", 1, "post_id", 1, "body", "x"));
        db.seed("comments", Map.of("id", 2, "post_id", 1, "body", "y"));
        db.seed("comments", Map.of("id", 3, "post_id", 3, "body", "z"));
        EloquentBuilder<User> query = users().withCount(one("posts",
                q -> q.join("comments", "comments.post_id", "=", "posts.id").where("comments.body", "x")));

        assertTrue(query.toSql().contains("from \"posts\" inner join \"comments\" on"), query.toSql());
        List<User> rows = query.orderBy("id").get();
        assertEquals(1L, ((Number) rows.get(0).getAttribute("posts_count")).longValue());
        assertEquals(0L, ((Number) rows.get(1).getAttribute("posts_count")).longValue());
    }

    @Test
    void closureGroupByRefusedWithRelationName() {
        for (EloquentBuilder<User> query : both()) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> query.withCount(one("posts", q -> q.query(b -> b.groupBy("title")))));

            assertTrue(thrown.getMessage().contains("[posts]"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("groupBy/having"), thrown.getMessage());
            assertEquals("select * from \"users\"", query.toSql());
        }
    }

    @Test
    void sumClosureSelectDoesNotLeakColumns() {
        String sql = users().withSum(one("posts", q -> q.select("title")), "id").toSql();

        assertEquals(SELECT + "(select sum(\"posts\".\"id\") " + POSTS + ") as \"posts_sum_id\"" + FROM, sql);
    }

    @Test
    void existsClosureRunsAndDropsOrdersKeepsLimit() {
        EloquentBuilder<User> query = users().withExists(one("posts", q -> q.where("title", "c")));
        List<User> rows = query.orderBy("id").get();

        assertEquals(Boolean.FALSE, rows.get(0).getAttribute("posts_exists"));
        assertEquals(Boolean.TRUE, rows.get(1).getAttribute("posts_exists"));
        assertEquals(SELECT + "exists(select * " + POSTS + " limit 1) as \"posts_exists\"" + FROM,
                users().withExists(one("posts", q -> q.orderBy("title").limit(1))).toSql());
    }

    @Test
    void columnListIgnored() {
        assertEquals(PLAIN_COUNT, users().withCount("posts:id,title").toSql());
    }

    @Test
    void bindingOrder() {
        Consumer<EloquentBuilder<?>> titled = q -> q.where("title", "a");
        EloquentBuilder<User> after = users().withCount(one("posts", titled)).where("name", "Ann");
        EloquentBuilder<User> before = users().where("name", "Ann").withCount(one("posts", titled));

        assertEquals(Arrays.asList("a", "Ann"), after.getBindings());
        assertEquals(Arrays.asList("a", "Ann"), before.getBindings());
        assertEquals(after.toSql(), before.toSql());
    }

    @Test
    void relationOwnConstraintsApplied() {
        EloquentBuilder<Blogger> query = Blogger.QUERY.on(db.db).query().withCount("titled");

        assertEquals(SELECT + "(select count(*) " + POSTS + " and \"title\" = ?) as \"titled_count\"" + FROM,
                query.toSql());
        assertEquals(Collections.singletonList("b"), query.getBindings());
        Blogger ann = query.where("id", 1).firstOrFail();
        assertEquals(2L, ((Number) ann.getAttribute("titled_count")).longValue());
    }

    @Test
    void emptyGroupNotWritten() {
        assertEquals(PLAIN_COUNT, users().withCount(one("posts", q -> { })).toSql());
    }

    @Test
    void closureCountsRows() {
        List<User> rows = users().withCount(one("posts", q -> q.where("title", "b"))).orderBy("id").get();

        assertEquals(2L, ((Number) rows.get(0).getAttribute("posts_count")).longValue());
        assertEquals(0L, ((Number) rows.get(1).getAttribute("posts_count")).longValue());
    }

    // ---- refused constraint clauses

    @Test
    void distinctRefused() {
        assertRefused(q -> q.query(b -> b.distinct()));
    }

    @Test
    void havingRefused() {
        assertRefused(q -> q.query(b -> b.groupBy("title").having("title", "a")));
    }

    @Test
    void unionRefused() {
        assertRefused(q -> q.query(b -> b.union(db.db.table("posts"))));
    }

    @Test
    void refusalLeavesTheOuterQueryUnchanged() {
        Map<String, Consumer<EloquentBuilder<?>>> relations = new LinkedHashMap<>();
        relations.put("posts", q -> q.where("title", "a"));
        relations.put("posts as distinct_posts", q -> q.query(b -> b.distinct()));
        EloquentBuilder<User> query = users();

        assertThrows(IllegalArgumentException.class, () -> query.withCount(relations));

        assertEquals("select * from \"users\"", query.toSql());
        assertTrue(query.getBindings().isEmpty());
    }

    private void assertRefused(Consumer<EloquentBuilder<?>> constraint) {
        EloquentBuilder<User> query = users();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> query.withCount(one("posts", constraint)));

        assertTrue(thrown.getMessage().contains("[posts]"), thrown.getMessage());
        assertEquals("select * from \"users\"", query.toSql());
    }

    // ---- names, functions and columns

    @Test
    void dotPathRefusedBeforeAnyChange() {
        EloquentBuilder<User> query = users();

        RelationNotFoundException thrown = assertThrows(RelationNotFoundException.class,
                () -> query.withCount("posts", "posts.comments"));

        assertEquals("posts.comments", thrown.getRelation());
        assertEquals("select * from \"users\"", query.toSql());
    }

    @Test
    void unknownNameRefusedBeforeAnyChange() {
        EloquentBuilder<User> query = users();

        assertThrows(RelationNotFoundException.class, () -> query.withCount("posts", "nope"));

        assertEquals("select * from \"users\"", query.toSql());
    }

    @Test
    void extraSpacesAroundAsAreNotAnAlias() {
        assertThrows(RelationNotFoundException.class, () -> users().withCount("posts  as total"));
    }

    @Test
    void asIsCaseInsensitive() {
        assertEquals(SELECT + "(select count(*) " + POSTS + ") as \"total\"" + FROM,
                users().withCount("posts AS total").toSql());
    }

    @Test
    void badFunctionRejected() {
        EloquentBuilder<User> query = users();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> query.withAggregate("posts", "votes", "group_concat"));

        assertTrue(thrown.getMessage().contains("[group_concat]"), thrown.getMessage());
        assertEquals("select * from \"users\"", query.toSql());
    }

    @Test
    void longFunctionTextIsCut() {
        String function = String.join("", Collections.nCopies(200, "x"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> users().withAggregate("posts", "votes", function));

        assertFalse(thrown.getMessage().contains(function), thrown.getMessage());
    }

    @Test
    void functionCaseInsensitive() {
        String sql = users().withAggregate("posts", "votes", "MAX").toSql();

        assertEquals(SELECT + "(select max(\"posts\".\"votes\") " + POSTS + ") as \"posts_max_votes\"" + FROM, sql);
    }

    @Test
    void dottedColumnAlias() {
        String sql = users().withMax("posts", "posts.votes").toSql();

        assertEquals(SELECT + "(select max(\"posts\".\"votes\") " + POSTS + ") as \"posts_max_postsvotes\"" + FROM,
                sql);
    }

    @Test
    void expressionColumn() {
        for (EloquentBuilder<User> query : both()) {
            String sql = query.withSum("posts", Expression.raw("price * qty")).toSql();

            assertEquals(SELECT + "(select sum(price * qty) " + POSTS + ") as \"posts_sum_price_qty\"" + FROM, sql);
        }
    }

    @Test
    void noFunctionSelectsOneQualifiedColumn() {
        for (EloquentBuilder<User> query : both()) {
            assertEquals(SELECT + "(select \"posts\".\"title\" " + POSTS + " limit 1) as \"posts_title\"" + FROM,
                    query.withAggregate("posts", "title").toSql());
        }
    }

    @Test
    void existsOnBelongsTo() {
        String sql = RelationModels.Post.QUERY.on(db.db).query().withExists("author").toSql();

        assertEquals("select \"posts\".*, exists(select * from \"users\" where \"posts\".\"user_id\" = \"users\".\"id\")"
                + " as \"author_exists\" from \"posts\"", sql);
    }

    @Test
    void userColumnsKeptAndSubselectAppended() {
        String sql = users().select("name").withCount("posts").toSql();

        assertEquals("select \"name\", (select count(*) " + POSTS + ") as \"posts_count\"" + FROM, sql);
    }

    @Test
    void aliasedOuterFromSelectsTheAlias() {
        String sql = users().query(b -> b.from("users as u")).withCount("posts").toSql();

        assertTrue(sql.startsWith("select \"u\".*, (select count(*) from \"posts\" where"), sql);
    }

    @Test
    void emptyRelationsChangeNothing() {
        assertEquals("select * from \"users\"", users().withCount().toSql());
    }
}
