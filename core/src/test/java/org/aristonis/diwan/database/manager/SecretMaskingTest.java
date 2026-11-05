package org.aristonis.diwan.database.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretMaskingTest {

    @Test
    void toStringHidesThePassword() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("pgsql").host("h")
                .username("u").password("hunter2").build();

        String text = config.toString();

        assertFalse(text.contains("hunter2"));
        assertTrue(text.contains("password=***"));
        assertTrue(text.contains("username=u"));
    }

    @Test
    void toStringMasksAPasswordInsideTheUrl() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("pgsql")
                .url("jdbc:postgresql://h/db?user=u&password=hunter2&ssl=true").build();

        String text = config.toString();

        assertFalse(text.contains("hunter2"));
        assertTrue(text.contains("password=***&ssl=true"));
    }

    @Test
    void toStringMasksTheUserInfoPasswordOfAUrl() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("pgsql")
                .url("postgresql://u:hunter2@h/db").build();

        assertFalse(config.toString().contains("hunter2"));
        assertTrue(config.toString().contains("postgresql://u:***@h/db"));
    }

    @Test
    void toStringMasksOptionsWhoseKeysNameASecret() {
        ConnectionConfig config = ConnectionConfig.builder("main").driver("x")
                .option("ssl_password", "hunter2").option("apiToken", "tok3n").option("sslmode", "require").build();

        String text = config.toString();

        assertFalse(text.contains("hunter2"));
        assertFalse(text.contains("tok3n"));
        assertTrue(text.contains("sslmode=require"));
    }

    @Test
    void maskReturnsNullForNullAndKeepsPlainText() {
        assertNull(ConnectionConfig.mask(null));
        assertEquals("jdbc:sqlite:app.db", ConnectionConfig.mask("jdbc:sqlite:app.db"));
    }

    @Test
    void maskHandlesEveryPasswordSpelling() {
        assertEquals("a=1;pwd=***;b=2", ConnectionConfig.mask("a=1;pwd=hunter2;b=2"));
        assertEquals("PASSWORD=***", ConnectionConfig.mask("PASSWORD=hunter2"));
    }

    @Test
    void databaseConfigToStringListsNamesOnly() {
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("a")
                .connection(ConnectionConfig.builder("a").driver("x").password("hunter2").build()).build();

        assertFalse(config.toString().contains("hunter2"));
        assertTrue(config.toString().contains("a"));
    }

    @Test
    void managerErrorsNameConnectionsAndDriversButNeverTheirSettings() {
        ConnectionConfig secret = ConnectionConfig.builder("main").driver("ghost").password("hunter2")
                .url("jdbc:x://h?password=hunter2").build();
        DatabaseManager manager = DatabaseManager.builder()
                .config(DatabaseConfig.builder().defaultConnection("main").connection(secret).build())
                .connector(new FakeConnector("fake")).build();

        IllegalArgumentException driver = assertThrows(IllegalArgumentException.class, manager::connection);
        IllegalArgumentException name = assertThrows(IllegalArgumentException.class, () -> manager.connection("nope"));

        assertFalse(driver.getMessage().contains("hunter2"));
        assertFalse(name.getMessage().contains("hunter2"));
    }
}
