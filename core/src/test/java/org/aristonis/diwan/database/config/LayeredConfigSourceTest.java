package org.aristonis.diwan.database.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LayeredConfigSourceTest {

    private static Map<String, String> map(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void firstLayerWinsOverEnvironmentOverFile() {
        ConfigSource code = MapConfigSource.of(map("database.default", "code"));
        ConfigSource env = EnvConfigSource.of(map("DATABASE_DEFAULT", "env", "DATABASE_HOST", "env-host"));
        ConfigSource file = MapConfigSource.of(map("database.default", "file", "database.host", "file-host",
                "database.port", "5432"));

        LayeredConfigSource layered = LayeredConfigSource.of(code, env, file);

        assertEquals("code", layered.get("database.default").orElseThrow());
        assertEquals("env-host", layered.get("database.host").orElseThrow());
        assertEquals("5432", layered.get("database.port").orElseThrow());
        assertFalse(layered.get("database.user").isPresent());
    }

    @Test
    void anEmptyValueInAHigherLayerStillWins() {
        LayeredConfigSource layered = LayeredConfigSource.of(MapConfigSource.of(map("k", "")),
                MapConfigSource.of(map("k", "low")));

        assertEquals("", layered.get("k").orElseThrow());
    }

    @Test
    void keysAreTheSortedUnionOfEveryLayer() {
        LayeredConfigSource layered = LayeredConfigSource.of(
                MapConfigSource.of(map("database.connections.main.driver", "x", "database.connections.zeta.driver", "x")),
                MapConfigSource.of(map("database.connections.cache.driver", "x", "database.connections.main.url", "u")));

        assertEquals(Arrays.asList("cache", "main", "zeta"),
                Arrays.asList(layered.keys("database.connections").toArray()));
        assertEquals(Arrays.asList("driver", "url"),
                Arrays.asList(layered.keys("database.connections.main").toArray()));
    }

    @Test
    void anEmptyListOfLayersHasNoValues() {
        LayeredConfigSource layered = LayeredConfigSource.of();

        assertFalse(layered.get("any").isPresent());
        assertEquals(0, layered.keys("").size());
    }

    @Test
    void copiesTheArrayOfLayers() {
        ConfigSource[] layers = {MapConfigSource.of(map("a", "1"))};
        LayeredConfigSource layered = LayeredConfigSource.of(layers);

        layers[0] = MapConfigSource.of(map("a", "2"));

        assertEquals("1", layered.get("a").orElseThrow());
    }

    @Test
    void rejectsNullLayers() {
        assertThrows(NullPointerException.class, () -> LayeredConfigSource.of((ConfigSource[]) null));
        assertThrows(NullPointerException.class, () -> LayeredConfigSource.of(MapConfigSource.of(map()), null));
    }

    @Test
    void requireFailsWhenNoLayerHasTheKey() {
        LayeredConfigSource layered = LayeredConfigSource.of(MapConfigSource.of(map("a", "1")));

        assertEquals("1", layered.require("a"));
        assertThrows(IllegalArgumentException.class, () -> layered.require("b"));
    }

    @Test
    void toStringShowsTheLayerCountAndNoValues() {
        LayeredConfigSource layered = LayeredConfigSource.of(MapConfigSource.of(map("password", "s3cret-value")));

        assertEquals("LayeredConfigSource[layers=1]", layered.toString());
    }
}
