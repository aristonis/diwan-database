package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SQLite settings given in the connection URL reach the database as pragmas. */
class SQLitePragmaOptionsTest {

    @TempDir
    Path folder;

    private DatabaseManager managerOver(String query) {
        String url = "jdbc:sqlite:" + folder.resolve("pragma.db") + query;
        return ManagerFixtures.manager(ConnectionConfig.builder("main").driver("sqlite").url(url).build());
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    @Test
    void aBusyTimeoutInTheUrlReachesTheDatabase() {
        try (DatabaseManager manager = managerOver("?busy_timeout=1234")) {
            assertEquals(1234, number(manager.connection().scalar("pragma busy_timeout").orElseThrow()));
        }
    }

    @Test
    void aSynchronousSettingInTheUrlReachesTheDatabase() {
        try (DatabaseManager manager = managerOver("?synchronous=OFF")) {
            assertEquals(0, number(manager.connection().scalar("pragma synchronous").orElseThrow()));
        }
    }

    @Test
    void foreignKeyEnforcementStaysOnNextToOtherUrlSettings() {
        try (DatabaseManager manager = managerOver("?synchronous=NORMAL")) {
            assertEquals(1, number(manager.connection().scalar("pragma foreign_keys").orElseThrow()));
            assertEquals(1, number(manager.connection().scalar("pragma synchronous").orElseThrow()));
        }
    }
}
