package org.aristonis.diwan.database.eloquent.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.aristonis.diwan.database.eloquent.UniqueIds;
import org.junit.jupiter.api.Test;

/** The Crockford base-32 holder: the 128-bit text form, its bounds, strict decoding and locale independence. */
class CrockfordTest {

    private static final int BYTES = 16;
    private static final int CHARS = 26;
    private static final int TIME_BYTES = 6;
    private static final String SPEC_TEXT = "01arz3ndektsv4rrffq69g5fav";

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

    /** Random bits that are all one fixed byte. */
    private static Random constant(byte fill) {
        return new Random() {
            private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, fill);
            }
        };
    }

    @Test
    void encodesTheSpecExample() {
        byte[] bytes = hex("01563e3ab5d3d6764c61efb99302bd5b");

        assertEquals(SPEC_TEXT, Crockford.encode128(bytes));
        assertArrayEquals(bytes, Crockford.decode128(SPEC_TEXT));
    }

    @Test
    void encodesBoundsAndRoundTrips() {
        assertEquals("7" + repeat('z', CHARS - 1), Crockford.encode128(filled(0xff)));
        assertEquals(repeat('0', CHARS), Crockford.encode128(filled(0)));
        assertArrayEquals(filled(0xff), Crockford.decode128("7" + repeat('z', CHARS - 1)));
        assertArrayEquals(filled(0), Crockford.decode128(repeat('0', CHARS)));
        Random random = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            byte[] bytes = new byte[BYTES];
            random.nextBytes(bytes);

            String text = Crockford.encode128(bytes);

            assertEquals(CHARS, text.length());
            assertArrayEquals(bytes, Crockford.decode128(text));
        }
    }

    @Test
    void decodeReturnsNullForEveryRuleBreak() {
        String valid = SPEC_TEXT;
        String[] broken = {
            valid.substring(1), valid + "0", "", " ", repeat(' ', CHARS),
            " " + valid.substring(1), valid.substring(1) + " ",
            "i" + valid.substring(1), "I" + valid.substring(1), "l" + valid.substring(1),
            "L" + valid.substring(1), "o" + valid.substring(1), "O" + valid.substring(1),
            "u" + valid.substring(1), "U" + valid.substring(1),
            "0i" + valid.substring(2), "0L" + valid.substring(2), "0o" + valid.substring(2),
            "0U" + valid.substring(2), "-" + valid.substring(1), "0" + "-" + valid.substring(2),
            "０" + valid.substring(1), "é" + valid.substring(1), "0é" + valid.substring(2),
            "8" + valid.substring(1), "9" + valid.substring(1), "z" + valid.substring(1),
            "Z" + valid.substring(1)
        };
        for (String text : broken) {
            assertNull(Crockford.decode128(text), "[" + text + "]");
        }
    }

    @Test
    void decodeAcceptsBothCasesWhateverTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr"));

            byte[] lower = Crockford.decode128(SPEC_TEXT);
            byte[] upper = Crockford.decode128(SPEC_TEXT.toUpperCase(Locale.ROOT));

            assertNotNull(lower);
            assertArrayEquals(lower, upper);
            assertEquals(SPEC_TEXT, Crockford.encode128(lower));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void encodeRejectsOtherLengths() {
        assertThrows(IllegalArgumentException.class, () -> Crockford.encode128(new byte[BYTES - 1]));
        assertThrows(IllegalArgumentException.class, () -> Crockford.encode128(new byte[BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> Crockford.encode128(new byte[0]));
    }

    @Test
    void ulidGenerationUsesTheHolder() {
        long[] times = {1L, 0x0123456789ABL, 0xFFFFFFFFFFFFL};
        for (long time : times) {
            UniqueIds ids = new UniqueIds(() -> time, constant((byte) 0x5a));
            byte[] expected = new byte[BYTES];
            ByteBuffer.wrap(expected, 0, TIME_BYTES + 2).putLong(time << 16);
            Arrays.fill(expected, TIME_BYTES, BYTES, (byte) 0x5a);

            assertEquals(Crockford.encode128(expected), ids.ulid(), "time " + time);
        }
    }
}
