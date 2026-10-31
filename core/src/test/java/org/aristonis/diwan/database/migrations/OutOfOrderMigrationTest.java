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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** A migration whose name sorts before ones already run, added later, as after a branch merge. */
class OutOfOrderMigrationTest {

    @TempDir
    Path folder;

    @Test
    void anOlderPendingMigrationRunsInTheNextBatchAndRollsBackWithIt() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("o.db"));
        Connection db = Connection.builder().name("o").dataSource(dataSource).build();
        Schema schema = SQLiteSchema.of(db);
        TestMigration first = new TestMigration("2026_01_a", s -> s.create("a", t -> t.id()), s -> s.drop("a"));
        TestMigration late = new TestMigration("2026_02_b", s -> s.create("b", t -> t.id()), s -> s.drop("b"));
        TestMigration third = new TestMigration("2026_03_c", s -> s.create("c", t -> t.id()), s -> s.drop("c"));

        Migrator.builder(schema).migrations(Arrays.asList(first, third)).build().run();
        Migrator all = Migrator.builder(schema).migrations(Arrays.asList(first, late, third)).build();
        assertEquals(1, all.run().size());

        assertTrue(schema.hasTable("b"));
        assertEquals(2, ((Number) db.scalar("select batch from migrations where migration = '2026_02_b'").get())
                .intValue());
        assertEquals(1, all.rollback().size());
        assertFalse(schema.hasTable("b"));
        assertTrue(schema.hasTable("c"));
    }
}
