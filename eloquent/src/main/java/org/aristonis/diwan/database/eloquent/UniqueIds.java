package org.aristonis.diwan.database.eloquent;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Generates the unique string keys of {@link UniqueIdKind#UUID} and {@link UniqueIdKind#ULID}
 * models: version 7 UUIDs and ULIDs, both in lower case as Laravel writes them.
 *
 * <p>Both start with the milliseconds since the epoch, so ids sort roughly by creation time. Ids
 * made in the same millisecond are ordered by their random part, not by creation. Safe to share
 * across threads when the random source is, as {@link SecureRandom} is.
 */
public final class UniqueIds {

    private static final UniqueIds STANDARD = new UniqueIds(System::currentTimeMillis, new SecureRandom());
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char[] CROCKFORD = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final int UUID_BYTES = 16;
    private static final int ULID_RANDOM_BYTES = 10;
    private static final int TIME_BYTES = 6;
    private static final int BITS_PER_BYTE = 8;
    private static final int BYTE_MASK = 0xFF;
    private static final int BASE32_BITS = 5;
    private static final int BASE32_MASK = 0x1F;
    private static final int ULID_TIME_CHARS = 10;
    private static final int VERSION_BYTE = 6;
    private static final int VARIANT_BYTE = 8;

    private final LongSupplier epochMillis;
    private final Random random;

    /**
     * Creates a generator over the given time source and random source.
     *
     * @param epochMillis the source of milliseconds since the epoch, never {@code null}
     * @param random the random source, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public UniqueIds(LongSupplier epochMillis, Random random) {
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Returns the shared generator over the system clock and a {@link SecureRandom}.
     *
     * @return the generator, never {@code null}
     */
    public static UniqueIds standard() {
        return STANDARD;
    }

    /**
     * Returns a new version 7 UUID in lower case, such as
     * {@code 0190b3c4-5e6f-7a1b-9c2d-3e4f5a6b7c8d}.
     *
     * @return the UUID text, never {@code null}
     */
    public String uuid7() {
        byte[] bytes = new byte[UUID_BYTES];
        random.nextBytes(bytes);
        writeTime(bytes, epochMillis.getAsLong());
        bytes[VERSION_BYTE] = (byte) ((bytes[VERSION_BYTE] & 0x0F) | 0x70);
        bytes[VARIANT_BYTE] = (byte) ((bytes[VARIANT_BYTE] & 0x3F) | 0x80);
        StringBuilder out = new StringBuilder(36);
        for (int i = 0; i < UUID_BYTES; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                out.append('-');
            }
            out.append(HEX[(bytes[i] >> 4) & 0x0F]).append(HEX[bytes[i] & 0x0F]);
        }
        return out.toString();
    }

    /**
     * Returns a new ULID in lower case: 26 Crockford base-32 characters, ten for the time and
     * sixteen for 80 random bits.
     *
     * @return the ULID text, never {@code null}
     */
    public String ulid() {
        long time = epochMillis.getAsLong();
        byte[] randomPart = new byte[ULID_RANDOM_BYTES];
        random.nextBytes(randomPart);
        StringBuilder out = new StringBuilder(26);
        for (int i = ULID_TIME_CHARS - 1; i >= 0; i--) {
            out.append(CROCKFORD[(int) ((time >>> (i * BASE32_BITS)) & BASE32_MASK)]);
        }
        // 80 random bits are sixteen 5-bit characters; read them as two 40-bit halves.
        for (int half = 0; half < 2; half++) {
            long bits = 0;
            for (int i = 0; i < BASE32_BITS; i++) {
                bits = (bits << BITS_PER_BYTE) | (randomPart[half * BASE32_BITS + i] & BYTE_MASK);
            }
            for (int i = BITS_PER_BYTE - 1; i >= 0; i--) {
                out.append(CROCKFORD[(int) ((bits >>> (i * BASE32_BITS)) & BASE32_MASK)]);
            }
        }
        return out.toString();
    }

    private static void writeTime(byte[] bytes, long millis) {
        for (int i = 0; i < TIME_BYTES; i++) {
            bytes[i] = (byte) (millis >>> (BITS_PER_BYTE * (TIME_BYTES - 1 - i)));
        }
    }
}
