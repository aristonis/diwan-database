package org.aristonis.diwan.database.eloquent.casts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The built-in {@code decimal:N} cast: reads a {@code BigDecimal} of scale N and stores its plain
 * text, both rounded half up.
 *
 * <p>Values are built from their text form, never from a binary double, so {@code 1.005d} rounds
 * to {@code 1.01}. Two raws compare equal when they round to the same value, as in
 * Laravel. Null is skipped; bad stored data throws {@link InvalidCastException}.
 */
final class DecimalCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

    /** Largest accepted scale N (ASSUMPTION-5). */
    static final int MAX_SCALE = 30;

    /** Digits before the point PostgreSQL numeric allows; caps work done on hostile exponents. */
    private static final int MAX_WHOLE_DIGITS = 131_072;

    private final int scale;
    private final String name;

    /**
     * Creates the cast.
     *
     * @param scale the number of digits after the point, 0 to {@link #MAX_SCALE}
     * @throws IllegalArgumentException if the scale is out of range
     */
    DecimalCast(int scale) {
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("Decimal scale must be 0 to " + MAX_SCALE + ", got " + scale);
        }
        this.scale = scale;
        this.name = "decimal:" + scale;
    }

    int scale() {
        return scale;
    }

    String name() {
        return name;
    }

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        return raw == null ? null : round(model, key, raw);
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        return value == null ? null : round(model, key, value).toPlainString();
    }

    @Override
    public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
        return value == null ? null : round(model, key, value).toPlainString();
    }

    @Override
    public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
        if (originalRaw == null || currentRaw == null) {
            return originalRaw == currentRaw;
        }
        try {
            return round(model, key, originalRaw).compareTo(round(model, key, currentRaw)) == 0;
        } catch (InvalidCastException e) {
            return false;
        }
    }

    private BigDecimal round(Model model, String key, Object raw) {
        try {
            if (!(raw instanceof Number) && !(raw instanceof String)) {
                throw new IllegalArgumentException("Not a number: a " + raw.getClass().getSimpleName());
            }
            // CastValues reads a Double through its shortest text, never new BigDecimal(double).
            BigDecimal decimal = CastValues.toBigDecimal(raw);
            if (CastValues.wholeDigits(decimal) > MAX_WHOLE_DIGITS) {
                throw new ArithmeticException("Whole part too long");
            }
            if (CastValues.roundsToZero(decimal, scale)) {
                return BigDecimal.ZERO.setScale(scale);
            }
            return decimal.setScale(scale, RoundingMode.HALF_UP);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw PrimitiveCasts.invalid(model, key, name, e);
        }
    }
}
