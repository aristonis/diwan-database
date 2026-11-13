package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.junit.jupiter.api.Test;

/** The binary casts on a model: 16-byte storage, strict ULID text, failures, dirty checks and serialization. */
class BinaryCastTest {

    private static final int BYTES = 16;
    private static final String CAST_NAME = "binary";
    private static final String UUID_TEXT = "550e8400-e29b-41d4-a716-446655440000";
    private static final String ULID_TEXT = "01arz3ndektsv4rrffq69g5fav";
    private static final String ULID_HEX = "01563e3ab5d3d6764c61efb99302bd5b";
    private static final String BAD_ULID = "ULID text must be 26 characters of 0-9 and A-Z without I, L, O and U,"
            + " starting with 0-7";
    private static final String NOT_16 = "stored value is not 16 bytes long";

    /** A model with a uuid, a ulid and a factory-built uuid attribute. */
    static final class Ledger extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("id", Casts.of("binary:uuid"));
            casts.put("ref", Casts.of("binary:ulid"));
            casts.put("typed", Casts.binaryUuid());
            return casts;
        }
    }

    private static Ledger stored(Object... pairs) {
        Ledger ledger = new Ledger();
        ledger.setRawAttributes(row(pairs), true);
        return ledger;
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    private static byte[] filled(int fill) {
        byte[] bytes = new byte[BYTES];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void assertBinaryFailure(InvalidCastException e, String key) {
        assertEquals(CAST_NAME, e.getCastName());
        assertEquals(key, e.getKey());
        assertNull(e.getCause());
    }

    @Test
    void aUuidIsStoredAsSixteenBytes() {
        Ledger ledger = new Ledger();

        ledger.setAttribute("id", UUID.fromString(UUID_TEXT));

        byte[] raw = (byte[]) ledger.getRawAttribute("id");
        assertEquals(BYTES, raw.length);
        assertArrayEquals(hex("550e8400e29b41d4a716446655440000"), raw);
    }

    @Test
    void aUuidReadsBackFromSixteenBytes() {
        Ledger ledger = stored("id", hex("550e8400e29b41d4a716446655440000"));

        assertEquals(UUID.fromString(UUID_TEXT), ledger.getAttribute("id"));
    }

    @Test
    void aUlidIsStoredAsSixteenBytesInAnyCase() {
        String[] texts = {ULID_TEXT, ULID_TEXT.toUpperCase(java.util.Locale.ROOT), "01ArZ3nDeKtSv4RrFfQ69g5FaV"};
        for (String text : texts) {
            Ledger ledger = new Ledger();

            ledger.setAttribute("ref", text);

            assertArrayEquals(hex(ULID_HEX), (byte[]) ledger.getRawAttribute("ref"), text);
        }
    }

    @Test
    void aUlidReadsBackInLowerCase() {
        Ledger ledger = stored("ref", hex(ULID_HEX));

        assertEquals(ULID_TEXT, ledger.getAttribute("ref"));
    }

    @Test
    void largestAndSmallestUlidStoreFfAndZeroBytes() {
        Ledger ledger = new Ledger();

        ledger.setAttribute("ref", "7" + repeat('z', 25));
        assertArrayEquals(filled(0xff), (byte[]) ledger.getRawAttribute("ref"));
        ledger.setAttribute("ref", repeat('0', 26));
        assertArrayEquals(filled(0), (byte[]) ledger.getRawAttribute("ref"));
        assertEquals("7" + repeat('z', 25), stored("ref", filled(0xff)).getAttribute("ref"));
        assertEquals(repeat('0', 26), stored("ref", filled(0)).getAttribute("ref"));
    }

    @Test
    void uuidWriteRefusesStringByteArrayAndInteger() {
        Object[] values = {UUID_TEXT, new byte[BYTES], 424242};
        String[] names = {"String", "byte[]", "Integer"};
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            Ledger ledger = new Ledger();

            InvalidCastException e = assertThrows(InvalidCastException.class,
                    () -> ledger.setAttribute("id", value), names[i]);

            assertBinaryFailure(e, "id");
            assertTrue(e.getMessage().contains("expected a UUID, got a " + names[i]), e.getMessage());
            assertFalse(e.getMessage().contains(UUID_TEXT), e.getMessage());
            assertFalse(e.getMessage().contains("424242"), e.getMessage());
        }
    }

    @Test
    void ulidWriteRefusesByteArrayUuidAndInteger() {
        Object[] values = {new byte[BYTES], UUID.fromString(UUID_TEXT), 424242};
        String[] names = {"byte[]", "UUID", "Integer"};
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            Ledger ledger = new Ledger();

            InvalidCastException e = assertThrows(InvalidCastException.class,
                    () -> ledger.setAttribute("ref", value), names[i]);

            assertBinaryFailure(e, "ref");
            assertTrue(e.getMessage().contains("expected a String, got a " + names[i]), e.getMessage());
            assertFalse(e.getMessage().contains(UUID_TEXT), e.getMessage());
            assertFalse(e.getMessage().contains("424242"), e.getMessage());
        }
    }

    @Test
    void ulidWriteRefusesEveryRuleBreak() {
        String tail = ULID_TEXT.substring(1);
        String[] broken = {
            "", "   ", tail, ULID_TEXT + "0", "i" + tail, "I" + tail, "l" + tail, "L" + tail, "o" + tail,
            "O" + tail, "u" + tail, "U" + tail, "-" + tail, "é" + tail, "8" + tail, "Z" + tail,
            " " + tail, tail + " ", " " + ULID_TEXT.substring(1, 26), ULID_TEXT.substring(0, 25) + " "
        };
        for (String text : broken) {
            Ledger ledger = new Ledger();

            InvalidCastException e = assertThrows(InvalidCastException.class,
                    () -> ledger.setAttribute("ref", text), "[" + text + "]");

            assertBinaryFailure(e, "ref");
            assertTrue(e.getMessage().endsWith(BAD_ULID), e.getMessage());
            assertTrue(text.trim().length() < 3 || !e.getMessage().contains(text.trim()), e.getMessage());
        }
    }

    @Test
    void readRefusesOtherStoredShapes() {
        Object[] shapes = {new byte[BYTES - 1], new byte[BYTES + 1], new byte[0], UUID_TEXT,
            UUID.fromString(UUID_TEXT), 424242};
        String[] reasons = {NOT_16, NOT_16, NOT_16, "stored value is a String, expected 16 bytes",
            "stored value is a UUID, expected 16 bytes", "stored value is a Integer, expected 16 bytes"};
        String[] keys = {"id", "ref"};
        for (String key : keys) {
            for (int i = 0; i < shapes.length; i++) {
                Ledger ledger = stored(key, shapes[i]);

                InvalidCastException e = assertThrows(InvalidCastException.class,
                        () -> ledger.getAttribute(key), key + " " + reasons[i]);

                assertBinaryFailure(e, key);
                assertTrue(e.getMessage().contains(reasons[i]), e.getMessage());
                assertFalse(e.getMessage().contains(UUID_TEXT), e.getMessage());
                assertFalse(e.getMessage().contains("424242"), e.getMessage());
            }
        }
    }

    @Test
    void nullReadsNullAndStoresNull() {
        Ledger read = stored("id", null, "ref", null);
        Ledger written = new Ledger();

        written.setAttribute("id", null);
        written.setAttribute("ref", null);

        assertNull(read.getAttribute("id"));
        assertNull(read.getAttribute("ref"));
        assertNull(written.getRawAttribute("id"));
        assertNull(written.getRawAttribute("ref"));
        assertTrue(written.getAttributes().containsKey("id"));
    }

    @Test
    void settingAnEqualUuidIsNotDirty() {
        Ledger ledger = stored("id", hex("550e8400e29b41d4a716446655440000"));

        ledger.setAttribute("id", UUID.fromString(UUID_TEXT));

        assertTrue(ledger.isClean("id"));
        assertTrue(ledger.getDirty().isEmpty());
    }

    @Test
    void settingTheSameUlidInUpperCaseIsNotDirty() {
        Ledger ledger = stored("ref", hex(ULID_HEX));

        ledger.setAttribute("ref", ULID_TEXT.toUpperCase(java.util.Locale.ROOT));

        assertTrue(ledger.isClean("ref"));
    }

    @Test
    void settingADifferentValueIsDirty() {
        Ledger ledger = stored("id", hex("550e8400e29b41d4a716446655440000"), "ref", hex(ULID_HEX));

        ledger.setAttribute("id", new UUID(1L, 2L));
        ledger.setAttribute("ref", repeat('0', 26));

        assertTrue(ledger.isDirty("id"));
        assertTrue(ledger.isDirty("ref"));
        assertEquals(new UUID(1L, 2L), ledger.getAttribute("id"));
    }

    @Test
    void toMapAndToJsonEmitCanonicalText() {
        Ledger ledger = stored("id", hex("550e8400e29b41d4a716446655440000"), "ref", hex(ULID_HEX), "typed", null);

        Map<String, Object> map = ledger.toMap();
        String json = ledger.toJson();

        assertEquals(UUID_TEXT, map.get("id"));
        assertEquals(ULID_TEXT, map.get("ref"));
        assertEquals(26, ((String) map.get("ref")).length());
        assertNull(map.get("typed"));
        assertTrue(json.contains("\"id\":\"" + UUID_TEXT + "\""), json);
        assertTrue(json.contains("\"ref\":\"" + ULID_TEXT + "\""), json);
    }

    @Test
    void aUuidWhoseBytesAreValidUtf8RoundTrips() {
        UUID utf8 = new UUID(0x61626364656644c3L, 0xa96768696a6b6c6dL);
        UUID ascii = new UUID(0x6162636465664467L, 0x68696a6b6c6d6e6fL);
        byte[] utf8Bytes = hex("61626364656644c3a96768696a6b6c6d");
        byte[] asciiBytes = hex("616263646566446768696a6b6c6d6e6f");

        assertEquals(utf8, stored("id", utf8Bytes).getAttribute("id"));
        assertEquals(ascii, stored("id", asciiBytes).getAttribute("id"));
        Ledger ledger = new Ledger();
        ledger.setAttribute("id", utf8);
        assertArrayEquals(utf8Bytes, (byte[]) ledger.getRawAttribute("id"));
        ledger.setAttribute("id", ascii);
        assertArrayEquals(asciiBytes, (byte[]) ledger.getRawAttribute("id"));
    }

    @Test
    void hasCastFindsBinary() {
        Ledger ledger = new Ledger();

        assertTrue(ledger.hasCast("id", "binary"));
        assertTrue(ledger.hasCast("id", "BINARY"));
        assertTrue(ledger.hasCast("typed", "binary"));
        assertFalse(ledger.hasCast("id", "uuid"));
    }
}
