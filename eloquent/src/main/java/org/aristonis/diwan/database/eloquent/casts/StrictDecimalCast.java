package org.aristonis.diwan.database.eloquent.casts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;

/**
 * The strict {@code decimal:P,S[,MODE]} cast: reads a {@code BigDecimal} of scale S and refuses a
 * value that does not fit P total digits.
 *
 * <p>Extra decimals are rounded with the mode; the default {@code UNNECESSARY} refuses them. Integer
 * digits beyond P minus S are refused whatever the mode, before rounding so a hostile exponent is
 * never expanded, and again after it to catch a carry. A refusal throws
 * {@link InvalidCastException} with a fixed reason and never the value.
 *
 * <p>When writing, a model whose connection keeps only some digits exactly, as SQLite does, also
 * refuses a fitted value with more significant digits than that.
 */
final class StrictDecimalCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

    /** Largest accepted precision, the PostgreSQL numeric maximum. */
    static final int MAX_PRECISION = 1000;

    private final int precision;
    private final int scale;
    private final RoundingMode mode;
    private final String name;
    private final boolean isText;

    /**
     * Creates the cast for a numeric column.
     *
     * @param precision the total number of digits, 1 to {@link #MAX_PRECISION}
     * @param scale the digits after the point, 0 to the precision and at most {@link DecimalCast#MAX_SCALE}
     * @param mode how extra decimals are rounded
     * @throws IllegalArgumentException if a number is out of range or {@code mode} is null
     */
    StrictDecimalCast(int precision, int scale, RoundingMode mode) {
        this(precision, scale, mode, false);
    }

    /**
     * Creates the cast.
     *
     * @param precision the total number of digits, 1 to {@link #MAX_PRECISION}
     * @param scale the digits after the point, 0 to the precision and at most {@link DecimalCast#MAX_SCALE}
     * @param mode how extra decimals are rounded
     * @param isText whether the column is text, which keeps every digit, so no database digit limit applies
     * @throws IllegalArgumentException if a number is out of range or {@code mode} is null
     */
    StrictDecimalCast(int precision, int scale, RoundingMode mode, boolean isText) {
        if (precision < 1 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("Decimal precision must be 1 to " + MAX_PRECISION + ", got " + precision);
        }
        if (scale < 0 || scale > precision || scale > DecimalCast.MAX_SCALE) {
            throw new IllegalArgumentException("Decimal scale must be 0 to " + Math.min(precision, DecimalCast.MAX_SCALE)
                    + ", got " + scale);
        }
        if (mode == null) {
            throw new IllegalArgumentException("Decimal rounding mode must not be null");
        }
        this.precision = precision;
        this.scale = scale;
        this.mode = mode;
        this.isText = isText;
        String prefix = isText ? "decimalText:" : "decimal:";
        this.name = mode == RoundingMode.UNNECESSARY
                ? prefix + precision + "," + scale
                : prefix + precision + "," + scale + "," + mode.name();
    }

    String name() {
        return name;
    }

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        return raw == null ? null : fit(model, key, raw);
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        if (value == null) {
            return null;
        }
        BigDecimal fitted = fit(model, key, value);
        guardExactDigits(model, key, fitted);
        return fitted.toPlainString();
    }

    @Override
    public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
        return value == null ? null : fit(model, key, value).toPlainString();
    }

    @Override
    public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
        if (originalRaw == null || currentRaw == null) {
            return originalRaw == currentRaw;
        }
        try {
            return fit(model, key, originalRaw).compareTo(fit(model, key, currentRaw)) == 0;
        } catch (InvalidCastException e) {
            return false;
        }
    }

    private BigDecimal fit(Model model, String key, Object raw) {
        BigDecimal decimal;
        try {
            decimal = CastValues.toBigDecimal(Objects.requireNonNull(raw, "raw"));
        } catch (IllegalArgumentException e) {
            throw PrimitiveCasts.invalid(model, key, name, e);
        }
        long wholeLimit = (long) precision - scale;
        String tooWide = "more than " + wholeLimit + " integer digits";
        // Before setScale, so an exponent such as 1E999999999 is never expanded.
        if (wholeDigits(decimal) > wholeLimit) {
            throw PrimitiveCasts.invalid(model, key, name, tooWide);
        }
        if (decimal.signum() != 0 && CastValues.roundsToZero(decimal, scale)) {
            // Far below the scale: a stand-in of the same sign rounds the same way without a huge division.
            decimal = BigDecimal.valueOf(decimal.signum(), scale + 1);
        }
        BigDecimal rounded;
        try {
            rounded = decimal.setScale(scale, mode);
        } catch (ArithmeticException e) {
            throw PrimitiveCasts.invalid(model, key, name, "more decimals than scale " + scale);
        }
        if (wholeDigits(rounded) > wholeLimit) {
            throw PrimitiveCasts.invalid(model, key, name, tooWide);
        }
        return rounded;
    }

    // A database that keeps only some digits exactly (SQLite stores numerics as doubles) refuses a longer value.
    private void guardExactDigits(Model model, String key, BigDecimal fitted) {
        if (isText) {
            return;
        }
        OptionalInt limit = exactDigits(model);
        if (!limit.isPresent()) {
            return;
        }
        int digits = fitted.signum() == 0 ? 1 : fitted.precision();
        if (digits > limit.getAsInt()) {
            throw PrimitiveCasts.invalid(model, key, name, "has " + digits + " significant digits; this database keeps "
                    + limit.getAsInt() + " exactly");
        }
    }

    // A model with no connection (unsaved, no resolver) or an unreachable database has no limit to check:
    // the bind-time path stays as it was.
    private static OptionalInt exactDigits(Model model) {
        if (model == null) {
            return OptionalInt.empty();
        }
        try {
            return model.getConnection().exactDecimalDigits();
        } catch (IllegalStateException | DriverException e) {
            // No connection, or the database cannot be reached: a setter must not fail for that.
            return OptionalInt.empty();
        }
    }

    // Zero has no integer digits whatever its BigDecimal form ("0", "0E+5"), so decimal(2,2) accepts it.
    private static long wholeDigits(BigDecimal decimal) {
        return decimal.signum() == 0 ? 0L : Math.max(0L, CastValues.wholeDigits(decimal));
    }
}
