package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.PostgresServer;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TComment;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TPost;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TUser;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code saveOrFail} on a model whose owners touch their own owners: every statement of the touch
 * chain, and every write an owner's {@code saved()} hook makes, runs inside the one transaction.
 * Every case runs on SQLite and on a throwaway PostgreSQL; the PostgreSQL half is skipped when the
 * server binaries are missing and fails when {@code DIWAN_PG_REQUIRED} is set.
 */
class SaveOrFailTouchTest {

    private static final String OLD_STAMP = "2000-01-01 00:00:00";

    /** The same cases over one database; subclasses open it. */
    abstract class Cases {

        /** The factory the models are bound to. */
        QueryFactory db;

        /** An unwrapped connection for seeding and reading. */
        Connection real;

        /**
         * Seeds user 1, its post 1 and the comment 1 of that post, all stamped long ago.
         *
         * @param connection the connection to seed through, with the touch tables already created
         */
        void seed(Connection connection) {
            connection.statement("insert into users (id, name, created_at, updated_at)"
                    + " values (1, 'Ann', '" + OLD_STAMP + "', '" + OLD_STAMP + "')");
            connection.statement("insert into posts (id, user_id, title, created_at, updated_at)"
                    + " values (1, 1, 'a', '" + OLD_STAMP + "', '" + OLD_STAMP + "')");
            connection.statement("insert into comments (id, post_id, body, created_at, updated_at)"
                    + " values (1, 1, 'x', '" + OLD_STAMP + "', '" + OLD_STAMP + "')");
        }

        String stamp(String table) {
            return String.valueOf(value(table, "updated_at"));
        }

        Object value(String table, String column) {
            return real.selectOne("select " + column + " from " + table + " where id = 1")
                    .orElseThrow(IllegalStateException::new).get(column);
        }

        /** Loads comment 1 with its post and the post's author, both loaded. */
        TComment loadedComment() {
            TUser user = TUser.QUERY.on(db).findOrFail(1);
            TPost post = TPost.QUERY.on(db).findOrFail(1);
            post.setRelation("author", user);
            TComment comment = TComment.QUERY.on(db).findOrFail(1);
            comment.setRelation("post", post);
            comment.setAttribute("body", "changed");
            return comment;
        }

        @Test
        void touchesTheOwnersOfAnOwnerInsideTheTransaction() {
            String postBefore = stamp("posts");
            String userBefore = stamp("users");
            TComment comment = loadedComment();

            assertTrue(comment.saveOrFail());

            assertEquals("changed", value("comments", "body"));
            assertNotEquals(postBefore, stamp("posts"));
            assertNotEquals(userBefore, stamp("users"));
        }

        @Test
        void rollsBackTheCommentAndBothOwnersWhenAnOwnerHookThrows() {
            String commentBefore = stamp("comments");
            String postBefore = stamp("posts");
            String userBefore = stamp("users");
            TComment comment = loadedComment();
            ((TPost) comment.getRelation("post")).throwsInHook = true;

            assertThrows(IllegalStateException.class, comment::saveOrFail);

            assertEquals("x", value("comments", "body"));
            assertEquals(commentBefore, stamp("comments"));
            assertEquals(postBefore, stamp("posts"));
            assertEquals(userBefore, stamp("users"));
        }

        @Test
        void anOwnerHookWriteJoinsTheTransaction() {
            TComment comment = loadedComment();
            TPost post = (TPost) comment.getRelation("post");
            post.setAttribute("title", "from hook");
            post.savesInHook = true;

            assertTrue(comment.saveOrFail());

            assertEquals("from hook", value("posts", "title"));
            assertEquals("changed", value("comments", "body"));
        }
    }

    @Nested
    class OnSqlite extends Cases {
        private TouchDb sqlite;

        @BeforeEach
        void open() throws SQLException {
            sqlite = new TouchDb();
            db = sqlite.db;
            real = sqlite.real;
            seed(real);
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
            real = connection;
            TouchDb.TouchModels.register();
            connection.statement("create table users (id bigint primary key, name varchar(255),"
                    + " created_at timestamp, updated_at timestamp)");
            connection.statement("create table posts (id bigint primary key, user_id bigint,"
                    + " title varchar(255), created_at timestamp, updated_at timestamp)");
            connection.statement("create table topics (id bigint primary key, user_id bigint,"
                    + " title varchar(255), created_at timestamp, updated_at timestamp)");
            connection.statement("create table comments (id bigint primary key, post_id bigint,"
                    + " topic_id bigint, body varchar(255), created_at timestamp, updated_at timestamp)");
            seed(connection);
        }
    }
}
