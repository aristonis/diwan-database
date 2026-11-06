package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.database.sqlite.SQLiteDatabase;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.MigrationChecksum;
import org.aristonis.diwan.database.exception.MigrationChecksumException;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A run records a migration's checksum and the next run validates it, through the Android driver. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidMigrationChecksumTest {

    private static Migration migration(String checksum) {
        return new Migration() {
            @Override
            public String name() {
                return "2026_01_users";
            }

            @Override
            public void up(Schema schema) {
                schema.create("users", t -> t.id());
            }

            @Override
            public Optional<String> checksum() {
                return Optional.of(checksum);
            }
        };
    }

    @Test
    public void aRunRecordsTheChecksumAndAnEditedMigrationIsRefused() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        String original = MigrationChecksum.of("create table users");
        Migrator first = Migrator.builder(SQLiteSchema.of(db)).add(migration(original)).build();

        first.run();
        Migrator edited = Migrator.builder(SQLiteSchema.of(db))
                .add(migration(MigrationChecksum.of("create table people"))).build();

        assertEquals(Optional.of(original), first.repository().checksums().get("2026_01_users"));
        assertThrows(MigrationChecksumException.class, edited::run);
        database.close();
    }
}
