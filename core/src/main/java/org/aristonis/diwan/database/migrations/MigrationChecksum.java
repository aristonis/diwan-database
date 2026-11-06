package org.aristonis.diwan.database.migrations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Objects;

/**
 * Computes the checksum a {@link Migration} can declare through {@link Migration#checksum()}.
 *
 * <p>A migration that wants protection against edits after it ran returns a hash of a canonical
 * description of itself, for example its SQL text or its column list. The text is normalized first,
 * so a file saved with Windows line endings or with composed and decomposed accents hashes the same
 * as the original.
 *
 * <p>The class has only static members and cannot be instantiated.
 */
public final class MigrationChecksum {

    /** Length of the hex text {@link #of(String)} returns. */
    public static final int LENGTH = 64;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int LOW_NIBBLE = 0x0f;
    private static final int HIGH_SHIFT = 4;
    private static final int BYTE_MASK = 0xff;

    private MigrationChecksum() {
    }

    /**
     * Computes the SHA-256 checksum of a text after normalizing it.
     *
     * <p>Every {@code CRLF} and lone {@code CR} becomes {@code LF}, then the text is put in Unicode
     * normalization form C and encoded as UTF-8.
     *
     * @param text the text to hash, never {@code null}
     * @return the checksum as {@value #LENGTH} lowercase hexadecimal characters, never {@code null}
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String of(String text) {
        Objects.requireNonNull(text, "text");
        String lines = text.replace("\r\n", "\n").replace('\r', '\n');
        String normalized = Normalizer.normalize(lines, Normalizer.Form.NFC);
        byte[] digest = sha256().digest(normalized.getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & BYTE_MASK;
            hex[2 * i] = HEX[value >>> HIGH_SHIFT];
            hex[2 * i + 1] = HEX[value & LOW_NIBBLE];
        }
        return new String(hex);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java runtime", e);
        }
    }
}
