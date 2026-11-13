package org.aristonis.diwan.database.eloquent.casts;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The {@code binary:uuid} and {@code binary:ulid} casts: an identifier stored as exactly 16 bytes,
 * most significant byte first.
 *
 * <p>A read needs a {@code byte[]} of 16 bytes and returns a {@code UUID} or the 26-character
 * lower-case ULID text. A write takes a {@code UUID} or a strict ULID {@code String} and stores
 * the bytes {@link BinaryIds} builds. A null value stores null and a null column reads null. Any
 * other value throws {@code InvalidCastException} with a fixed reason; the value is never put in
 * an exception message. Holds no state and is safe to share across models and threads.
 *
 * @param <T> the Java type a read returns, {@code UUID} or {@code String}
 */
final class BinaryCast<T> implements Cast<T>, CastSerializer<T> {

    private static final String CAST_NAME = "binary";
    private static final int BYTES = 16;

    /** The two byte formats, each with the Java type it accepts and the name used in messages. */
    enum Format {
        /** A {@code UUID}. */
        UUID(java.util.UUID.class, "UUID"),
        /** A lower-case ULID text. */
        ULID(String.class, "String");

        private final Class<?> type;

        private final String typeName;

        Format(Class<?> type, String typeName) {
            this.type = type;
            this.typeName = typeName;
        }
    }

    private final Format format;

    /**
     * Creates the cast.
     *
     * @param format the format of the stored bytes, never {@code null}
     */
    BinaryCast(Format format) {
        this.format = Objects.requireNonNull(format, "format");
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(Model model, String key, Object raw, Map<String, Object> attributes) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof byte[]) || ((byte[]) raw).length != BYTES) {
            throw PrimitiveCasts.invalid(model, key, CAST_NAME, storedReason(raw));
        }
        byte[] bytes = (byte[]) raw;
        return (T) (format == Format.UUID ? BinaryIds.uuidOf(bytes) : BinaryIds.ulidOf(bytes));
    }

    @Override
    public Object set(Model model, String key, T value, Map<String, Object> attributes) {
        if (value == null) {
            return null;
        }
        if (!format.type.isInstance(value)) {
            throw PrimitiveCasts.invalid(model, key, CAST_NAME,
                    "expected a " + format.typeName + ", got a "
                            + value.getClass().getSimpleName());
        }
        if (format == Format.UUID) {
            return BinaryIds.uuidBytes((UUID) value);
        }
        try {
            return BinaryIds.ulidBytes((String) value);
        } catch (IllegalArgumentException e) {
            // The cause is dropped on purpose: the fixed reason is the whole message.
            throw PrimitiveCasts.invalid(model, key, CAST_NAME, BinaryIds.BAD_ULID);
        }
    }

    @Override
    public Object serialize(Model model, String key, T value, Map<String, Object> attributes) {
        return value == null ? null : value.toString();
    }

    private static String storedReason(Object raw) {
        if (raw instanceof byte[]) {
            return "stored value is not " + BYTES + " bytes long";
        }
        return "stored value is a " + raw.getClass().getSimpleName() + ", expected " + BYTES
                + " bytes";
    }
}
