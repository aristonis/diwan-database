package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.eloquent.relations.RelationExistenceTest.Category;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Existence and aggregate subqueries under an aliased outer {@code from}:
 * the parent key is qualified with the outer alias, not the model table. Every case runs its SQL
 * on SQLite and on a throwaway PostgreSQL; the PostgreSQL half is skipped when the server
 * binaries are missing and fails when {@code DIWAN_PG_REQUIRED} is set.
 */
class AliasedFromTest {

    private static final String USERS_AS_U = "select * from \"users\" as \"u\" where ";
    private static final String POSTS_OF_U = "select * from \"posts\" where \"u\".\"id\" = \"posts\".\"user_id\"";
    private static final String CATEGORIES_AS_C = "select * from \"categories\" as \"c\" where ";

    /** The same cases over one database; subclasses open it. */
    abstract class Cases {

        /** The factory the models are bound to. */
        QueryFactory db;

        /**
         * Creates the tables the subclass has not created and seeds every case's rows.
         *
         * @param connection the connection to seed through
         * @param createUsersAndPosts whether {@code users} and {@code posts} still need creating
         */
        void seed(Connection connection, boolean createUsersAndPosts) {
            if (createUsersAndPosts) {
                connection.statement("create table users (id bigint primary key, name varchar(255) not null)");
                connection.statement("create table posts (id bigint primary key, user_id bigint,"
                        + " title varchar(255) not null)");
            }
            connection.statement("create table categories (id bigint primary key, parent_id bigint,"
                    + " name varchar(255) not null)");
            connection.statement("insert into users (id, name) values (1, 'Ann'), (2, 'Bob'), (3, 'Cid')");
            connection.statement("insert into posts (id, user_id, title) values"
                    + " (1, 1, 'a'), (2, 1, 'b'), (3, 2, 'c'), (4, 99, 'orphan')");
            connection.statement("insert into categories (id, parent_id, name) values"
                    + " (1, null, 'root'), (2, 1, 'x'), (3, 1, 'y'), (4, 2, 'z')");
        }

        private EloquentBuilder<User> users() {
            return User.QUERY.on(db).query().query(b -> b.from("users as u"));
        }

        private EloquentBuilder<Post> posts() {
            return Post.QUERY.on(db).query().query(b -> b.from("posts as p"));
        }

        private EloquentBuilder<Category> categories() {
            return Category.QUERY.on(db).query().query(b -> b.from("categories as c"));
        }

        @Test
        void hasQualifiesTheParentKeyWithTheAlias() {
            EloquentBuilder<User> query = users().has("posts");

            assertEquals(USERS_AS_U + "exists (" + POSTS_OF_U + ")", query.toSql());
            assertEquals(Arrays.asList(1L, 2L), keys(query.get()));
        }

        @Test
        void whereHasQualifiesTheParentKeyWithTheAlias() {
            EloquentBuilder<User> query = users().whereHas("posts", q -> q.where("title", "a"));

            assertEquals(USERS_AS_U + "exists (" + POSTS_OF_U + " and (\"title\" = ?))", query.toSql());
            assertEquals(Collections.singletonList(1L), keys(query.get()));
        }

        @Test
        void doesntHaveQualifiesTheParentKeyWithTheAlias() {
            EloquentBuilder<User> query = users().doesntHave("posts");

            assertEquals(USERS_AS_U + "not exists (" + POSTS_OF_U + ")", query.toSql());
            assertEquals(Collections.singletonList(3L), keys(query.get()));
        }

        @Test
        void withCountCorrelatesOnTheAlias() {
            EloquentBuilder<User> query = users().withCount("posts");

            String sql = query.toSql();
            assertTrue(sql.contains("\"u\".\"id\" = \"posts\".\"user_id\""), sql);
            assertTrue(sql.endsWith(" from \"users\" as \"u\""), sql);
            Map<Long, Long> counts = counts(query.get(), "posts_count");
            assertEquals(Map.of(1L, 2L, 2L, 1L, 3L, 0L), counts);
        }

        @Test
        void selfHasManyQualifiesTheOuterAlias() {
            EloquentBuilder<Category> query = categories().has("children");

            assertEquals(CATEGORIES_AS_C + "exists (select * from \"categories\" as \"laravel_reserved_0\" where"
                    + " \"c\".\"id\" = \"laravel_reserved_0\".\"parent_id\")", query.toSql());
            assertEquals(Arrays.asList(1L, 2L), keys(query.get()));
        }

        @Test
        void selfWithCountCorrelatesOnTheOuterAlias() {
            EloquentBuilder<Category> query = categories().withCount("children");

            String sql = query.toSql();
            assertTrue(sql.contains("\"c\".\"id\" = \"laravel_reserved_0\".\"parent_id\""), sql);
            Map<Long, Long> counts = counts(query.get(), "children_count");
            assertEquals(Map.of(1L, 2L, 2L, 1L, 3L, 0L, 4L, 0L), counts);
        }

        @Test
        void belongsToQualifiesTheForeignKeyWithTheAlias() {
            EloquentBuilder<Post> query = posts().has("author");

            assertEquals("select * from \"posts\" as \"p\" where exists (select * from \"users\" where"
                    + " \"p\".\"user_id\" = \"users\".\"id\")", query.toSql());
            assertEquals(Arrays.asList(1L, 2L, 3L), keys(query.get()));
        }

        @Test
        void belongsToWithCountCorrelatesOnTheAlias() {
            EloquentBuilder<Post> query = posts().withCount("author");

            String sql = query.toSql();
            assertTrue(sql.contains("\"p\".\"user_id\" = \"users\".\"id\""), sql);
            Map<Long, Long> counts = counts(query.get(), "author_count");
            assertEquals(Map.of(1L, 1L, 2L, 1L, 3L, 1L, 4L, 0L), counts);
        }

        @Test
        void selfBelongsToQualifiesTheOuterAlias() {
            EloquentBuilder<Category> query = categories().has("parent");

            assertEquals(CATEGORIES_AS_C + "exists (select * from \"categories\" as \"laravel_reserved_0\" where"
                    + " \"laravel_reserved_0\".\"id\" = \"c\".\"parent_id\")", query.toSql());
            assertEquals(Arrays.asList(2L, 3L, 4L), keys(query.get()));
        }
    }

    @Nested
    class OnSQLite extends Cases {

        private RelationDb sqlite;

        @BeforeEach
        void open() throws SQLException {
            sqlite = new RelationDb();
            db = sqlite.db;
            seed(sqlite.real, false);
        }

        @AfterEach
        void close() throws SQLException {
            sqlite.close();
        }
    }

    @Nested
    class OnPostgres extends Cases {

        @BeforeEach
        void open() {
            Connection connection = PostgresServer.newDatabase();
            db = PostgresQuery.of(connection);
            RelationModels.register();
            seed(connection, true);
        }
    }

    private static List<Long> keys(List<? extends Model> models) {
        List<Long> keys = new ArrayList<>();
        for (Model model : models) {
            keys.add(((Number) model.getKey()).longValue());
        }
        Collections.sort(keys);
        return keys;
    }

    private static Map<Long, Long> counts(List<? extends Model> models, String attribute) {
        Map<Long, Long> counts = new HashMap<>();
        for (Model model : models) {
            counts.put(((Number) model.getKey()).longValue(), ((Number) model.getAttribute(attribute)).longValue());
        }
        return counts;
    }
}
