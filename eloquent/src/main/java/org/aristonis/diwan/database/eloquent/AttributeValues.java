package org.aristonis.diwan.database.eloquent;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.values.Instants;

/** Conversions and comparisons of raw attribute values, shared by the model layers. */
final class AttributeValues {

    private static final Pattern NUMERIC = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private AttributeValues() {
    }

    /**
     * Returns whether a changed value equals its original as Laravel's dirty check sees it,
     * without casts. Never throws.
     */
    static boolean equivalent(Object current, Object original, String dateFormat) {
        if (current == original) {
            return true;
        }
        if (current == null || original == null) {
            return false;
        }
        if (current instanceof byte[] && original instanceof byte[]) {
            return Arrays.equals((byte[]) current, (byte[]) original);
        }
        if (isTemporal(current) || isTemporal(original)) {
            Instant a = tryInstant(current, dateFormat);
            Instant b = tryInstant(original, dateFormat);
            return a != null && a.equals(b);
        }
        if (isNumeric(current) && isNumeric(original)) {
            // Laravel compares numeric values as strings (strcmp): 1, 1.0 and "1" match, "1.0" does not.
            return numericText(current).equals(numericText(original));
        }
        return current.equals(original);
    }

    /** Returns whether two keys are the same: equal, or integral numbers of the same value. */
    static boolean sameKey(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        if (isIntegral(a) && isIntegral(b)) {
            return ((Number) a).longValue() == ((Number) b).longValue();
        }
        return a.equals(b);
    }

    /** Returns whether two keys match loosely, as PHP's {@code ==}: numbers and numeric text by value. */
    static boolean looseKey(Object a, Object b) {
        if (sameKey(a, b)) {
            return true;
        }
        if (a == null || b == null || !isNumeric(a) || !isNumeric(b)) {
            return false;
        }
        BigDecimal x = decimal(a);
        BigDecimal y = decimal(b);
        return x != null && y != null && x.compareTo(y) == 0;
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
    }

    private static boolean isTemporal(Object value) {
        return value instanceof Temporal;
    }

    private static boolean isNumeric(Object value) {
        return value instanceof Number || value instanceof String && NUMERIC.matcher((String) value).matches();
    }

    private static String numericText(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        BigDecimal decimal = decimal(value);
        return decimal == null ? value.toString() : decimal.stripTrailingZeros().toPlainString();
    }

    /** The exact decimal of a number or numeric text, or null for NaN and infinities. */
    private static BigDecimal decimal(Object value) {
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

    private static Instant tryInstant(Object value, String dateFormat) {
        try {
            return toInstant(value, dateFormat);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // A value that is not a moment never equals one: the attribute counts as dirty.
            return null;
        }
    }

    static Instant toInstant(Object value, String dateFormat) {
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

    private static Instant parseInstant(String text, String dateFormat) {
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
            throw new IllegalArgumentException("Not a moment in the format " + dateFormat + " or ISO-8601", e);
        }
    }

    static Long toLong(Object value) {
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

    static BigDecimal toBigDecimal(Object value) {
        BigDecimal decimal = value instanceof Number || value instanceof String ? decimal(value) : null;
        if (decimal == null) {
            throw new IllegalArgumentException("Not a decimal number: " + describe(value));
        }
        return decimal;
    }

    static Boolean toBoolean(Object value) {
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
