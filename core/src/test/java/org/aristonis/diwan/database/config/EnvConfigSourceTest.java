package org.aristonis.diwan.database.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvConfigSourceTest {

    private static EnvConfigSource env() {
        Map<String, String> vars = new HashMap<>();
        vars.put("DB_PASSWORD", "s3cret-value");
        vars.put("DATABASE_DEFAULT", "cache");
        vars.put("lower.name", "exact");
        return EnvConfigSource.of(vars);
    }

    @Test
    void findsAVariableByItsExactName() {
        assertEquals("s3cret-value", env().get("DB_PASSWORD").orElseThrow());
        assertEquals("exact", env().get("lower.name").orElseThrow());
    }

    @Test
    void findsADottedKeyInEnvironmentForm() {
        assertEquals("cache", env().get("database.default").orElseThrow());
    }

    @Test
    void returnsEmptyForAnUnsetVariable() {
        assertFalse(env().get("NOT_SET").isPresent());
    }

    @Test
    void listsVariableNamesAtTheTopLevel() {
        assertEquals(3, env().keys("").size());
        assertEquals(1, env().keys("lower").size());
        assertEquals("name", env().keys("lower").iterator().next());
    }

    @Test
    void copiesTheMapItIsGiven() {
        Map<String, String> vars = new HashMap<>();
        vars.put("A", "1");
        EnvConfigSource source = EnvConfigSource.of(vars);

        vars.put("B", "2");

        assertFalse(source.get("B").isPresent());
    }

    @Test
    void systemSourceReadsTheProcessEnvironmentAndDoesNotInventVariables() {
        EnvConfigSource source = EnvConfigSource.system();

        assertNotNull(source);
        assertFalse(source.get("DIWAN_SURELY_NOT_SET_VARIABLE_9F3A").isPresent());
    }

    @Test
    void toStringShowsTheCountAndNoValues() {
        String text = env().toString();

        assertEquals("EnvConfigSource[keys=3]", text);
        assertFalse(text.contains("s3cret"));
    }
}
