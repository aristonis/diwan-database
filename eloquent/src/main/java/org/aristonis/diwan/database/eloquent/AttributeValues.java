package org.aristonis.diwan.database.eloquent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.eloquent.casts.CastValues;

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
        BigDecimal x = CastValues.decimal(a);
        BigDecimal y = CastValues.decimal(b);
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
        BigDecimal decimal = CastValues.decimal(value);
        return decimal == null ? value.toString() : decimal.stripTrailingZeros().toPlainString();
    }

    private static Instant tryInstant(Object value, String dateFormat) {
        try {
            return CastValues.toInstant(value, dateFormat);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // A value that is not a moment never equals one: the attribute counts as dirty.
            return null;
        }
    }
}
