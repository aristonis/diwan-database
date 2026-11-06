package org.aristonis.diwan.database.migrations;

import org.aristonis.diwan.database.exception.MigrationFailedException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.MigratorSqliteTest.TestMigration;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** A rollback whose record cannot be removed must not leave half of it done. */
class MigratorRollbackRecordTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("rollback.db"));
        db = Connection.builder().name("rollback").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
    }

    private Migrator migrator(Migration... migrations) {
        return Migrator.builder(schema).migrations(Arrays.asList(migrations)).build();
    }

    private TestMigration createsPosts() {
        return new TestMigration("a", s -> s.create("posts", t -> t.string("title")), s -> s.drop("posts"));
    }

    private void refuseDeletesFromTheMigrationsTable() {
        db.statement("create trigger keep_records before delete on migrations"
                + " begin select raise(abort, 'records stay'); end");
    }

    @Test
    void aRecordThatCannotBeRemovedUndoesTheDownThatBelongsToIt() {
        Migrator migrator = migrator(createsPosts());
        migrator.run();
        refuseDeletesFromTheMigrationsTable();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::rollback);

        assertEquals("a", failure.migration());
        assertEquals(MigrationFailedException.Direction.DOWN, failure.direction());
        assertTrue(schema.hasTable("posts"), "the drop was undone with the record");
        assertEquals(Collections.singletonList("a"), migrator.repository().getRan());
    }

    @Test
    void aRecordThatCannotBeRemovedStopsTheRestOfTheRollback() {
        TestMigration second = new TestMigration("b", s -> s.create("tags", t -> t.string("name")),
                s -> s.drop("tags"));
        Migrator migrator = migrator(createsPosts(), second);
        migrator.run();
        refuseDeletesFromTheMigrationsTable();

        MigrationFailedException failure = assertThrows(MigrationFailedException.class, migrator::reset);

        assertEquals("b", failure.migration());
        assertEquals(Collections.emptyList(), failure.completed());
        assertTrue(schema.hasTable("posts") && schema.hasTable("tags"));
        assertEquals(Arrays.asList("a", "b"), migrator.repository().getRan());
    }
}
