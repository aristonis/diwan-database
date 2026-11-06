package org.aristonis.diwan.database.manager;

import org.aristonis.diwan.database.exception.DatabaseConfigException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.aristonis.diwan.database.config.MapConfigSource;
import org.junit.jupiter.api.Test;

class DatabaseConfigTest {

    private static Map<String, String> base() {
        Map<String, String> values = new HashMap<>();
        values.put("database.default", "main");
        values.put("database.connections.main.driver", "pgsql");
        values.put("database.connections.main.host", "db.local");
        values.put("database.connections.main.port", "5433");
        values.put("database.connections.main.database", "app");
        values.put("database.connections.main.username", "app_user");
        values.put("database.connections.main.password", "s3cret");
        values.put("database.connections.main.prefix", "x_");
        values.put("database.connections.main.strict_transactions", "false");
        values.put("database.connections.main.sslmode", "require");
        values.put("database.connections.cache.driver", "sqlite");
        values.put("database.connections.cache.database", ":memory:");
        return values;
    }

    @Test
    void readsTypedValuesAndCollectsUnknownKeysAsOptions() {
        DatabaseConfig config = DatabaseConfig.from(MapConfigSource.of(base()), MapConfigSource.of(new HashMap<>()));

        ConnectionConfig main = config.connection("main").get();
        assertEquals("main", config.defaultConnection());
        assertEquals("pgsql", main.driver());
        assertEquals("db.local", main.host().get());
        assertEquals(5433, main.port().getAsInt());
        assertEquals("app", main.database().get());
        assertEquals("app_user", main.username().get());
        assertEquals("s3cret", main.password().get());
        assertEquals("x_", main.prefix());
        assertFalse(main.strictTransactions());
        assertEquals("require", main.options().get("sslmode"));
        assertEquals("[cache, main]", config.names().toString());
    }

    @Test
    void appliesDefaultsWhenOptionalKeysAreAbsent() {
        ConnectionConfig cache = DatabaseConfig.from(MapConfigSource.of(base()), MapConfigSource.of(new HashMap<>()))
                .connection("cache").get();

        assertEquals("", cache.prefix());
        assertTrue(cache.strictTransactions());
        assertFalse(cache.port().isPresent());
        assertFalse(cache.password().isPresent());
        assertTrue(cache.options().isEmpty());
    }

    @Test
    void resolvesReferencesFromTheVariableSource() {
        Map<String, String> values = base();
        values.put("database.connections.main.password", "${DB_PASS}");

        DatabaseConfig config = DatabaseConfig.from(MapConfigSource.of(values),
                MapConfigSource.of(Map.of("DB_PASS", "from-env")));

        assertEquals("from-env", config.connection("main").get().password().get());
    }

    @Test
    void failsOnAMissingVariableWithoutShowingTheValue() {
        Map<String, String> values = base();
        values.put("database.connections.main.password", "${DB_PASS}");

        DatabaseConfigException failure = assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.from(MapConfigSource.of(values), MapConfigSource.of(new HashMap<>())));

        assertTrue(failure.getMessage().contains("DB_PASS"));
        assertEquals("database.connections.main.password", failure.key().get());
        assertEquals("main", failure.connection().get());
    }

    @Test
    void namesTheConnectionAndKeyWhenTheDriverIsMissing() {
        Map<String, String> values = base();
        values.remove("database.connections.cache.driver");

        DatabaseConfigException failure = assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.from(MapConfigSource.of(values), MapConfigSource.of(new HashMap<>())));

        assertTrue(failure.getMessage().contains("cache"));
        assertEquals("cache", failure.connection().get());
        assertEquals("driver", failure.key().get());
    }

    @Test
    void failsWhenTheDefaultKeyIsMissing() {
        Map<String, String> values = base();
        values.remove("database.default");

        DatabaseConfigException failure = assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.from(MapConfigSource.of(values), MapConfigSource.of(new HashMap<>())));

        assertEquals("database.default", failure.key().get());
    }

    @Test
    void failsWhenTheDefaultNamesNoConnection() {
        Map<String, String> values = base();
        values.put("database.default", "nope");

        DatabaseConfigException failure = assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.from(MapConfigSource.of(values), MapConfigSource.of(new HashMap<>())));

        assertTrue(failure.getMessage().contains("nope"));
        assertTrue(failure.getMessage().contains("[cache, main]"));
    }

    @Test
    void rejectsABadPortWithoutEchoingIt() {
        Map<String, String> values = base();
        values.put("database.connections.main.port", "55a55");

        DatabaseConfigException failure = assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.from(MapConfigSource.of(values), MapConfigSource.of(new HashMap<>())));

        assertEquals("database.connections.main.port", failure.key().get());
        assertFalse(failure.getMessage().contains("55a55"));
    }

    @Test
    void rejectsAPortOutOfRange() {
        ConnectionConfig.Builder builder = ConnectionConfig.builder("main").driver("x");

        assertThrows(DatabaseConfigException.class, () -> builder.port(0));
        assertThrows(DatabaseConfigException.class, () -> builder.port(65_536));
    }

    @Test
    void rejectsABadBoolean() {
        Map<String, String> values = base();
        values.put("database.connections.main.strict_transactions", "maybe");

        DatabaseConfigException failure = assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.from(MapConfigSource.of(values), MapConfigSource.of(new HashMap<>())));

        assertEquals("database.connections.main.strict_transactions", failure.key().get());
    }

    @Test
    void builderRejectsADuplicateConnectionAndAnUnknownDefault() {
        ConnectionConfig one = ConnectionConfig.builder("a").driver("x").build();
        DatabaseConfig.Builder duplicate = DatabaseConfig.builder().connection(one);

        assertThrows(DatabaseConfigException.class, () -> duplicate.connection(one));
        assertThrows(DatabaseConfigException.class, () -> DatabaseConfig.builder().connection(one).build());
        assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.builder().defaultConnection("zzz").connection(one).build());
    }

    @Test
    void connectionLookupOfAnUnknownNameIsEmpty() {
        DatabaseConfig config = ManagerFixtures.config("a");

        assertFalse(config.connection("b").isPresent());
        assertFalse(config.connection(null).isPresent());
    }
}
