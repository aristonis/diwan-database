package org.aristonis.diwan.database.eloquent.casts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The id byte helpers: UUID and ULID to 16 bytes and back, strict ULID text, null arguments. */
class BinaryIdsTest {

    private static final int BYTES = 16;
    private static final int CHARS = 26;
    private static final String SPEC_TEXT = "01arz3ndektsv4rrffq69g5fav";
    private static final String SPEC_HEX = "01563e3ab5d3d6764c61efb99302bd5b";
    private static final String UUID_TEXT = "550e8400-e29b-41d4-a716-446655440000";

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

    @Test
    void uuidBytesAreMostSignificantFirst() {
        byte[] bytes = BinaryIds.uuidBytes(UUID.fromString(UUID_TEXT));

        assertArrayEquals(hex("550e8400e29b41d4a716446655440000"), bytes);
    }

    @Test
    void uuidBytesReturnsANewArrayEachCall() {
        UUID uuid = UUID.fromString(UUID_TEXT);

        byte[] first = BinaryIds.uuidBytes(uuid);
        first[0] = 0;
        byte[] second = BinaryIds.uuidBytes(uuid);

        assertNotSame(first, second);
        assertEquals(0x55, second[0]);
    }

    @Test
    void ulidBytesDecodeTheSpecExample() {
        assertArrayEquals(hex(SPEC_HEX), BinaryIds.ulidBytes(SPEC_TEXT));
    }

    @Test
    void ulidBytesIgnoreLetterCase() {
        byte[] expected = hex(SPEC_HEX);
        String mixed = "01ArZ3nDeKtSv4RrFfQ69g5FaV";

        assertArrayEquals(expected, BinaryIds.ulidBytes(SPEC_TEXT.toUpperCase(Locale.ROOT)));
        assertArrayEquals(expected, BinaryIds.ulidBytes(SPEC_TEXT));
        assertArrayEquals(expected, BinaryIds.ulidBytes(mixed));
    }

    @Test
    void ulidBoundsAreSixteenFfAndSixteenZero() {
        assertArrayEquals(filled(0xff), BinaryIds.ulidBytes("7" + repeat('z', CHARS - 1)));
        assertArrayEquals(filled(0xff), BinaryIds.ulidBytes("7" + repeat('Z', CHARS - 1)));
        assertArrayEquals(filled(0), BinaryIds.ulidBytes(repeat('0', CHARS)));
    }

    @Test
    void ulidBytesRefuseBadText() {
        String tail = SPEC_TEXT.substring(1);
        String[] broken = {
            "", "   ", SPEC_TEXT.substring(1), SPEC_TEXT + "0",
            "i" + tail, "I" + tail, "l" + tail, "L" + tail, "o" + tail, "O" + tail, "u" + tail, "U" + tail,
            "0i" + tail.substring(1), "0L" + tail.substring(1), "0o" + tail.substring(1),
            "0U" + tail.substring(1), "-" + tail, "é" + tail, "8" + tail, "9" + tail, "z" + tail,
            "Z" + tail, " " + tail, tail + " ", " " + SPEC_TEXT.substring(1), SPEC_TEXT.substring(1) + " "
        };
        for (String text : broken) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> BinaryIds.ulidBytes(text), "[" + text + "]");

            assertEquals(BinaryIds.BAD_ULID, e.getMessage());
        }
    }

    @Test
    void nullArgumentsThrowIllegalArgument() {
        IllegalArgumentException uuid = assertThrows(IllegalArgumentException.class,
                () -> BinaryIds.uuidBytes(null));
        IllegalArgumentException ulid = assertThrows(IllegalArgumentException.class,
                () -> BinaryIds.ulidBytes(null));

        assertEquals("uuid must not be null", uuid.getMessage());
        assertEquals("ulid must not be null", ulid.getMessage());
    }

    @Test
    void readersInvertTheWriters() {
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            UUID uuid = new UUID(random.nextLong(), random.nextLong());
            byte[] raw = new byte[BYTES];
            random.nextBytes(raw);
            String ulid = BinaryIds.ulidOf(raw);

            assertEquals(uuid, BinaryIds.uuidOf(BinaryIds.uuidBytes(uuid)));
            assertEquals(ulid, BinaryIds.ulidOf(BinaryIds.ulidBytes(ulid.toUpperCase(Locale.ROOT))));
            assertArrayEquals(raw, BinaryIds.ulidBytes(ulid));
        }
    }

    @Test
    void ulidOfIsLowerCaseAndTwentySixLong() {
        String text = BinaryIds.ulidOf(hex(SPEC_HEX));

        assertEquals(SPEC_TEXT, text);
        assertEquals(CHARS, text.length());
        assertTrue(text.equals(text.toLowerCase(Locale.ROOT)));
    }
}
