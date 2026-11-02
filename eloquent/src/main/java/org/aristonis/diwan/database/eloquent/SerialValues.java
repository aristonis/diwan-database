package org.aristonis.diwan.database.eloquent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.BackedValue;

/**
 * Turns uncast attribute values into the plain forms that {@code toMap()} and {@code toJson()}
 * emit.
 *
 * <p>Stateless and safe to call from any thread. The input is never changed; maps and lists are
 * copied.
 */
final class SerialValues {

    /** The datetime form: UTC with microseconds, as Laravel's {@code serializeDate}. */
    static final DateTimeFormatter MOMENT_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private SerialValues() {
    }

    /**
     * Returns the serializable form of a value.
     *
     * <p>Moments ({@code Instant}, {@code OffsetDateTime}, {@code ZonedDateTime},
     * {@code java.util.Date} and {@code java.sql.Timestamp}) become UTC text with microseconds; a
     * {@code LocalDateTime} is read as UTC. A {@code LocalDate} or {@code java.sql.Date} becomes
     * ISO {@code uuuu-MM-dd}. An enum becomes {@link BackedValue#value()} when it implements
     * {@link BackedValue}, else its name. A map becomes a new {@code LinkedHashMap} with
     * {@code String.valueOf} keys and converted values, in the source order; any other collection
     * becomes a new {@code ArrayList} of converted elements. Everything else, including
     * {@code byte[]} and null, is returned unchanged.
     *
     * @param value the value to convert; may be null
     * @return the converted value, a new mutable map or list for maps and collections
     */
    static Object toSerializable(Object value) {
        if (value instanceof Map) {
            return copyMap((Map<?, ?>) value);
        }
        if (value instanceof Collection) {
            return copyList((Collection<?>) value);
        }
        if (value instanceof Enum) {
            return value instanceof BackedValue ? ((BackedValue) value).value() : ((Enum<?>) value).name();
        }
        return temporal(value);
    }

    private static Object temporal(Object value) {
        if (value instanceof Instant) {
            return MOMENT_FORMAT.format((Instant) value);
        }
        if (value instanceof OffsetDateTime) {
            return MOMENT_FORMAT.format(((OffsetDateTime) value).toInstant());
        }
        if (value instanceof ZonedDateTime) {
            return MOMENT_FORMAT.format(((ZonedDateTime) value).toInstant());
        }
        if (value instanceof LocalDateTime) {
            return MOMENT_FORMAT.format(((LocalDateTime) value).toInstant(ZoneOffset.UTC));
        }
        if (value instanceof LocalDate) {
            return value.toString();
        }
        // java.sql.Date must come before java.util.Date: its toInstant() throws, and it carries a
        // calendar day, not a moment. Its toString() is already yyyy-mm-dd, and toLocalDate() is
        // missing on Android.
        if (value instanceof java.sql.Date) {
            return value.toString();
        }
        if (value instanceof Date) {
            // Timestamp overrides toInstant() and keeps its nanoseconds, so micros survive.
            return MOMENT_FORMAT.format(((Date) value).toInstant());
        }
        return value;
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), toSerializable(entry.getValue()));
        }
        return copy;
    }

    private static List<Object> copyList(Collection<?> source) {
        List<Object> copy = new ArrayList<>(source.size());
        for (Object element : source) {
            copy.add(toSerializable(element));
        }
        return copy;
    }
}
