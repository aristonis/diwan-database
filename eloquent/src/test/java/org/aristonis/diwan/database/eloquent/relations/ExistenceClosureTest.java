package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The closure of {@code has} and {@code whereHas} runs on the existence subquery itself: joins,
 * a limit and groups render inside it, the wheres it adds form one group, and a count
 * comparison drops orders and paging and refuses groups.
 */
class ExistenceClosureTest {

    private static final String USERS = "select * from \"users\" where ";
    private static final String POSTS_OF_USER =
            "select * from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\"";
    private static final String COUNT_OF_POSTS =
            "(select count(*) from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\")";

    /** A category tree over a {@code categories} table that only SQL-text tests use. */
    static final class Category extends Model {
        static final ModelQuery<Category> QUERY = ModelQuery.of(Category.class, Category::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Category.class).add("children", Category::children);
        }

        public HasMany<Category> children() {
            return hasMany(Category.class, "parent_id");
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
        db.seed("comments", Map.of("id", 1, "post_id", 3, "body", "x"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<User> users() {
        return User.QUERY.on(db.db).query();
    }

    @Test
    void groupByAndHavingRenderInsideExists() {
        EloquentBuilder<User> query = users().whereHas("posts",
                q -> q.groupBy("user_id").query(b -> b.havingRaw("count(*) > ?", Collections.singletonList(1))));

        assertEquals(USERS + "exists (" + POSTS_OF_USER + " group by \"user_id\" having count(*) > ?)",
                query.toSql());
        assertEquals(Collections.singletonList(1), query.get().modelKeys());
    }

    @Test
    void existsFormDropsOrderAndKeepsLimit() {
        EloquentBuilder<User> query = users().whereHas("posts", q -> q.orderBy("title", "desc").limit(1));

        assertEquals(USERS + "exists (" + POSTS_OF_USER + " limit 1)", query.toSql());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void orWhereInsideClosureStaysGrouped() {
        EloquentBuilder<User> query = users().whereHas("posts",
                q -> q.where("title", "a").orWhere("title", "b"));

        assertEquals(USERS + "exists (" + POSTS_OF_USER + " and (\"title\" = ? or \"title\" = ?))",
                query.toSql());
        assertEquals(Collections.singletonList(1), query.get().modelKeys());
    }

    @Test
    void severalClosureBindingsKeepTheirOrder() {
        EloquentBuilder<User> query = users()
                .where("name", "Ann")
                .whereHas("posts", q -> q.whereIn("title", Arrays.asList("a", "b")).orWhere("id", 3))
                .where("id", 9);

        assertEquals(USERS + "\"name\" = ? and exists (" + POSTS_OF_USER
                + " and (\"title\" in (?, ?) or \"id\" = ?)) and \"id\" = ?", query.toSql());
        assertEquals(Arrays.asList("Ann", "a", "b", 3, 9), query.getBindings());
    }

    @Test
    void joinInsideClosureRendersAndRuns() {
        EloquentBuilder<User> query = users().whereHas("posts",
                q -> q.join("comments", "comments.post_id", "=", "posts.id").where("comments.body", "x"));

        assertEquals(USERS + "exists (select * from \"posts\" inner join \"comments\""
                + " on \"comments\".\"post_id\" = \"posts\".\"id\""
                + " where \"users\".\"id\" = \"posts\".\"user_id\" and \"comments\".\"body\" = ?)", query.toSql());
        assertEquals(Collections.singletonList(2), query.get().modelKeys());
    }

    @Test
    void selfRelationJoinUsesTheReservedAlias() {
        EloquentBuilder<Category> query = Category.QUERY.on(db.db).query().whereHas("children",
                q -> q.join("tags", "tags.category_id", "=", "laravel_reserved_0.id"));

        assertEquals("select * from \"categories\" where exists (select * from \"categories\" as"
                + " \"laravel_reserved_0\" inner join \"tags\" on \"tags\".\"category_id\" = \"laravel_reserved_0\".\"id\""
                + " where \"categories\".\"id\" = \"laravel_reserved_0\".\"parent_id\")", query.toSql());
    }

    @Test
    void countFormDropsOrderLimitAndOffset() {
        EloquentBuilder<User> query = users().whereHas("posts",
                q -> q.orderBy("title").limit(1).query(b -> b.offset(5)), ">", 1);

        assertEquals(USERS + COUNT_OF_POSTS + " > ?", query.toSql());
        assertEquals(Collections.singletonList(1), query.get().modelKeys());
    }

    @Test
    void countFormRefusesGroupBy() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> users().whereHas("posts", q -> q.groupBy("user_id"), ">", 1));

        assertTrue(e.getMessage().contains("posts"), e.getMessage());
        assertTrue(e.getMessage().contains("groupBy/having"), e.getMessage());
    }

    @Test
    void countFormRefusesHaving() {
        assertThrows(IllegalArgumentException.class, () -> users().whereHas("posts",
                q -> q.query(b -> b.havingRaw("count(*) > 1")), "=", 2));
    }

    @Test
    void existsFormRendersNoOrderBy() {
        EloquentBuilder<User> query = users().whereHas("posts", q -> q.orderBy("title"));

        assertFalse(query.toSql().contains("order by"), query.toSql());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void countFormDropsSelectedColumns() {
        EloquentBuilder<User> query = users().whereHas("posts", q -> q.select("id"), ">", 1);

        assertEquals(USERS + COUNT_OF_POSTS + " > ?", query.toSql());
        assertEquals(Collections.singletonList(1), query.get().modelKeys());
    }

    @Test
    void countFormRefusesDistinct() {
        assertThrows(IllegalArgumentException.class, () -> users().whereHas("posts", q -> q.distinct(), ">", 1));
    }

    @Test
    void countFormRefusesUnion() {
        assertThrows(IllegalArgumentException.class, () -> users().whereHas("posts",
                q -> q.query(b -> b.union(c -> c.from("posts"))), ">", 1));
    }

    @Test
    void doesntHaveClosureAlsoRunsOnTheSubquery() {
        List<Object> keys = users().whereDoesntHave("posts", q -> q.where("title", "c")).get().modelKeys();

        assertEquals(Arrays.asList(1, 3), keys);
        assertFalse(users().whereDoesntHave("posts", q -> q.limit(1)).toSql().contains("count"));
    }
}
