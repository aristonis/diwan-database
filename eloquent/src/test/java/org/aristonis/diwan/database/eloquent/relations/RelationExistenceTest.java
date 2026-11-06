package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelationExistenceTest {

    private static final String USERS = "select * from \"users\" where ";
    private static final String POSTS_OF_USER =
            "select * from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\"";
    private static final String HAS_POSTS = USERS + "exists (" + POSTS_OF_USER + ")";
    private static final String CATEGORIES = "select * from \"categories\" where ";
    private static final String CHILDREN_0 = "select * from \"categories\" as \"laravel_reserved_0\" where"
            + " \"categories\".\"id\" = \"laravel_reserved_0\".\"parent_id\"";

    /** A category tree over a {@code categories} table that only SQL-text tests use. */
    static final class Category extends Model {
        static final ModelQuery<Category> QUERY = ModelQuery.of(Category.class, Category::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Category.class).add("children", Category::children).add("parent", Category::parent);
        }

        public HasMany<Category> children() {
            return hasMany(Category.class, "parent_id");
        }

        public BelongsTo<Category> parent() {
            return belongsTo(Category.class, "parent_id");
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

    private EloquentBuilder<Category> categories() {
        return Category.QUERY.on(db.db).query();
    }

    // ---- query shapes

    @Test
    void hasCompilesToExists() {
        assertEquals(HAS_POSTS, users().has("posts").toSql());
    }

    @Test
    void belongsToHasComparesOwnerKey() {
        String sql = Post.QUERY.on(db.db).query().has("author").toSql();

        assertEquals("select * from \"posts\" where exists (select * from \"users\" where"
                + " \"posts\".\"user_id\" = \"users\".\"id\")", sql);
    }

    @Test
    void orHasJoinsWithOr() {
        String sql = users().where("name", "Ann").orHas("posts").toSql();

        assertEquals(USERS + "\"name\" = ? or exists (" + POSTS_OF_USER + ")", sql);
    }

    @Test
    void doesntHaveCompilesToNotExists() {
        assertEquals(USERS + "not exists (" + POSTS_OF_USER + ")", users().doesntHave("posts").toSql());
    }

    @Test
    void orDoesntHaveJoinsWithOr() {
        String sql = users().where("name", "Ann").orDoesntHave("posts").toSql();

        assertEquals(USERS + "\"name\" = ? or not exists (" + POSTS_OF_USER + ")", sql);
    }

    @Test
    void whereHasGroupsTheConstraint() {
        EloquentBuilder<User> query = users().whereHas("posts", q -> q.where("title", "a"));

        assertEquals(USERS + "exists (" + POSTS_OF_USER + " and \"title\" = ?)", query.toSql());
        assertEquals(Collections.singletonList("a"), query.getBindings());
    }

    @Test
    void whereHasWithCountUsesCountForm() {
        String sql = users().whereHas("posts", q -> q.where("title", "a"), ">", 2).toSql();

        assertEquals(USERS + "(select count(*) from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\""
                + " and \"title\" = ?) > ?", sql);
    }

    @Test
    void orWhereHasJoinsWithOr() {
        String sql = users().where("name", "Ann").orWhereHas("posts", q -> q.where("title", "a")).toSql();

        assertEquals(USERS + "\"name\" = ? or exists (" + POSTS_OF_USER + " and \"title\" = ?)", sql);
    }

    @Test
    void whereDoesntHaveCompilesToNotExists() {
        String sql = users().whereDoesntHave("posts", q -> q.where("title", "a")).toSql();

        assertEquals(USERS + "not exists (" + POSTS_OF_USER + " and \"title\" = ?)", sql);
    }

    @Test
    void orWhereDoesntHaveJoinsWithOr() {
        String sql = users().where("name", "Ann").orWhereDoesntHave("posts", q -> q.where("title", "a")).toSql();

        assertEquals(USERS + "\"name\" = ? or not exists (" + POSTS_OF_USER + " and \"title\" = ?)", sql);
    }

    // ---- edge cases

    @Test
    void selfRelationAliased() {
        assertEquals(CATEGORIES + "exists (" + CHILDREN_0 + ")", categories().has("children").toSql());
    }

    @Test
    void selfBelongsToAliased() {
        String sql = categories().has("parent").toSql();

        assertEquals(CATEGORIES + "exists (select * from \"categories\" as \"laravel_reserved_0\" where"
                + " \"laravel_reserved_0\".\"id\" = \"categories\".\"parent_id\")", sql);
    }

    @Test
    void selfAliasIsDeterministic() {
        String first = categories().has("children").toSql();
        String second = categories().has("children").toSql();
        EloquentBuilder<Category> twice = categories().has("children").has("children");

        assertEquals(first, second);
        assertTrue(twice.toSql().contains("\"laravel_reserved_1\""));
        assertEquals(first, categories().has("children").toSql());
    }

    @Test
    void nestedSelfAliasesIncrement() {
        String sql = categories().has("children.children").toSql();

        assertEquals(CATEGORIES + "exists (" + CHILDREN_0 + " and exists (select * from \"categories\" as"
                + " \"laravel_reserved_1\" where \"laravel_reserved_0\".\"id\""
                + " = \"laravel_reserved_1\".\"parent_id\"))",
                sql);
    }

    @Test
    void constraintSeesAlias() {
        String sql = categories().whereHas("children", q -> q.where(q.getModel().qualifyColumn("name"), "x")).toSql();

        assertEquals(CATEGORIES + "exists (" + CHILDREN_0 + " and \"laravel_reserved_0\".\"name\" = ?)", sql);
    }

    @Test
    void countForm() {
        EloquentBuilder<User> query = users().has("posts", ">=", 3);

        assertEquals(USERS + "(select count(*) from \"posts\" where \"users\".\"id\" = \"posts\".\"user_id\") >= ?",
                query.toSql());
        assertEquals(Collections.singletonList(3L), query.getBindings());
    }

    @Test
    void nestedDoesntHave() {
        String sql = users().doesntHave("posts.comments").toSql();

        assertEquals(USERS + "not exists (" + POSTS_OF_USER + " and exists (select * from \"comments\" where"
                + " \"posts\".\"id\" = \"comments\".\"post_id\"))", sql);
    }

    @Test
    void nestedCountAppliesToLastSegment() {
        String sql = users().has("posts.comments", ">", 2).toSql();

        assertEquals(USERS + "exists (" + POSTS_OF_USER + " and (select count(*) from \"comments\" where"
                + " \"posts\".\"id\" = \"comments\".\"post_id\") > ?)", sql);
    }

    @Test
    void badOperatorRejected() {
        EloquentBuilder<User> query = users();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> query.has("posts", "~~", 1));

        assertTrue(e.getMessage().contains("~~"));
        assertEquals("select * from \"users\"", query.toSql());
    }

    @Test
    void unknownRelationRejected() {
        assertThrows(RelationNotFoundException.class, () -> users().has("missing"));
    }

    @Test
    void nullConstraintIsHas() {
        assertEquals(HAS_POSTS, users().whereHas("posts", null).toSql());
    }

    @Test
    void closureJoinRendersInsideTheSubquery() {
        String sql = users().whereHas("posts", q -> q.join("comments", "comments.post_id", "=", "posts.id")).toSql();

        assertEquals(USERS + "exists (select * from \"posts\" inner join \"comments\""
                + " on \"comments\".\"post_id\" = \"posts\".\"id\""
                + " where \"users\".\"id\" = \"posts\".\"user_id\")", sql);
    }

    @Test
    void whereRelationForms() {
        EloquentBuilder<User> query = users().whereRelation("posts", "title", "like", "%x%");
        String or = users().where("name", "Ann").orWhereRelation("posts", "title", "a").toSql();
        String equal = users().whereRelation("posts", "title", "a").toSql();

        assertEquals(USERS + "exists (" + POSTS_OF_USER + " and \"title\" like ?)", query.toSql());
        assertEquals(Collections.singletonList("%x%"), query.getBindings());
        assertEquals(USERS + "\"name\" = ? or exists (" + POSTS_OF_USER + " and \"title\" = ?)", or);
        assertEquals(USERS + "exists (" + POSTS_OF_USER + " and \"title\" = ?)", equal);
        assertEquals(USERS + "\"name\" = ? or exists (" + POSTS_OF_USER + " and \"title\" like ?)",
                users().where("name", "Ann").orWhereRelation("posts", "title", "like", "%x%").toSql());
    }

    // ---- groups, bindings, aliases

    @Test
    void emptyConstraintAddsNoGroup() {
        assertEquals(HAS_POSTS, users().whereHas("posts", q -> { }).toSql());
    }

    @Test
    void whereGroupWrapsItsClauses() {
        String sql = users().where("id", 1).whereGroup(q -> q.where("name", "Ann").orWhere("name", "Bob")).toSql();

        assertEquals(USERS + "\"id\" = ? and (\"name\" = ? or \"name\" = ?)", sql);
    }

    @Test
    void nestedBindingsOrdered() {
        EloquentBuilder<User> query = users()
                .where("name", "n")
                .whereHas("posts", q -> q.where("title", "a").whereHas("comments", c -> c.where("body", "b")))
                .where("id", 9);

        assertEquals(Arrays.asList("n", "a", "b", 9), query.getBindings());
    }

    @Test
    void copyDoesNotShiftAliases() {
        EloquentBuilder<Category> original = categories().has("children");
        EloquentBuilder<?> copy = Access.eloquent().copy(original);

        copy.has("children");
        original.has("children");

        assertTrue(original.toSql().contains("\"laravel_reserved_1\""));
        assertFalse(original.toSql().contains("\"laravel_reserved_2\""));
    }

    @Test
    void whereHasFiltersRows() {
        assertEquals(Collections.singletonList(2),
                users().whereHas("posts", q -> q.where("title", "c")).get().modelKeys());
        assertEquals(Arrays.asList(1, 2), users().has("posts").get().modelKeys());
        assertEquals(Collections.singletonList(3), users().doesntHave("posts").get().modelKeys());
        assertEquals(Collections.singletonList(1), users().has("posts", ">=", 2).get().modelKeys());
        assertEquals(Collections.singletonList(2), users().has("posts.comments").get().modelKeys());
    }
}
