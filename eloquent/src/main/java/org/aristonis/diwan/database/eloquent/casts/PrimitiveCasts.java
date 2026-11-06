package org.aristonis.diwan.database.eloquent.casts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * Holds the built-in {@code int}, {@code float}, {@code string} and {@code bool} casts.
 *
 * <p>Each cast is stateless apart from its name, so one instance serves every model. Built-ins
 * skip null: a null raw reads as null and a null value stores as null. Bad stored data throws
 * {@link InvalidCastException}; comparators never throw and treat a failed conversion as a
 * change.
 */
final class PrimitiveCasts {

    /** Digits a long can hold; a whole part longer than this cannot fit. */
    private static final int LONG_DIGITS = 19;

    /** Units in the last place two floats may differ by and still compare equal. */
    private static final int FLOAT_ULP_TOLERANCE = 4;

    /** Text PHP's string cast gives for true. */
    private static final String PHP_TRUE_TEXT = "1";

    /** Text PHP's string cast gives for false. */
    private static final String PHP_FALSE_TEXT = "";

    /** Magnitude below which a whole float prints as an integer under PHP's string cast. */
    private static final double PHP_WHOLE_FLOAT_LIMIT = 1e15;

    /** Text PHP's string cast gives for negative zero. */
    private static final String PHP_NEGATIVE_ZERO_TEXT = "-0";

    private PrimitiveCasts() {
    }

    /**
     * Builds the exception for a value the cast cannot convert.
     *
     * @param model the owning model, or null when the cast is used on its own
     * @param key the attribute key
     * @param name the cast name
     * @param cause the conversion failure; only its type enters the message
     * @return the exception to throw
     */
    static InvalidCastException invalid(Model model, String key, String name, Throwable cause) {
        return new InvalidCastException(model == null ? null : model.getClass(), key, name, cause);
    }

    /** Reads whole numbers as {@code Long}, truncating a fraction as Laravel does. */
    static final class IntCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

        private final String name;

        IntCast(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        String name() {
            return name;
        }

        @Override
        public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return raw == null ? null : convert(model, key, raw);
        }

