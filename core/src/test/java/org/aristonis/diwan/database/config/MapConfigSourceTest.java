package org.aristonis.diwan.database.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapConfigSourceTest {

    private static Map<String, String> sample() {
        Map<String, String> map = new HashMap<>();
        map.put("a.b.x", "1");
        map.put("a.b.y.z", "2");
        map.put("a.c", "3");
        map.put("top", "4");
        map.put("empty", "");
        return map;
    }

    @Test
    void returnsStoredValueAndEmptyForAMissingKey() {
        MapConfigSource source = MapConfigSource.of(sample());

        assertEquals("1", source.get("a.b.x").orElseThrow());
        assertFalse(source.get("a.b").isPresent());
        assertEquals("", source.get("empty").orElseThrow());
    }

    @Test
    void listsDirectChildSegmentsSortedForAPrefix() {
        MapConfigSource source = MapConfigSource.of(sample());

        assertEquals(Arrays.asList("b", "c"), Arrays.asList(source.keys("a").toArray()));
        assertEquals(Arrays.asList("x", "y"), Arrays.asList(source.keys("a.b").toArray()));
        assertEquals(Arrays.asList("a", "empty", "top"), Arrays.asList(source.keys("").toArray()));
    }

    @Test
    void doesNotMatchAPrefixThatIsOnlyTheStartOfASegment() {
        MapConfigSource source = MapConfigSource.of(sample());

        assertTrue(source.keys("a.").isEmpty());
        assertTrue(source.keys("a.b.x").isEmpty());
        assertTrue(source.keys("missing").isEmpty());
    }

    @Test
    void returnsUnmodifiableKeys() {
        MapConfigSource source = MapConfigSource.of(sample());

        assertThrows(UnsupportedOperationException.class, () -> source.keys("a").add("z"));
    }

    @Test
    void copiesTheMapSoLaterChangesDoNotShowThrough() {
        Map<String, String> map = sample();
        MapConfigSource source = MapConfigSource.of(map);

        map.put("late", "x");
        map.put("top", "changed");

        assertFalse(source.get("late").isPresent());
        assertEquals("4", source.get("top").orElseThrow());
    }

    @Test
    void requireReturnsTheValueOrNamesTheMissingKey() {
        MapConfigSource source = MapConfigSource.of(sample());

        assertEquals("3", source.require("a.c"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> source.require("nope"));
        assertEquals("Missing config key [nope]", e.getMessage());
    }

    @Test
    void rejectsNullEntries() {
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("k", null);
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "v");

        assertThrows(NullPointerException.class, () -> MapConfigSource.of(null));
        assertThrows(NullPointerException.class, () -> MapConfigSource.of(nullValue));
        assertThrows(NullPointerException.class, () -> MapConfigSource.of(nullKey));
        assertThrows(NullPointerException.class, () -> MapConfigSource.of(sample()).get(null));
    }

    @Test
    void toStringShowsTheCountAndNoValues() {
        Map<String, String> map = new HashMap<>();
        map.put("database.password", "s3cret-value");

        String text = MapConfigSource.of(map).toString();

        assertEquals("MapConfigSource[keys=1]", text);
        assertFalse(text.contains("s3cret"));
        assertEquals(new HashSet<>(Arrays.asList("database")), MapConfigSource.of(map).keys(""));
    }
}
