package org.aristonis.diwan.database.eloquent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

    private final JsonCodec codec = JsonCodec.standard();

    @Test
    void acceptsStrictInput() {
        List<String> accepted = Arrays.asList(
                "{}", "[]", "0", "-0", "1", "-12", "1.5", "1e3", "1E-3", "1.0e+2", "\"\"", "true",
                "false", "null", " \t\r\n[ 1 , 2 ] \n", "{\"a\":[{\"b\":null}]}", "\"a\\/b\\b\\f\\n\\r\\t\"");

        for (String text : accepted) {
            codec.decode(text);
        }
    }

    @Test
    void rejectsNonStrictInput() {
        List<String> rejected = Arrays.asList(
                "{\"a\":1}x", "[1,]", "{\"a\":1,}", "{'a':1}", "01", "-01", "00", "/* c */1", "// c\n1",
                "\uFEFF1", "", "   ", "[1 2]", "{\"a\" 1}", "{a:1}", "1.", ".5", "1e", "-", "+1", "NaN",
                "Infinity", "tru", "nul", "\"abc", "\"\\x\"", "\"\\u12\"", "\"a\nb\"", "[", "{", "]",
                "1 2", "\"\\u00zz\"");

        for (String text : rejected) {
            assertThrows(JsonException.class, () -> codec.decode(text), text);
        }
    }

    @Test
    void rejectsNullText() {
        assertThrows(JsonException.class, () -> codec.decode(null));
    }

    @Test
    void depthLimit() {
        assertInstanceOf(List.class, codec.decode(nested(512)));

        JsonException e = assertThrows(JsonException.class, () -> codec.decode(nested(513)));
        assertTrue(e.getMessage().contains("512"), e.getMessage());
    }

    @Test
    void unicodeEscapes() {
        assertEquals("\uD83D\uDE00", codec.decode("\"\\ud83d\\ude00\""));
        assertEquals("\uD83D\uDE00", codec.decode("\"\uD83D\uDE00\""));
        assertEquals("a\u0000b", codec.decode("\"a\\u0000b\""));

        assertThrows(JsonException.class, () -> codec.decode("\"\\ud83d\""));
        assertThrows(JsonException.class, () -> codec.decode("\"\\ud83dx\""));
        assertThrows(JsonException.class, () -> codec.decode("\"\\ude00\""));
        assertThrows(JsonException.class, () -> codec.decode("\"\\ud83d\\u0041\""));
        assertThrows(JsonException.class, () -> codec.decode("\"\uD83D\""));
    }

    @Test
    void numberTypes() {
        assertEquals(1L, codec.decode("1"));
        assertEquals(Long.MAX_VALUE, codec.decode("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, codec.decode("-9223372036854775808"));
        assertEquals(new BigDecimal("1.5"), codec.decode("1.5"));
        assertEquals(new BigDecimal("1e3"), codec.decode("1e3"));
    }

    @Test
    void bigNumbers() {
        assertEquals(new BigInteger("9223372036854775808"), codec.decode("9223372036854775808"));
        assertEquals(new BigInteger("-9223372036854775809"), codec.decode("-9223372036854775809"));
        assertEquals(new BigDecimal("1e400"), codec.decode("1e400"));
        assertThrows(JsonException.class, () -> codec.decode("1e99999999999"));
    }

    @Test
    void reencodeNeverRounds() {
        List<String> texts = Arrays.asList("0.1", "12345678901234567890", "[0.1,0.2,12345678901234567890]",
                "{\"id\":98765432109876543210123}");

        for (String text : texts) {
            assertEquals(text, codec.encode(codec.decode(text)));
        }
    }

    @Test
    void duplicateKeysLastWinsInFirstSeenOrder() {
        Object decoded = codec.decode("{\"b\":1,\"a\":2,\"b\":3}");

        assertInstanceOf(LinkedHashMap.class, decoded);
        Map<?, ?> map = (Map<?, ?>) decoded;
        assertEquals(Arrays.asList("b", "a"), new ArrayList<>(map.keySet()));
        assertEquals(3L, map.get("b"));
    }

    @Test
    void writerEscapes() {
        assertEquals("\"a/b\"", codec.encode("a/b"));
        assertEquals("\"\\\"\\\\\"", codec.encode("\"\\"));
        assertEquals("\"\\b\\f\\n\\r\\t\\u0000\\u001f\"", codec.encode("\b\f\n\r\t\u0000\u001f"));
        assertEquals("\"\\u00e9\\u20ac\\ud83d\\ude00\"", codec.encode("\u00e9\u20ac\uD83D\uDE00"));
        assertEquals("\"\\u2028\\u2029\"", codec.encode("\u2028\u2029"));
        assertEquals("\"\u007f\"", codec.encode("\u007f"));
        assertEquals("\"\\u0080\"", codec.encode("\u0080"));
    }

    @Test
    void encodeUnescapedKeepsUtf8() {
        assertEquals("\"\u00e9\u20ac\uD83D\uDE00\"", codec.encodeUnescaped("\u00e9\u20ac\uD83D\uDE00"));
        assertEquals("\"\\u2028\\u2029\\n\\\"\"", codec.encodeUnescaped("\u2028\u2029\n\""));
    }

    @Test
    void writerValueTypes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("s", "x");
        map.put("t", Boolean.TRUE);
        map.put("n", null);
        map.put("i", Arrays.asList((byte) 1, (short) 2, 3, 4L, BigInteger.TEN, new BigDecimal("1.50")));
        map.put("d", 1.5d);
        map.put("f", 0.5f);
        map.put("e", Collections.emptyMap());

        assertEquals("{\"s\":\"x\",\"t\":true,\"n\":null,\"i\":[1,2,3,4,10,1.50],\"d\":1.5,\"f\":0.5,\"e\":{}}",
                codec.encode(map));
        assertEquals("null", codec.encode(null));
    }

    @Test
    void nonFiniteRejected() {
        assertThrows(JsonException.class, () -> codec.encode(Double.NaN));
        assertThrows(JsonException.class, () -> codec.encode(Double.POSITIVE_INFINITY));
        assertThrows(JsonException.class, () -> codec.encode(Float.NEGATIVE_INFINITY));
        assertThrows(JsonException.class, () -> codec.encode(Collections.singletonList(Double.NaN)));
    }

    @Test
    void unsupportedTypeNamesClassOnly() {
        Object secret = new Object() {
            @Override
            public String toString() {
                return "SECRET-VALUE";
            }
        };

        JsonException e = assertThrows(JsonException.class, () -> codec.encode(Collections.singletonMap("k", secret)));
        assertFalse(e.getMessage().contains("SECRET-VALUE"), e.getMessage());
        assertTrue(e.getMessage().contains(secret.getClass().getName()), e.getMessage());

        Map<Object, Object> badKey = new HashMap<>();
        badKey.put(1, "v");
        assertThrows(JsonException.class, () -> codec.encode(badKey));
    }

    @Test
    void decodeMessagesNeverEchoInput() {
        JsonException e = assertThrows(JsonException.class, () -> codec.decode("{\"password\":\"hunter2\" x}"));

        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
        assertTrue(e.getMessage().contains("position"), e.getMessage());
    }

    @Test
    void cyclicStructureFailsCleanly() {
        List<Object> list = new ArrayList<>();
        list.add(list);
        Map<String, Object> map = new HashMap<>();
        map.put("self", map);

        assertThrows(JsonException.class, () -> codec.encode(list));
        assertThrows(JsonException.class, () -> codec.encode(map));
    }

    @Test
    void roundTrip() {
        String text = "{\"a\":[1,-2,3.25,true,false,null,\"x\\\"y\\u00e9\"],\"b\":{\"c\":{}},\"d\":[]}";

        Object decoded = codec.decode(text);

        assertEquals(text, codec.encode(decoded));
        assertEquals(decoded, codec.decode(codec.encode(decoded)));
        assertNull(codec.decode("null"));
    }

    private static String nested(int depth) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            text.append('[');
        }
        for (int i = 0; i < depth; i++) {
            text.append(']');
        }
        return text.toString();
    }
}