        @Override
        public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
            return value == null ? null : convert(model, key, value);
        }

        @Override
        public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
            return value;
        }

        @Override
        public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
            if (originalRaw == null || currentRaw == null) {
                return originalRaw == currentRaw;
            }
            try {
                return convert(model, key, originalRaw).equals(convert(model, key, currentRaw));
            } catch (InvalidCastException e) {
                return false;
            }
        }

        private Long convert(Model model, String key, Object raw) {
            try {
                return truncate(raw);
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw invalid(model, key, name, e);
            }
        }

        private static Long truncate(Object raw) {
            if (raw instanceof Boolean) {
                return ((Boolean) raw) ? 1L : 0L;
            }
            if (!(raw instanceof Number) && !(raw instanceof String)) {
                throw new IllegalArgumentException("Not a number: a " + raw.getClass().getSimpleName());
            }
            BigDecimal decimal = CastValues.toBigDecimal(raw);
            // Checked before setScale, so text such as "1e999999999" cannot expand into a huge number.
            if (CastValues.wholeDigits(decimal) > LONG_DIGITS) {
                throw new ArithmeticException("Whole part out of the long range");
            }
            if (CastValues.roundsToZero(decimal, 0)) {
                return 0L;
            }
            return decimal.setScale(0, RoundingMode.DOWN).longValueExact();
        }
    }

    /**
     * Reads numbers as {@code Double}; compares within four units in the last place.
     */
    static final class FloatCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

        private final String name;

        FloatCast(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        String name() {
            return name;
        }

        @Override
        public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return raw == null ? null : convert(model, key, raw);
        }

        @Override
        public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
            return value == null ? null : convert(model, key, value);
        }

        @Override
        public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
            return value;
        }

        @Override
        public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
            if (originalRaw == null || currentRaw == null) {
                return originalRaw == currentRaw;
            }
            try {
                return equivalent(convert(model, key, originalRaw), convert(model, key, currentRaw));
            } catch (InvalidCastException e) {
                return false;
            }
        }

        /**
         * Tells whether two doubles are the same value up to rounding noise.
         *
         * @param a the first value
         * @param b the second value
         * @return true when both are NaN, they are equal, or neither is infinite and they differ
         *     by at most four units in the last place of the larger magnitude
         */
        static boolean equivalent(double a, double b) {
            if (Double.isNaN(a) && Double.isNaN(b)) {
                return true;
            }
            if (a == b) {
                return true;
            }
            // ulp(Infinity) is Infinity, so without this guard every finite value would match.
            if (Double.isInfinite(a) || Double.isInfinite(b)) {
                return false;
            }
            double larger = Math.max(Math.abs(a), Math.abs(b));
            return Math.abs(a - b) <= FLOAT_ULP_TOLERANCE * Math.ulp(larger);
        }

        private Double convert(Model model, String key, Object raw) {
            try {
                return toDouble(raw);
            } catch (IllegalArgumentException e) {
                throw invalid(model, key, name, e);
            }
        }

        private static Double toDouble(Object raw) {
            // As IntCast reads it, so a flag column cast to float holds 1.0 or 0.0.
            if (raw instanceof Boolean) {
                return ((Boolean) raw) ? 1.0d : 0.0d;
            }
            if (raw instanceof Number) {
                return ((Number) raw).doubleValue();
            }
            if (!(raw instanceof String)) {
                throw new IllegalArgumentException("Not a number: a " + raw.getClass().getSimpleName());
            }
            String text = ((String) raw).trim();
            switch (text) {
                case "Infinity":
                    return Double.POSITIVE_INFINITY;
                case "-Infinity":
                    return Double.NEGATIVE_INFINITY;
                case "NaN":
                    return Double.NaN;
                default:
                    // Through BigDecimal, so Java-only forms such as "0x1p3" or "1d" are rejected.
                    return CastValues.toBigDecimal(text).doubleValue();
            }
        }
    }

    /**
     * Reads scalars as {@code String}, following PHP's {@code (string)} cast where it can.
     *
     * <p>A {@code Boolean} becomes {@code "1"} for true and {@code ""} for false. A whole
     * {@code Double} or {@code Float} that is finite and below 1e15 in magnitude becomes its
     * integer text, so {@code 1.0} reads as {@code "1"}, {@code -2.0f} as {@code "-2"} and negative
     * zero as {@code "-0"}. A decimal reads as its plain text. Other floating values use Java's
     * {@code Double.toString}, which differs from PHP for magnitudes of 1e7 or more and for
     * magnitudes below 1e-3, where Java switches to exponent form ({@code "1.0E-4"}), and for NaN
     * and infinities, which read as {@code "NaN"} and {@code "Infinity"} where PHP gives
     * {@code "NAN"} and {@code "INF"}.
     */
    static final class StringCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

        private final String name;

        StringCast(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        String name() {
            return name;
        }

        @Override
        public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return raw == null ? null : convert(model, key, raw);
        }

        @Override
        public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
            return value == null ? null : convert(model, key, value);
        }

        @Override
        public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
            return value;
        }

        @Override
        public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
            if (originalRaw == null || currentRaw == null) {
                return originalRaw == currentRaw;
            }
            try {
                return convert(model, key, originalRaw).equals(convert(model, key, currentRaw));
            } catch (InvalidCastException e) {
                return false;
            }
        }

        /**
         * Converts a scalar to its text; the class Javadoc says where this matches PHP.
         *
         * @param model the owning model, or null when the cast is used on its own
         * @param key the attribute key
         * @param raw the value to convert
         * @return the text form of the value
         * @throws InvalidCastException when the value is not a scalar
         */
        private String convert(Model model, String key, Object raw) {
            if (raw instanceof String) {
                return (String) raw;
            }
            if (raw instanceof Boolean) {
                return ((Boolean) raw) ? PHP_TRUE_TEXT : PHP_FALSE_TEXT;
            }
            if (raw instanceof BigDecimal) {
                return ((BigDecimal) raw).toPlainString();
            }
            if ((raw instanceof Double || raw instanceof Float) && isPhpWholeFloat((Number) raw)) {
                return wholeFloatText((Number) raw);
            }
            if (raw instanceof Number || raw instanceof Character) {
                return raw.toString();
            }
            throw invalid(model, key, name,
                    new IllegalArgumentException("Not a scalar: a " + raw.getClass().getSimpleName()));
        }

        /**
         * Tells whether a floating value prints as a plain integer under PHP's string cast.
         *
         * @param value the boxed {@code Double} or {@code Float}
         * @return true when the value is finite, whole and below 1e15 in magnitude
         */
        private static boolean isPhpWholeFloat(Number value) {
            double d = value.doubleValue();
            return Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < PHP_WHOLE_FLOAT_LIMIT;
        }

        /**
         * Prints a whole floating value as an integer, keeping the sign of negative zero.
         *
         * @param value the boxed {@code Double} or {@code Float}, already known to be whole
         * @return the integer text, or {@code "-0"} for negative zero
         */
        private static String wholeFloatText(Number value) {
            double d = value.doubleValue();
            if (Double.compare(d, -0.0d) == 0) {
                return PHP_NEGATIVE_ZERO_TEXT;
            }
            return Long.toString(value.longValue());
        }
    }

    /** Reads database flags as {@code Boolean}; text other than 1, 0, true, false or empty throws. */
    static final class BoolCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

        private final String name;

        BoolCast(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        String name() {
            return name;
        }

        @Override
        public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
            return raw == null ? null : convert(model, key, raw);
        }

        @Override
        public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
            return value == null ? null : convert(model, key, value);
        }

        @Override
        public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
            return value;
        }

        @Override
        public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
            if (originalRaw == null || currentRaw == null) {
                return originalRaw == currentRaw;
            }
            try {
                return convert(model, key, originalRaw).equals(convert(model, key, currentRaw));
            } catch (InvalidCastException e) {
                return false;
            }
        }

        private Boolean convert(Model model, String key, Object raw) {
            try {
                return CastValues.toBoolean(raw);
            } catch (IllegalArgumentException e) {
                throw invalid(model, key, name, e);
            }
        }
    }
}
