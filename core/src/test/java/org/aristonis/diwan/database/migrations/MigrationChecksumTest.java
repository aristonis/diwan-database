package org.aristonis.diwan.database.migrations;

import org.aristonis.diwan.database.exception.MigrationChecksumException;
import org.aristonis.diwan.database.migrations.event.MigrationEvent;
import org.aristonis.diwan.database.migrations.event.MigrationListener;
import org.aristonis.diwan.database.migrations.event.MigrationsEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class MigrationChecksumTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("main.db"));
        db = Connection.builder().name("main").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
    }

    /** A migration that creates one table and declares the given checksum, if any. */
    private static Migration migration(String name, String table, String checksum) {
        return new Migration() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void up(Schema schema) {
                schema.create(table, t -> t.string("x"));
            }

            @Override
            public void down(Schema schema) {
                schema.drop(table);
            }

            @Override
            public Optional<String> checksum() {
                return Optional.ofNullable(checksum);
            }
        };
    }

    private Migrator migrator(Migration... migrations) {
        return Migrator.builder(schema).migrations(Arrays.asList(migrations)).build();
    }

    private Optional<String> stored(String name) {
        return migrator().repository().checksums().get(name);
    }

    @Test
    void sameTextWithDifferentLineEndingsHasTheSameChecksum() {
        String unix = MigrationChecksum.of("create table a (\n  x int\n)\n");

        assertEquals(unix, MigrationChecksum.of("create table a (\r\n  x int\r\n)\r\n"));
        assertEquals(unix, MigrationChecksum.of("create table a (\r  x int\r)\r"));
        assertNotEquals(unix, MigrationChecksum.of("create table a (\n  x int\n) "));
    }

    @Test
    void composedAndDecomposedAccentsHaveTheSameChecksum() {
        assertEquals(MigrationChecksum.of("café"), MigrationChecksum.of("café"));
    }

    @Test
    void aChecksumIsSixtyFourLowercaseHexCharacters() {
        String checksum = MigrationChecksum.of("");

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", checksum);
        assertEquals(MigrationChecksum.LENGTH, checksum.length());
        assertThrows(NullPointerException.class, () -> MigrationChecksum.of(null));
    }

    @Test
    void aRunRecordsTheDeclaredChecksum() {
        migrator(migration("a", "ta", "v1"), migration("b", "tb", null)).run();

        assertEquals(Optional.of("v1"), stored("a"));
        assertEquals(Optional.empty(), stored("b"));
    }

    @Test
    void anEditedMigrationIsRefusedBeforeAnythingRunsAndTheMessageListsEveryOne() {
        migrator(migration("a", "ta", "v1"), migration("b", "tb", "v1")).run();
        Migrator edited = migrator(migration("a", "ta", "v2"), migration("b", "tb", "v2"),
                migration("c", "tc", "v1"));

        MigrationChecksumException failure = assertThrows(MigrationChecksumException.class, edited::run);

        assertEquals(2, failure.mismatches().size());
        assertEquals("a", failure.mismatches().get(0).name());
        assertEquals("v1", failure.mismatches().get(0).stored());
        assertEquals("v2", failure.mismatches().get(0).current());
        String message = failure.getMessage();
        assertTrue(message.contains("a: recorded v1, current v2"), message);
        assertTrue(message.contains("b: recorded v1, current v2"), message);
        assertTrue(message.contains("repairChecksums()"), message);
        assertFalse(schema.hasTable("tc"), "nothing ran");
    }

    @Test
    void pretendingARunRefusesAnEditedMigrationToo() {
        migrator(migration("a", "ta", "v1")).run();

        assertThrows(MigrationChecksumException.class,
                () -> migrator(migration("a", "ta", "v2"), migration("c", "tc", null)).pretendRun());
    }

    @Test
    void aMigrationWithoutAChecksumNeverBlocks() {
        migrator(migration("a", "ta", "v1"), migration("b", "tb", null)).run();

        List<MigrationResult> results = migrator(migration("a", "ta", null), migration("b", "tb", "v9"),
                migration("c", "tc", null)).run();

        assertEquals(1, results.size());
        assertEquals(Optional.of("v1"), stored("a"), "an undeclared checksum does not erase the stored one");
    }

    @Test
    void aMigrationThatStartsDeclaringAChecksumAdoptsItAndIsCheckedAfterwards() {
        migrator(migration("a", "ta", null)).run();
        assertEquals(Optional.empty(), stored("a"));

        migrator(migration("a", "ta", "v1")).run();

        assertEquals(Optional.of("v1"), stored("a"));
        assertThrows(MigrationChecksumException.class, () -> migrator(migration("a", "ta", "v2")).run());
    }

    @Test
    void repairAcceptsTheCurrentChecksumsAndReportsHowManyChanged() {
        migrator(migration("a", "ta", "v1"), migration("b", "tb", "v1"), migration("c", "tc", null)).run();
        Migration[] edited = {migration("a", "ta", "v2"), migration("b", "tb", "v1"), migration("c", "tc", null)};

        int changed = migrator(edited).repairChecksums(Arrays.asList(edited));

        assertEquals(1, changed);
        assertEquals(Optional.of("v2"), stored("a"));
        assertEquals(Optional.of("v1"), stored("b"));
        assertTrue(migrator(edited).run().isEmpty());
    }

    @Test
    void repairFiresNoMigrationEvents() {
        migrator(migration("a", "ta", "v1")).run();
        int[] seen = {0};
        Migration edited = migration("a", "ta", "v2");
        MigrationListener counter = new MigrationListener() {
            @Override
            public void migrationsStarted(MigrationsEvent event) {
                seen[0]++;
            }

            @Override
            public void migrationStarted(MigrationEvent event) {
                seen[0]++;
            }

            @Override
            public void noPendingMigrations(MigrationsEvent event) {
                seen[0]++;
            }
        };
        Migrator migrator = Migrator.builder(schema).add(edited).listener(counter).build();

        migrator.repairChecksums(Collections.singletonList(edited));
        migrator.clearChecksums();

        assertEquals(0, seen[0]);
    }

    @Test
    void clearRemovesEveryChecksumAndTheNextRunAdoptsThemAgain() {
        Migrator migrator = migrator(migration("a", "ta", "v1"), migration("b", "tb", null));
        migrator.run();

        assertEquals(1, migrator.clearChecksums());

        assertEquals(Optional.empty(), stored("a"));
        assertTrue(migrator(migration("a", "ta", "v2")).run().isEmpty());
        assertEquals(Optional.of("v2"), stored("a"));
    }

    @Test
    void repairAndClearDoNothingWithoutAMigrationsTable() {
        Migrator migrator = migrator(migration("a", "ta", "v1"));

        assertEquals(0, migrator.repairChecksums(migrator.migrations()));
        assertEquals(0, migrator.clearChecksums());
        assertFalse(migrator.repository().repositoryExists());
    }

    @Test
    void aChecksumThatDoesNotFitTheColumnIsRefused() {
        Migrator tooLong = migrator(migration("a", "ta", new String(new char[65]).replace('\0', 'x')));
        Migrator blank = migrator(migration("a", "ta", "  "));

        assertThrows(IllegalStateException.class, tooLong::run);
        assertThrows(IllegalStateException.class, blank::run);
        assertFalse(schema.hasTable("ta"));
    }

    @Test
    void aTableFromBeforeChecksumsFailsWithTheStatementToRun() {
        db.statement("create table migrations (id integer primary key autoincrement, "
                + "migration varchar(191) not null unique, batch integer not null)");
        db.insert("insert into migrations (migration, batch) values ('a', 1)");
        Migrator migrator = migrator(migration("a", "ta", "v1"), migration("b", "tb", null));

        IllegalStateException failure = assertThrows(IllegalStateException.class, migrator::run);

        assertTrue(failure.getMessage().contains("alter table migrations add column checksum varchar(64)"),
                failure.getMessage());
        assertThrows(IllegalStateException.class, migrator::pretendRun);
        assertThrows(IllegalStateException.class, migrator::clearChecksums);
        assertThrows(IllegalStateException.class, () -> migrator.repository().getLast());
        assertThrows(IllegalStateException.class,
                () -> migrator.repository().log("z", 2, null));
        assertEquals(Collections.singletonList("a"), migrator.repository().getRan());
    }

    @Test
    void everyRollbackPathAndStatusFailWithTheStatementOnATableFromBeforeChecksums() {
        db.statement("create table migrations (id integer primary key autoincrement, "
                + "migration varchar(191) not null unique, batch integer not null)");
        db.insert("insert into migrations (migration, batch) values ('a', 1)");
        Migrator migrator = migrator(migration("a", "ta", "v1"));
        String statement = "alter table migrations add column checksum varchar(64)";

        for (Runnable call : Arrays.<Runnable>asList(migrator::rollback, () -> migrator.rollbackSteps(1),
                () -> migrator.rollbackBatch(1), migrator::reset, migrator::status)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, call::run);
            assertTrue(failure.getMessage().contains(statement), failure.getMessage());
        }
    }

    @Test
    void rollbackAndStatusStillWorkWithoutAMigrationsTable() {
        Migrator migrator = migrator(migration("a", "ta", "v1"));

        assertTrue(migrator.rollback().isEmpty());
        assertTrue(migrator.reset().isEmpty());
        assertEquals(1, migrator.status().size());
    }

    @Test
    void theOneStatementFromTheMessageUpgradesAPreReleaseTable() {
        db.statement("create table migrations (id integer primary key autoincrement, "
                + "migration varchar(191) not null unique, batch integer not null)");
        db.insert("insert into migrations (migration, batch) values ('a', 1)");

        db.statement("alter table migrations add column checksum varchar(64)");
        List<MigrationResult> results = migrator(migration("a", "ta", "v1"), migration("b", "tb", null)).run();

        assertEquals(1, results.size());
        assertEquals(Optional.of("v1"), stored("a"), "the old row adopts the declared checksum");
    }

    @Test
    void entriesCarryTheirChecksum() {
        migrator(migration("a", "ta", "v1"), migration("b", "tb", null)).run();
        Map<String, Optional<String>> all = migrator().repository().checksums();

        assertEquals(Arrays.asList("a", "b"), Arrays.asList(all.keySet().toArray()));
        assertEquals(Optional.of("v1"), migrator().repository().getLast().get(1).checksum());
        assertEquals(Optional.empty(), migrator().repository().getLast().get(0).checksum());
    }

    @Test
    void aRepositoryWithoutChecksumSupportRefusesTheDefaultMethods() {
        MigrationRepository bare = new MigrationRepository() {
            public List<String> getRan() { return Collections.emptyList(); }
            public List<MigrationEntry> getMigrations(int steps) { return Collections.emptyList(); }
            public List<MigrationEntry> getMigrationsByBatch(int batch) { return Collections.emptyList(); }
            public List<MigrationEntry> getLast() { return Collections.emptyList(); }
            public Map<String, Integer> getMigrationBatches() { return Collections.emptyMap(); }
            public void log(String name, int batch, String checksum) { }
            public void delete(String name) { }
            public int getLastBatchNumber() { return 0; }
            public int getNextBatchNumber() { return 1; }
            public void createRepository() { }
            public boolean repositoryExists() { return true; }
            public void deleteRepository() { }
        };

        assertThrows(UnsupportedOperationException.class, bare::checksums);
        assertThrows(UnsupportedOperationException.class, () -> bare.setChecksum("a", Optional.empty()));
    }
}
