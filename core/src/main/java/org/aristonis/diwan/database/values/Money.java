package org.aristonis.diwan.database.values;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.schema.Blueprint;

/**
 * Converts money between {@link BigDecimal} and the two ways {@link Blueprint#money} stores it:
 * whole minor units (cents) and fixed-scale decimal text.
 *
 * <p>Nothing here rounds. A value with more decimal places than the scale, or too large for its
 * form, is refused, since a ledger must not change an amount on its way to the database. Errors
 * name the rule that failed, never the amount.
 */
public final class Money {

    private static final int MAX_SCALE = 18;
    private static final int MAX_INTEGER_DIGITS = 38;
    private static final Pattern PLAIN = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");

    private Money() {
    }

    /**
     * Converts an amount to whole minor units at a scale: {@code 10.10} at scale 2 is {@code 1010}.
     *
     * @param amount the amount, never {@code null}
     * @param scale the number of decimal places of the minor unit, 0 to 18
     * @return the amount in minor units
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws IllegalArgumentException if the scale is out of range, the amount has more decimal
     *     places than the scale, or it does not fit in a {@code long}
     */
    public static long toMinor(BigDecimal amount, int scale) {
        checkScale(scale);
        BigDecimal exact = atScale(Objects.requireNonNull(amount, "amount"), scale);
        java.math.BigInteger minor = exact.unscaledValue();
        // bitLength rather than longValueExact, which Android 28 lacks.
        if (minor.bitLength() >= Long.SIZE) {
            throw new IllegalArgumentException("The amount does not fit in a long at scale " + scale);
        }
        return minor.longValue();
    }

    /**
     * Converts whole minor units back to an amount: {@code 1010} at scale 2 is {@code 10.10}.
     *
     * @param minor the amount in minor units
     * @param scale the number of decimal places of the minor unit, 0 to 18
     * @return the amount, carrying exactly {@code scale} decimal places
     * @throws IllegalArgumentException if the scale is out of range
     */
    public static BigDecimal fromMinor(long minor, int scale) {
        checkScale(scale);
        return BigDecimal.valueOf(minor, scale);
    }

    /**
     * Writes an amount as fixed-scale text, the one spelling a decimal money column accepts:
     * {@code 1E+3} at scale 6 is {@code "1000.000000"}.
     *
     * @param amount the amount, never {@code null}
     * @param scale the exact number of decimal places, 0 to 18
     * @return plain text with exactly {@code scale} decimal places, never an exponent or a
     *     negative zero
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws IllegalArgumentException if the scale is out of range or the amount has more
     *     decimal places than the scale
     */
    public static String toText(BigDecimal amount, int scale) {
        checkScale(scale);
        return atScale(Objects.requireNonNull(amount, "amount"), scale).toPlainString();
    }

    /**
     * Reads fixed-scale text back as an amount.
     *
     * @param text the stored text, never {@code null}
     * @param scale the exact number of decimal places the text must carry, 0 to 18
     * @return the amount
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws IllegalArgumentException if the scale is out of range or the text is not in the
     *     canonical form: ASCII digits, no leading zero, no exponent or plus sign, no negative zero,
     *     exactly {@code scale} decimal places
     */
    public static BigDecimal fromText(String text, int scale) {
        checkScale(scale);
        Objects.requireNonNull(text, "text");
        int dot = text.indexOf('.');
        int places = dot < 0 ? 0 : text.length() - dot - 1;
        if (!PLAIN.matcher(text).matches() || places != scale || scale > 0 && dot < 0) {
            throw new IllegalArgumentException("The text is not a plain decimal with exactly " + scale
                    + " decimal places");
        }
        BigDecimal amount = new BigDecimal(text);
        if (amount.signum() == 0 && text.startsWith("-")) {
            throw new IllegalArgumentException("The text is a negative zero");
        }
        return amount;
    }

    private static BigDecimal atScale(BigDecimal amount, int scale) {
        // An exponent like 1E+1000000000 would build a billion-digit number before any limit applied.
        if ((long) amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("The amount has more than " + MAX_INTEGER_DIGITS + " integer digits");
        }
        try {
            BigDecimal exact = amount.setScale(scale);
            // BigDecimal has no negative zero, but keep the guarantee explicit for readers.
            return exact.signum() == 0 ? BigDecimal.ZERO.setScale(scale) : exact;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The amount has more than " + scale + " decimal places");
        }
    }

    private static void checkScale(int scale) {
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("The scale must be 0 to " + MAX_SCALE);
        }
    }
}
