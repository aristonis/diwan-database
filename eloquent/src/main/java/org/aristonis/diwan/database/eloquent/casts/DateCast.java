package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The {@code date} and {@code date:F} casts.
 *
 * <p>Reads as a {@link LocalDate}; a moment is taken at its UTC date. Writes {@code yyyy-MM-dd}
 * text whatever the model's timestamp format, and compares by {@link LocalDate}, so a change
 * in time of day alone is clean. The format {@code F} only shapes serialized output.
 * Immutable and safe to share across threads.
 */
final class DateCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

    private final String name;
    private final DateTimeFormatter format;

    /** Creates the {@code date} cast. */
    DateCast() {
        this.name = "date";
        this.format = DateTimeFormatter.ISO_LOCAL_DATE;
    }

    /**
     * Creates the {@code date:F} cast.
     *
     * @param pattern the {@link DateTimeFormatter} pattern used for serialized output
     * @throws NullPointerException if {@code pattern} is null
     * @throws IllegalArgumentException if {@code pattern} is not a valid pattern
     */
    DateCast(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        this.name = "date:" + pattern;
        this.format = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
    }

    String name() {
        return name;
    }

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        return toDate(model, key, raw, true);
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        return DateTimeFormatter.ISO_LOCAL_DATE.format(toDate(model, key, value, false));
    }

    @Override
    public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
        if (originalRaw == null || currentRaw == null) {
            return originalRaw == currentRaw;
        }
        return toDate(model, key, originalRaw, true).equals(toDate(model, key, currentRaw, true));
    }

    @Override
    public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof LocalDate)) {
            throw new InvalidCastException(model.getClass(), key, name,
                    new IllegalArgumentException("Not a LocalDate: " + value.getClass().getSimpleName()));
        }
        return format.format((LocalDate) value);
    }

    private LocalDate toDate(Model model, String key, Object value, boolean isStored) {
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof String) {
            try {
                return LocalDate.parse((String) value);
            } catch (DateTimeParseException e) {
                // Not a bare date; read it as a moment below.
            }
        }
        if (value instanceof Long || value instanceof Integer) {
            if (!isStored) {
                // A bare number given to set() has no agreed unit; refusing beats guessing.
                throw new InvalidCastException(model.getClass(), key, name,
                        new IllegalArgumentException("A number is ambiguous for a date"));
            }
            return MomentCast.readStored(model, key, value, name).atOffset(ZoneOffset.UTC).toLocalDate();
        }
        return MomentCast.toMoment(model, key, value, name).atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
