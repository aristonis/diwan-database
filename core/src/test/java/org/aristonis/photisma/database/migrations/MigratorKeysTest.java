package org.aristonis.photisma.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.aristonis.photisma.database.Connection;
import org.aristonis.photisma.database.migrations.MigratorSqliteTest.TestMigration;
import org.aristonis.photisma.database.schema.ForeignKeyCheckException;
import org.aristonis.photisma.database.schema.Schema;
import org.aristonis.photisma.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** A blog's releases on SQLite with foreign keys enforced, as a real app runs them. */
class MigratorKeysTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    private final TestMigration users = new TestMigration("2026_01_users", s -> s.create("users", t -> {
        t.id();
        t.string("email").unique();
    }), s -> s.drop("users"));

    private final TestMigration posts = new TestMigration("2026_02_posts", s -> s.create("posts", t -> {
        t.id();
        t.foreignId("user_id").constrained().cascadeOnDelete();
        t.integer("category_id").nullable();
        t.string("title");
        t.index("user_id", "title");
    }), s -> s.drop("posts"));

    private final TestMigration comments = new TestMigration("2026_03_comments", s -> s.create("comments", t -> {
        t.id();
        t.foreignId("post_id").constrained().cascadeOnDelete();
    }), s -> s.drop("comments"));

    private final TestMigration categories = new TestMigration("2026_04_categories", s -> {
        s.create("categories", t -> t.id());
        s.table("posts", t -> t.foreign("category_id").references("id").on("categories").nullOnDelete());
    }, s -> {
        s.table("posts", t -> t.dropForeign(Arrays.asList("category_id")));
        s.drop("categories");
    });

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("app.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("app").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
    }

    private Migrator migrator(Migration... migrations) {
        return Migrator.builder(schema).migrations(Arrays.asList(migrations)).build();
    }

    private long count(String table) {
        return ((Number) db.scalar("select count(*) from " + table).get()).longValue();
    }

    private void fill() {
        db.insert("insert into users (id, email) values (1, 'a'), (2, 'b')");
        db.insert("insert into posts (id, user_id, title) values (1, 1, 'one'), (2, 2, 'two')");
        db.insert("insert into comments (post_id) values (1), (1), (2)");
    }

    @Test
    void deletingAUserCascadesThroughPostsToComments() {
        migrator(users, posts, comments).run();
        fill();

        db.delete("delete from users where id = 1");

        assertEquals(1, count("posts"));
        assertEquals(1, count("comments"));
    }

    @Test
    void aLaterReleaseAddsAKeyToAReferencedTableWithoutLosingChildren() {
        migrator(users, posts, comments).run();
        fill();

        migrator(users, posts, comments, categories).run();

        assertEquals(2, count("posts"));
        assertEquals(3, count("comments"));
        assertEquals(2, schema.foreignKeys("posts").size());
        assertEquals(1, ((Number) db.scalar("pragma foreign_keys").get()).intValue());
        db.insert("insert into categories (id) values (5)");
        db.update("update posts set category_id = 5 where id = 1");
        db.delete("delete from categories where id = 5");
        assertTrue(!db.scalar("select category_id from posts where id = 1").isPresent());
    }

    @Test
    void rollingBackTheReleaseRemovesTheKeyAndKeepsTheRows() {
        migrator(users, posts, comments).run();
        fill();
        Migrator all = migrator(users, posts, comments, categories);
        all.run();

        all.rollback();

        assertEquals(1, schema.foreignKeys("posts").size());
        assertEquals(2, count("posts"));
        assertEquals(3, count("comments"));
        assertTrue(!schema.hasTable("categories"));
    }

    @Test
    void aFailingRebuildInsideAMigrationUndoesAllOfIt() {
        migrator(users, posts, comments).run();
        fill();
        db.update("update posts set category_id = 99 where id = 2");

        MigrationFailedException thrown = assertThrows(MigrationFailedException.class,
                () -> migrator(users, posts, comments, categories).run());

        assertInstanceOf(ForeignKeyCheckException.class, thrown.getCause());
        assertTrue(!schema.hasTable("categories"));
        assertEquals(1, schema.foreignKeys("posts").size());
        assertEquals(3, count("comments"));
        assertEquals(Arrays.asList("2026_01_users", "2026_02_posts", "2026_03_comments"),
                migrator(users).repository().getRan());
        assertEquals(1, ((Number) db.scalar("pragma foreign_keys").get()).intValue());
    }

    @Test
    void aRebuildOutsideAMigrationTransactionIsStillSafe() {
        TestMigration plain = new TestMigration("2026_04_categories", s -> {
            s.create("categories", t -> t.id());
            s.table("posts", t -> t.foreign("category_id").references("id").on("categories"));
        }, null).withoutTransaction();
        migrator(users, posts, comments).run();
        fill();

        migrator(users, posts, comments, plain).run();

        assertEquals(3, count("comments"));
        assertEquals(2, schema.foreignKeys("posts").size());
    }

    @Test
    void pretendListsTheRebuildAndTouchesNothing() {
        migrator(users, posts, comments).run();
        fill();
        Migrator all = migrator(users, posts, comments, categories);

        List<MigrationResult> results = all.pretendRun();

        boolean listsRebuild = false;
        for (org.aristonis.photisma.database.LoggedQuery query : results.get(0).queries()) {
            listsRebuild |= query.sql().startsWith("create table \"__temp__posts\"");
        }
        assertTrue(listsRebuild);
        assertEquals(1, schema.foreignKeys("posts").size());
        assertEquals(1, ((Number) db.scalar("pragma foreign_keys").get()).intValue());
    }

    @Test
    void droppingAConstrainedForeignIdRemovesKeyAndColumn() {
        TestMigration drop = new TestMigration("2026_05_drop", s -> s.table("comments",
                t -> t.dropConstrainedForeignId("post_id")), null);
        migrator(users, posts, comments).run();
        fill();

        migrator(users, posts, comments, drop).run();

        assertTrue(!schema.hasColumn("comments", "post_id"));
        assertTrue(schema.foreignKeys("comments").isEmpty());
        assertEquals(3, count("comments"));
    }

    @Test
    void aDataMigrationStillCascades() {
        TestMigration purge = new TestMigration("2026_05_purge", s -> s.connection().delete("delete from users where id = 1"),
                null);
        migrator(users, posts, comments).run();
        fill();

        migrator(users, posts, comments, purge).run();

        assertEquals(1, count("posts"));
        assertEquals(1, count("comments"));
    }
}
