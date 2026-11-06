package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.MigrationChecksum;
import org.aristonis.diwan.database.migrations.MigrationChecksumException;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;

/** Migration checksums on PostgreSQL, including the run under the advisory migration lock. */
class PostgresMigrationChecksumTest {

    private static Migration migration(String name, String table, String checksum) {
        return new Migration() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void up(Schema schema) {
                schema.create(table, t -> t.id());
            }

            @Override
            public void down(Schema schema) {
                schema.drop(table);
            }

            @Override
            public Optional<String> checksum() {
                return Optional.of(checksum);
            }
        };
    }

    private static Migrator migrator(Connection db, String database, Migration migration) {
        return Migrator.builder(PostgresSchema.of(db)).add(migration)
                .lock(new PostgresMigrationLock(PostgresServer.dataSource(database))).build();
    }

    private static String nameOf(Connection db) {
        return String.valueOf(db.scalar("select current_database()").get());
    }

    @Test
    void aRunUnderTheLockRecordsTheChecksumAndRefusesAnEditedMigration() {
        Connection db = PostgresServer.newDatabase();
        String database = nameOf(db);
        String v1 = MigrationChecksum.of("create table a");

        migrator(db, database, migration("2026_01_a", "a", v1)).run();
        Migrator edited = migrator(db, database, migration("2026_01_a", "a", MigrationChecksum.of("create table b")));

        assertEquals(Optional.of(v1), edited.repository().checksums().get("2026_01_a"));
        assertThrows(MigrationChecksumException.class, edited::run);
        assertThrows(MigrationChecksumException.class, edited::pretendRun);
    }

    @Test
    void repairAndClearRunUnderTheLockAndTheLockIsFreeAfterwards() {
        Connection db = PostgresServer.newDatabase();
        String database = nameOf(db);
        migrator(db, database, migration("2026_01_a", "a", "v1")).run();
        Migration edited = migration("2026_01_a", "a", "v2");
        Migrator migrator = migrator(db, database, edited);

        assertThrows(MigrationChecksumException.class, migrator::run);
        assertEquals(1, migrator.repairChecksums(Collections.singletonList(edited)));
        assertTrue(migrator.run().isEmpty());
        assertEquals(1, migrator.clearChecksums());

        assertEquals(Optional.empty(), migrator.repository().checksums().get("2026_01_a"));
        PostgresMigrationLock again = new PostgresMigrationLock(PostgresServer.dataSource(database));
        again.acquire();
        again.release();
    }

    @Test
    void aTableFromBeforeChecksumsNamesTheStatementToRun() {
        Connection db = PostgresServer.newDatabase();
        String database = nameOf(db);
        db.statement("create table migrations (id bigserial primary key, migration varchar(191) not null unique, "
                + "batch integer not null)");
        db.insert("insert into migrations (migration, batch) values ('2026_01_a', 1)");
        Migrator migrator = migrator(db, database, migration("2026_01_a", "a", "v1"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, migrator::run);

        assertTrue(failure.getMessage().contains("alter table migrations add column checksum varchar(64)"),
                failure.getMessage());
        db.statement("alter table migrations add column checksum varchar(64)");
        assertTrue(migrator.run().isEmpty());
        assertEquals(Optional.of("v1"), migrator.repository().checksums().get("2026_01_a"));
    }
}
