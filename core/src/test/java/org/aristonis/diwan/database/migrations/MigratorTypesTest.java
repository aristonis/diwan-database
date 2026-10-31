package org.aristonis.diwan.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.MigratorSqliteTest.TestMigration;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** A Laravel-style users and comments schema built from the helpers, then trimmed by a later release. */
class MigratorTypesTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    private final TestMigration authors = new TestMigration("2026_01_authors", s -> s.create("authors", t -> {
        t.uuid("id").primary();
        t.string("name");
    }), s -> s.drop("authors"));

    private final TestMigration users = new TestMigration("2026_02_users", s -> s.create("users", t -> {
        t.increments("id");
        t.charColumn("country", 2);
        t.enumColumn("status", Arrays.asList("active", "banned")).defaultValue("active");
        t.rememberToken();
        t.softDeletes();
        t.timestampsTz();
    }), s -> s.drop("users"));

    private final TestMigration comments = new TestMigration("2026_03_comments", s -> s.create("comments", t -> {
        t.id();
        t.morphs("commentable");
        t.foreignUuid("author_id").constrained("authors");
        t.text("body");
    }), s -> s.drop("comments"));

    private final TestMigration trim = new TestMigration("2026_04_trim", s -> {
        s.table("users", t -> t.dropSoftDeletes());
        s.table("comments", t -> t.dropMorphs("commentable"));
    }, s -> {
        s.table("users", t -> t.softDeletes());
        s.table("comments", t -> t.nullableMorphs("commentable"));
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

    @Test
    void helpersAddAndDropTheirColumns() {
        migrator(authors, users, comments).run();
        db.insert("insert into authors (id, name) values ('a-1', 'Ann')");
        db.insert("insert into users (country) values ('NL')");
        db.insert("insert into comments (commentable_type, commentable_id, author_id, body)"
                + " values ('post', 7, 'a-1', 'hi')");

        assertEquals("active", db.scalar("select status from users").get());
        assertTrue(schema.hasColumn("users", "remember_token"));
        assertTrue(schema.hasColumn("users", "deleted_at"));
        assertTrue(schema.hasColumn("comments", "commentable_type"));
        assertEquals("authors", schema.foreignKeys("comments").get(0).foreignTable());

        migrator(authors, users, comments, trim).run();

        assertFalse(schema.hasColumn("users", "deleted_at"));
        assertFalse(schema.hasColumn("comments", "commentable_id"));
        assertEquals(1L, ((Number) db.scalar("select count(*) from comments").get()).longValue());

        migrator(authors, users, comments, trim).rollback();

        assertTrue(schema.hasColumn("users", "deleted_at"));
        assertTrue(schema.hasColumn("comments", "commentable_type"));
    }
}
