package org.aristonis.photisma.database.migrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.aristonis.photisma.database.schema.Schema;
import org.junit.jupiter.api.Test;

class MigrationTest {

    private static class UpOnly extends Migration {
        int ups;

        @Override
        public String name() {
            return "2026_10_31_000000_create_users_table";
        }

        @Override
        public void up(Schema schema) {
            ups++;
        }
    }

    @Test
    void migrationWithOnlyUpHasLaravelDefaults() {
        UpOnly migration = new UpOnly();

        migration.down(null);

        assertEquals(0, migration.ups);
        assertTrue(migration.withinTransaction());
        assertTrue(migration.shouldRun());
        assertEquals(Optional.empty(), migration.connection());
    }

    @Test
    void defaultsCanBeOverridden() {
        Migration migration = new UpOnly() {
            @Override
            public boolean withinTransaction() {
                return false;
            }

            @Override
            public boolean shouldRun() {
                return false;
            }

            @Override
            public Optional<String> connection() {
                return Optional.of("reports");
            }
        };

        assertFalse(migration.withinTransaction());
        assertFalse(migration.shouldRun());
        assertEquals(Optional.of("reports"), migration.connection());
    }
}
