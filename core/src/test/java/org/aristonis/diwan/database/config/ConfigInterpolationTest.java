package org.aristonis.diwan.database.config;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.aristonis.diwan.database.manager.DatabaseConfigException;
import org.junit.jupiter.api.Test;

class ConfigInterpolationTest {

    private static ConfigSource vars(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return MapConfigSource.of(map);
    }

    private static String resolve(String raw, ConfigSource vars) {
        return ConfigInterpolator.resolve("database.connections.main.password", raw, vars);
    }

    @Test
    void replacesAVariableFromTheSource() {
        assertEquals("pw-1", resolve("${DB_PASSWORD}", vars("DB_PASSWORD", "pw-1")));
        assertEquals("jdbc:x://h:5432/d", resolve("jdbc:x://${HOST}:${PORT}/d", vars("HOST", "h", "PORT", "5432")));
    }

    @Test
    void returnsTheSameTextWhenThereIsNothingToReplace() {
        String raw = "plain value";

        assertSame(raw, resolve(raw, vars()));
        assertEquals("cost $5 and {braces}", resolve("cost $5 and {braces}", vars()));
    }

    @Test
    void failsNamingTheKeyAndVariableButNeverAValue() {
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class,
                () -> resolve("prefix-${DB_PASSWORD}", vars("OTHER", "s3cret-value")));

        assertTrue(e.getMessage().contains("database.connections.main.password"), e.getMessage());
        assertTrue(e.getMessage().contains("DB_PASSWORD"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cret-value"), e.getMessage());
        assertFalse(e.getMessage().contains("prefix-"), e.getMessage());
        assertEquals(Optional.of("main"), e.connection());
        assertEquals("database.connections.main.password", e.key().orElse(null));
    }

    @Test
    void leavesTheConnectionOutWhenTheKeyIsNotUnderAConnection() {
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class,
                () -> ConfigInterpolator.resolve("database.default", "${NAME}", vars()));

        assertEquals(Optional.empty(), e.connection());
        assertEquals("database.default", e.key().orElse(null));
    }

    @Test
    void usesTheFallbackOnlyWhenTheVariableIsMissing() {
        assertEquals("dflt", resolve("${X:-dflt}", vars()));
        assertEquals("set", resolve("${X:-dflt}", vars("X", "set")));
        assertEquals("", resolve("${X:-}", vars()));
        assertEquals("", resolve("${X:-dflt}", vars("X", "")));
    }

    @Test
    void turnsDoubleDollarBraceIntoALiteral() {
        assertEquals("${literal}", resolve("$${literal}", vars("literal", "no")));
        assertEquals("a ${ b", resolve("a $${ b", vars()));
    }

    @Test
    void doesNotScanReplacementTextAgain() {
        assertEquals("${B}", resolve("${A}", vars("A", "${B}", "B", "no")));
    }

    @Test
    void rejectsAnUnclosedReferenceAndAnEmptyName() {
        DatabaseConfigException open = assertThrows(DatabaseConfigException.class, () -> resolve("${NAME", vars()));
        DatabaseConfigException empty = assertThrows(DatabaseConfigException.class, () -> resolve("a${}b", vars()));
        DatabaseConfigException blank = assertThrows(DatabaseConfigException.class, () -> resolve("${:-x}", vars()));

        assertTrue(open.getMessage().contains("database.connections.main.password"), open.getMessage());
        assertTrue(empty.getMessage().contains("no variable name"), empty.getMessage());
        assertTrue(blank.getMessage().contains("no variable name"), blank.getMessage());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> ConfigInterpolator.resolve(null, "x", vars()));
        assertThrows(NullPointerException.class, () -> ConfigInterpolator.resolve("k", null, vars()));
        assertThrows(NullPointerException.class, () -> ConfigInterpolator.resolve("k", "x", null));
    }

    @Test
    void aReferenceInsideAFallbackFailsNamingTheKey() {
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class,
                () -> resolve("${A:-${B}}", vars("B", "b")));

        assertTrue(e.getMessage().contains("database.connections.main.password"), e.getMessage());
        assertTrue(e.getMessage().contains("nested"), e.getMessage());
    }
}
