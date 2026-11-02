package org.aristonis.diwan.database.eloquent.casts;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.aristonis.diwan.database.values.Instants;

/**
 * Converts raw attribute values to numbers, booleans and moments, as the built-in casts read them.
 *
 * <p>Every method is stateless and safe to call from any thread. Error messages name only the
 * value's type, never the value, because attribute values can be personal data.
 *
 * @apiNote Internal support for the built-in casts and the model layers, public only so that
 *     both packages can reach it. It is not a stable API and may change in any release.
 */
public final class CastValues {

    /**
     * Places past the target scale a value's leading digit must sit before it is treated as zero
     * without rounding. One place already puts it below half a unit; the second is a margin.
     */
    private static final long NEGLIGIBLE_MARGIN = 2;

    private CastValues() {
    }

    /**
     * Returns the digits before the point, negative when the value is below one tenth; computed in
     * long so an exponent near the int limits cannot overflow.
     *
     * @param decimal the value
     * @return precision minus scale
     */
    static long wholeDigits(BigDecimal decimal) {
        return (long) decimal.precision() - decimal.scale();
    }

    /**
     * Tells whether a value is so far below the target scale that it rounds to zero, so the
     * caller can skip {@code setScale}: on a scale such as 999999999 it would divide by a power of
     * ten with a billion digits and run for minutes.
     *
     * @param decimal the value
     * @param targetScale the scale it will be rounded to, zero or more
     * @return whether the value rounds to zero half up and down alike
     */
    static boolean roundsToZero(BigDecimal decimal, int targetScale) {
        return -wholeDigits(decimal) > targetScale + NEGLIGIBLE_MARGIN;
    }

    /**
     * Returns the exact decimal of a number or numeric text.
     *
     * <p>A {@code Double} goes through its shortest decimal text, a {@code Float} through its own
     * text, so {@code 0.1d} gives {@code 0.1}, not the binary expansion.
     *
     * @param value the number or text to read; not null
     * @return the exact decimal, or null for NaN, infinities and text that is not a number
     */
    public static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return value instanceof Float ? new BigDecimal(value.toString()) : BigDecimal.valueOf(d);
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the moment a raw value stands for.
     *
     * <p>A {@code LocalDateTime} is read as UTC. An {@code Instant}, {@code OffsetDateTime},
     * {@code Long} or {@code Integer} is read by {@code Instants.read}. A {@code String} is parsed
     * by {@link #parseInstant(String, String)}.
     *
     * @param value the raw value; not null
     * @param dateFormat the model's date format, a {@code DateTimeFormatter} pattern
     * @return the moment
     * @throws IllegalArgumentException if the value is of another type or is text that is not a
     *     moment
     * @throws java.time.DateTimeException if a {@code Long} or {@code Integer} count of
     *     microseconds lies outside the range of {@code Instant}
     */
    public static Instant toInstant(Object value, String dateFormat) {
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Instant || value instanceof OffsetDateTime || value instanceof Long
                || value instanceof Integer) {
            return Instants.read(value);
        }
        if (value instanceof String) {
            return parseInstant((String) value, dateFormat);
        }
        throw new IllegalArgumentException("Not a moment: " + value.getClass().getSimpleName());
    }

    /**
     * Parses text as a moment: first in the model's date format, then as an ISO-8601 date-time
     * with an offset, then as an ISO-8601 local date-time read as UTC.
     *
     * @param text the text to parse; not null
     * @param dateFormat the model's date format, a {@code DateTimeFormatter} pattern
     * @return the moment
     * @throws IllegalArgumentException if the text matches none of the three forms
     */
    public static Instant parseInstant(String text, String dateFormat) {
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(dateFormat, Locale.ROOT))
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            // Not in the model's format; fall through to the ISO forms.
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            // No offset; a plain ISO local date-time is read as UTC below.
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            // Not chained: the parse exception's message quotes the text, which can be personal data.
            throw new IllegalArgumentException("Not a moment in the format " + dateFormat + " or ISO-8601");
        }
    }

    /**
     * Returns a raw value as a whole number.
     *
     * @param value the number or numeric text; not null
     * @return the value as a long
     * @throws IllegalArgumentException if the value is not a whole number, is out of the long
     *     range, or is of another type
     */
    public static Long toLong(Object value) {
        if (value instanceof BigDecimal || value instanceof Double || value instanceof Float || value instanceof String) {
            BigDecimal decimal = decimal(value);
            if (decimal == null) {
                throw new IllegalArgumentException("Not a whole number: " + describe(value));
            }
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Not a whole number in the long range: " + describe(value), e);
            }
        }
        if (value instanceof BigInteger) {
            // bitLength, not longValueExact: the latter needs Android API 31.
            if (((BigInteger) value).bitLength() > Long.SIZE - 1) {
                throw new IllegalArgumentException("Not in the long range: " + describe(value));
            }
            return ((BigInteger) value).longValue();
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("Not a whole number: " + describe(value));
    }

    /**
     * Returns a raw value as an exact decimal.
     *
     * @param value the number or numeric text; not null
     * @return the exact decimal
     * @throws IllegalArgumentException if the value is NaN, infinite, text that is not a
     *     number, or of another type
     */
    public static BigDecimal toBigDecimal(Object value) {
        BigDecimal decimal = value instanceof Number || value instanceof String ? decimal(value) : null;
        if (decimal == null) {
            throw new IllegalArgumentException("Not a decimal number: " + describe(value));
        }
        return decimal;
    }

    /**
     * Returns a raw value as a boolean, as PHP reads database flags.
     *
     * <p>A number is true when it is not zero. Text {@code "1"} and {@code "true"} (any case) are
     * true; {@code "0"}, {@code "false"} (any case) and the empty text are false.
     *
     * @param value the raw value; not null
     * @return the boolean
     * @throws IllegalArgumentException if the value is text of another form, or a number that is
     *     NaN or infinite
     */
    public static Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return toBigDecimal(value).signum() != 0;
        }
        String text = value.toString();
        if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text) || text.isEmpty()) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Not a boolean: " + describe(value));
    }

    // Only the type: attribute values can be personal data and must not reach logs.
    private static String describe(Object value) {
        return "a " + value.getClass().getSimpleName();
    }
}
