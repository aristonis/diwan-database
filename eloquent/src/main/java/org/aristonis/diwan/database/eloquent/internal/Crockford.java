package org.aristonis.diwan.database.eloquent.internal;

import java.util.Arrays;
import org.aristonis.diwan.database.Internal;

/**
 * The Crockford base-32 text of a 128-bit value, as ULIDs write it: one alphabet, one grouping.
 *
 * <p>A value of 16 bytes, most significant byte first, becomes 26 lower-case characters. Twenty-six
 * characters of 5 bits hold 130 bits, so the first character is 0 to 7 and carries the two
 * leading zero bits. Decoding accepts either letter case and reads no locale: the value table is
 * filled with ASCII arithmetic.
 *
 * <p>Internal: not API. The id generator and the binary casts share it.
 */
@Internal
public final class Crockford {

    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final int[] VALUES = valueTable();
    private static final int ASCII_LIMIT = 128;
    private static final int BYTES = 16;
    private static final int CHARS = 26;
    private static final int BITS_PER_CHAR = 5;
    private static final int BITS_PER_BYTE = 8;
    private static final int CHAR_MASK = 0x1F;
    private static final int BYTE_MASK = 0xFF;
    private static final int MAX_FIRST_CHAR = 7;

    private Crockford() {
    }

    /**
     * Returns the 26 lower-case characters of 16 bytes, most significant byte first.
     *
     * @param bytes the 128-bit value, exactly 16 bytes, never {@code null}
     * @return the 26-character text, never {@code null}
     * @throws IllegalArgumentException if {@code bytes} is not 16 bytes long
     */
    public static String encode128(byte[] bytes) {
        if (bytes.length != BYTES) {
            throw new IllegalArgumentException("a 128-bit value has 16 bytes");
        }
        char[] out = new char[CHARS];
        int pos = CHARS - 1;
        int acc = 0;
        int bits = 0;
        for (int i = BYTES - 1; i >= 0; i--) {
            acc |= (bytes[i] & BYTE_MASK) << bits;
            bits += BITS_PER_BYTE;
            while (bits >= BITS_PER_CHAR) {
                out[pos--] = ALPHABET[acc & CHAR_MASK];
                acc >>>= BITS_PER_CHAR;
                bits -= BITS_PER_CHAR;
            }
        }
        // The last 3 bits make the first character, always 0 to 7.
        out[pos] = ALPHABET[acc & CHAR_MASK];
        return new String(out);
    }

    /**
     * Returns the 16 bytes of a 26-character text in either letter case, or {@code null} when the
     * text is not one.
     *
     * <p>The text is refused when it is not 26 characters, holds a character outside the alphabet
     * (I, L, O and U included) or starts with a character above 7. Nothing is trimmed.
     *
     * @param text the text to decode, never {@code null}
     * @return the 16 bytes, most significant byte first, or {@code null} for any other text
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static byte[] decode128(String text) {
        if (text.length() != CHARS || valueOf(text.charAt(0)) > MAX_FIRST_CHAR) {
            return null;
        }
        byte[] out = new byte[BYTES];
        int idx = BYTES - 1;
        int acc = 0;
        int bits = 0;
        for (int i = CHARS - 1; i >= 0; i--) {
            int value = valueOf(text.charAt(i));
            if (value < 0) {
                return null;
            }
            acc |= value << bits;
            bits += BITS_PER_CHAR;
            while (bits >= BITS_PER_BYTE) {
                out[idx--] = (byte) acc;
                acc >>>= BITS_PER_BYTE;
                bits -= BITS_PER_BYTE;
            }
        }
        // 130 bits were read and 16 bytes written, so 2 zero bits are left over.
        return out;
    }

    private static int valueOf(char c) {
        return c < ASCII_LIMIT ? VALUES[c] : -1;
    }

    private static int[] valueTable() {
        int[] table = new int[ASCII_LIMIT];
        Arrays.fill(table, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            char c = ALPHABET[i];
            table[c] = i;
            if (c >= 'a') {
                table[c - 'a' + 'A'] = i;
            }
        }
        return table;
    }
}
