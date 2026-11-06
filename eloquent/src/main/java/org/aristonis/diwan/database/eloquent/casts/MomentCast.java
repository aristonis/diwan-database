package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.TimestampFormat;

/**
 * The {@code datetime}, {@code datetime:F} and {@code timestamp} casts.
 *
 * <p>Reads as an {@link Instant}, or as a {@code Long} of epoch seconds for {@code timestamp}. A
 * stored {@code Long} or {@code Integer} is microseconds. Writes go through
 * {@link Model#fromDateTime(Instant)}, so the attribute always holds the storage form; the
 * format {@code F} only shapes serialized output. Compares by {@link Instant}. Immutable and
 * safe to share across threads.
 */
final class MomentCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

    /** ISO-8601 in UTC with microseconds, the serialized form of a plain {@code datetime}. */
    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final String name;
    private final boolean isTimestamp;
    private final DateTimeFormatter format;

    /**
     * Creates the {@code datetime} or {@code timestamp} cast.
     *
     * @param isTimestamp {@code true} for {@code timestamp}, {@code false} for {@code datetime}
     */
    MomentCast(boolean isTimestamp) {
        this.name = isTimestamp ? "timestamp" : "datetime";
        this.isTimestamp = isTimestamp;
        this.format = null;
    }

    /**
     * Creates the {@code datetime:F} cast.
     *
     * @param pattern the {@link DateTimeFormatter} pattern used for serialized output
     * @throws NullPointerException if {@code pattern} is null
     * @throws IllegalArgumentException if {@code pattern} is not a valid pattern
     */
    MomentCast(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        this.name = "datetime:" + pattern;
        this.isTimestamp = false;
        this.format = DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(ZoneOffset.UTC);
    }

    String name() {
        return name;
    }

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        Instant moment = readStored(model, key, raw, name);
        return isTimestamp ? (Object) moment.getEpochSecond() : moment;
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        Instant moment = readInput(model, key, value);
        if (model.getTimestampFormat() == TimestampFormat.INSTANT) {
            // Instant storage holds whole microseconds only; finer digits would never compare equal
            // to what the database returns.
            moment = moment.truncatedTo(ChronoUnit.MICROS);
        }
        return model.fromDateTime(moment);
    }

    @Override
    public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
        if (originalRaw == null || currentRaw == null) {
            return originalRaw == currentRaw;
        }
        return readStored(model, key, originalRaw, name).equals(readStored(model, key, currentRaw, name));
    }

    @Override
    public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
        if (value == null || isTimestamp) {
            return value;
        }
        if (!(value instanceof Instant)) {
            throw new InvalidCastException(model.getClass(), key, name,
                    new IllegalArgumentException("Not an Instant: " + value.getClass().getSimpleName()));
        }
        return (format != null ? format : ISO_MICROS).format((Instant) value);
    }

    private Instant readInput(Model model, String key, Object value) {
        if (value instanceof Long || value instanceof Integer) {
            if (!isTimestamp) {
                // A bare number could be seconds, millis or micros; refusing beats guessing.
                throw new InvalidCastException(model.getClass(), key, name,
                        new IllegalArgumentException("A number is ambiguous for a datetime"));
            }
            return Instant.ofEpochSecond(((Number) value).longValue());
        }
        return toMoment(model, key, value, name);
    }

    /**
     * Converts a value given to {@code set} into a moment: {@code Instant}, {@code OffsetDateTime},
     * {@code ZonedDateTime}, {@code LocalDateTime} (UTC), {@code LocalDate} (start of day UTC),
     * {@code java.util.Date} or text in the model's date format or ISO-8601.
     */
    static Instant toMoment(Model model, String key, Object value, String castName) {
        if (value instanceof ZonedDateTime) {
            return ((ZonedDateTime) value).toInstant();
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Date) {
            // getTime, not toInstant: java.sql.Date throws from toInstant.
            return Instant.ofEpochMilli(((Date) value).getTime());
        }
        if (value instanceof Instant || value instanceof OffsetDateTime || value instanceof LocalDateTime
                || value instanceof String) {
            return readStored(model, key, value, castName);
        }
        throw new InvalidCastException(model.getClass(), key, castName,
                new IllegalArgumentException("Not a moment: " + value.getClass().getSimpleName()));
    }

    /** Reads a stored value as a moment; a {@code Long} or {@code Integer} is microseconds. */
    static Instant readStored(Model model, String key, Object raw, String castName) {
        try {
            return CastValues.toInstant(raw, model.getDateFormat());
        } catch (RuntimeException e) {
            throw new InvalidCastException(model.getClass(), key, castName, e);
        }
    }
}
