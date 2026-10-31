package org.aristonis.diwan.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

/** A column change on a table that another table points at, run as a migration. */
class MigratorChangeTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    private final TestMigration create = new TestMigration("2026_01_create", s -> {
        s.create("posts", t -> {
            t.id();
            t.string("title", 20);
        });
        s.create("comments", t -> {
            t.id();
            t.foreignId("post_id").constrained().cascadeOnDelete();
        });
    }, s -> {
        s.drop("comments");
        s.drop("posts");
    });

    private final TestMigration widen = new TestMigration("2026_02_widen",
            s -> s.table("posts", t -> t.string("title", 200).nullable().change()),
            s -> s.table("posts", t -> t.string("title", 20).change()));

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("app.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("app").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
    }

    private long count(String table) {
        return ((Number) db.scalar("select count(*) from " + table).get()).longValue();
    }

    @Test
    void changingAReferencedTableKeepsChildRowsAndTheirCascade() {
        Migrator.builder(schema).migrations(Arrays.asList(create)).build().run();
        db.insert("insert into posts (id, title) values (1, 'one'), (2, 'two')");
        db.insert("insert into comments (post_id) values (1), (1), (2)");

        Migrator.builder(schema).migrations(Arrays.asList(create, widen)).build().run();

        assertEquals(3, count("comments"));
        db.delete("delete from posts where id = 1");
        assertEquals(1, count("comments"));
        assertEquals(1, schema.foreignKeys("comments").size());
    }
}
