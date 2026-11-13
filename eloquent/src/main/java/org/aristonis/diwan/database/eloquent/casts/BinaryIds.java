package org.aristonis.diwan.database.eloquent.casts;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.aristonis.diwan.database.eloquent.internal.Crockford;

/**
 * Builds the 16 bytes a {@code binary} cast stores, for use in a {@code where} clause.
 *
 * <p>A {@code binary:uuid} or {@code binary:ulid} attribute lives in a {@code binary()} column as
 * 16 bytes, most significant byte first. The query builder binds a {@code byte[]} but not a
 * {@link UUID}, so search such a column with these bytes:
 * {@code where("id", BinaryIds.uuidBytes(uuid))}. The bytes are the ones the cast stores, with the
 * same ULID rules. Holds no state and is safe to use from any thread.
 */
public final class BinaryIds {

    /** The fixed sentence for a bad ULID text; never the text itself. */
    static final String BAD_ULID = "ULID text must be 26 characters of 0-9 and A-Z without I, L, O"
            + " and U, starting with 0-7";

    private static final int BYTES = 16;
    private static final String NULL_UUID = "uuid must not be null";
    private static final String NULL_ULID = "ulid must not be null";

    private BinaryIds() {
    }

    /**
     * Returns the 16 bytes of a UUID, most significant bits first.
     *
     * @param uuid the UUID, never {@code null}
     * @return a new 16-byte array, never {@code null}
     * @throws IllegalArgumentException if {@code uuid} is {@code null}
     */
    public static byte[] uuidBytes(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException(NULL_UUID);
        }
        return ByteBuffer.allocate(BYTES)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /**
     * Returns the 16 bytes of a ULID text, in any letter case.
     *
     * <p>The text must be exactly 26 characters: the digits 0 to 9 and the letters A to Z without
     * I, L, O and U. Its first character must be 0 to 7, since 26 characters of 5 bits hold 130
     * bits and the first two are zero padding. Whitespace is not trimmed.
     *
     * @param ulid the ULID text, never {@code null}
     * @return a new 16-byte array, never {@code null}
     * @throws IllegalArgumentException if {@code ulid} is {@code null} or not a valid ULID; the
     *     message never holds the text
     */
    public static byte[] ulidBytes(String ulid) {
        if (ulid == null) {
            throw new IllegalArgumentException(NULL_ULID);
        }
        byte[] bytes = Crockford.decode128(ulid);
        if (bytes == null) {
            throw new IllegalArgumentException(BAD_ULID);
        }
        return bytes;
    }

    /** Returns the UUID of 16 stored bytes. */
    static UUID uuidOf(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** Returns the 26 lower-case ULID characters of 16 stored bytes. */
    static String ulidOf(byte[] bytes) {
        return Crockford.encode128(bytes);
    }
}
