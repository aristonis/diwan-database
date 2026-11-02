package org.aristonis.diwan.database.eloquent.casts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.json.JsonCodec;
import org.aristonis.diwan.database.eloquent.json.JsonException;
import org.junit.jupiter.api.Test;

class JsonCastTest {

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();

    private final JsonCasts json = new JsonCasts("json", false);

    private final JsonCasts unicode = new JsonCasts("json:unicode", true);

    @Test
    void emptyStringReadsNull() {
        assertNull(json.get(null, "data", "", NO_ATTRIBUTES));
    }

    @Test
    void brokenJsonThrows() {
        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> json.get(null, "data", "{\"a\":", NO_ATTRIBUTES));

        assertEquals("data", e.getKey());
        assertEquals("json", e.getCastName());
        assertTrue(e.getCause() instanceof JsonException);
    }

    @Test
    void unsupportedRawTypeThrows() {
        assertThrows(InvalidCastException.class, () -> json.get(null, "data", 5L, NO_ATTRIBUTES));
    }

    @Test
    void bytesAreDecoded() {
        byte[] raw = "{\"name\":\"café\"}".getBytes(StandardCharsets.UTF_8);

        Object value = json.get(null, "data", raw, NO_ATTRIBUTES);

        assertEquals(Map.of("name", "café"), value);
    }

    @Test
    void unsupportedValueThrows() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", new Object());

        JsonException e = assertThrows(JsonException.class, () -> json.set(null, "data", map, NO_ATTRIBUTES));

        assertTrue(e.getMessage().contains("java.lang.Object"));
    }

    @Test
    void nanInMapThrows() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", Double.NaN);

        assertThrows(JsonException.class, () -> json.set(null, "data", map, NO_ATTRIBUTES));
    }

    @Test
    void bigIntegerSurvivesRoundTrip() {
        String raw = "{\"id\":9223372036854775808,\"small\":12,\"price\":0.1}";

        Object value = json.get(null, "data", raw, NO_ATTRIBUTES);
        Map<?, ?> map = (Map<?, ?>) value;

        assertEquals(new BigInteger("9223372036854775808"), map.get("id"));
        assertEquals(12L, map.get("small"));
        assertEquals(new BigDecimal("0.1"), map.get("price"));
        assertEquals(raw, json.set(null, "data", value, NO_ATTRIBUTES));
    }

    @Test
    void stringSetEncodesAsJsonString() {
        assertEquals("\"{\\\"a\\\":1}\"", json.set(null, "data", "{\"a\":1}", NO_ATTRIBUTES));
    }

    @Test
    void nullSetStoresNull() {
        assertNull(json.set(null, "data", null, NO_ATTRIBUTES));
    }

    @Test
    void unicodeCastKeepsNonAscii() {
        List<Object> list = new ArrayList<>();
        list.add("café");

        assertEquals("[\"café\"]", unicode.set(null, "data", list, NO_ATTRIBUTES));
        assertEquals("[\"caf\\u00e9\"]", json.set(null, "data", list, NO_ATTRIBUTES));
    }

    @Test
    void serializeReturnsValue() {
        Map<String, Object> map = Map.of("a", 1L);

        assertEquals(map, json.serialize(null, "data", map, NO_ATTRIBUTES));
    }

    @Test
    void compareIgnoresKeyOrderAndSpacing() {
        assertTrue(json.compare(null, "data", "{\"a\":1,\"b\":[1,2]}", "{\"b\": [1, 2], \"a\": 1}"));
    }

    @Test
    void compareTreatsOneAndOnePointZeroAsEqual() {
        assertTrue(json.compare(null, "data", "{\"a\":1}", "{\"a\":1.0}"));
        assertTrue(json.compare(null, "data", "[100]", "[1e2]"));
    }

    @Test
    void compareDetectsRealChanges() {
        assertFalse(json.compare(null, "data", "{\"a\":1}", "{\"a\":2}"));
        assertFalse(json.compare(null, "data", "[1,2]", "[2,1]"));
        assertFalse(json.compare(null, "data", "{\"a\":1}", "{\"b\":1}"));
        assertFalse(json.compare(null, "data", "{\"a\":1}", null));
    }

    @Test
    void compareHandlesBytesAndBrokenText() {
        byte[] bytes = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        assertTrue(json.compare(null, "data", bytes, "{\"a\": 1}"));
        assertFalse(json.compare(null, "data", "{\"a\":", "{\"a\":1}"));
        assertTrue(json.compare(null, "data", "", null));
    }

    @Test
    void structuralCompareStopsPastDepthLimit() {
        Object deep = nest(JsonCasts.MAX_DEPTH + 1);
        Object shallow = nest(JsonCasts.MAX_DEPTH - 1);

        assertFalse(JsonCasts.structurallyEqual(deep, nest(JsonCasts.MAX_DEPTH + 1), 0));
        assertTrue(JsonCasts.structurallyEqual(shallow, nest(JsonCasts.MAX_DEPTH - 1), 0));
    }

    private static Object nest(int depth) {
        Object value = 1L;
        for (int i = 0; i < depth; i++) {
            List<Object> list = new ArrayList<>();
            list.add(value);
            value = list;
        }
        return value;
    }

    /** Wraps the standard codec and counts its calls. */
    static final class RecordingCodec implements JsonCodec {
        int encodes;
        int decodes;

        @Override
        public String encode(Object value) {
            encodes++;
            return JsonCodec.standard().encode(value);
        }

        @Override
        public Object decode(String text) {
            decodes++;
            return JsonCodec.standard().decode(text);
        }
    }

    /** A model with its own codec and one json attribute. */
    static final class Settings extends Model {
        final RecordingCodec codec = new RecordingCodec();

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("data", Casts.json());
        }

        @Override
        protected JsonCodec jsonCodec() {
            return codec;
        }
    }

    @Test
    void customCodecIsUsed() {
        Settings settings = new Settings();
        settings.setRawAttributes(Map.of("data", "{\"a\":1}"), true);

        assertEquals(Map.of("a", 1L), settings.getAttribute("data"));
        settings.setAttribute("data", Map.of("b", 2L));

        assertSame(settings.codec, settings.getJsonCodec());
        assertEquals(1, settings.codec.decodes);
        assertEquals(1, settings.codec.encodes);
        assertEquals("{\"b\":2}", settings.getAttributes().get("data"));
    }
}
