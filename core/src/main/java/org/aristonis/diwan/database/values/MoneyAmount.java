package org.aristonis.diwan.database.values;

import java.math.BigDecimal;
import java.util.Objects;
import org.aristonis.diwan.database.schema.Blueprint;
import org.aristonis.diwan.database.schema.ColumnDefinition;

/**
 * An amount of money at a fixed scale, read the same way whichever database stored it.
 *
 * <p>A {@link Blueprint#money} column stores whole minor units; with
 * {@link ColumnDefinition#asDecimal(int)} it stores checked text on SQLite and {@code numeric} on
 * PostgreSQL. {@link #read(Object, int)} accepts each of those stored forms and returns the same
 * amount. Writing stays explicit, so code never guesses what a column holds: bind
 * {@link #toMinor()} to a minor-unit column, {@link #toText()} to a decimal column on SQLite and
 * {@link #amount()} to one on PostgreSQL.
 *
 * <p>Instances are immutable; nothing rounds.
 */
public final class MoneyAmount {

    private final BigDecimal amount;
    private final int scale;

    private MoneyAmount(BigDecimal amount, int scale) {
        this.amount = amount;
        this.scale = scale;
    }

    /**
     * Creates an amount at a scale.
     *
     * @param amount the amount, never {@code null}
     * @param scale the number of decimal places, 0 to 18
     * @return the amount, carrying exactly {@code scale} decimal places
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws IllegalArgumentException if the scale is out of range or the amount has more decimal
     *     places than the scale
     */
    public static MoneyAmount of(BigDecimal amount, int scale) {
        return new MoneyAmount(Money.fromText(Money.toText(amount, scale), scale), scale);
    }

    /**
     * Reads a stored amount: whole minor units ({@code Long} or another integer), checked text
     * ({@code String}) or a {@code BigDecimal}.
     *
     * @param stored the value as the connection returned it, never {@code null}
     * @param scale the column's scale, 0 to 18
     * @return the amount
     * @throws NullPointerException if {@code stored} is {@code null}
     * @throws IllegalArgumentException if the value is of another type, text is not in the checked
     *     form, or a decimal has more places than the scale
     */
    public static MoneyAmount read(Object stored, int scale) {
        Objects.requireNonNull(stored, "stored");
        if (stored instanceof Long || stored instanceof Integer || stored instanceof Short) {
            return new MoneyAmount(Money.fromMinor(((Number) stored).longValue(), scale), scale);
        }
        if (stored instanceof java.math.BigInteger && ((java.math.BigInteger) stored).bitLength() < Long.SIZE) {
            return new MoneyAmount(Money.fromMinor(((java.math.BigInteger) stored).longValue(), scale), scale);
        }
        if (stored instanceof String) {
            return new MoneyAmount(Money.fromText((String) stored, scale), scale);
        }
        if (stored instanceof BigDecimal) {
            return of((BigDecimal) stored, scale);
        }
        throw new IllegalArgumentException("A money amount is stored as whole minor units, text or a decimal, not "
                + stored.getClass().getSimpleName());
    }

    /**
     * Returns the amount.
     *
     * @return the amount, with exactly {@link #scale()} decimal places
     */
    public BigDecimal amount() {
        return amount;
    }

    /**
     * Returns the scale.
     *
     * @return the number of decimal places
     */
    public int scale() {
        return scale;
    }

    /**
     * Returns the amount in whole minor units, for a minor-unit money column.
     *
     * @return the minor units
     * @throws IllegalArgumentException if they do not fit in a {@code long}
     */
    public long toMinor() {
        return Money.toMinor(amount, scale);
    }

    /**
     * Returns the amount as checked text, for a decimal money column on SQLite.
     *
     * @return the text, with exactly {@link #scale()} decimal places
     */
    public String toText() {
        return Money.toText(amount, scale);
    }

    /**
     * Compares by amount and scale.
     *
     * @param other the object to compare with, may be {@code null}
     * @return {@code true} if {@code other} is a {@code MoneyAmount} with the same amount and scale
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof MoneyAmount && ((MoneyAmount) other).scale == scale
                && ((MoneyAmount) other).amount.equals(amount);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(amount, scale);
    }

    /**
     * Returns the amount as plain text.
     *
     * @return the text, such as {@code 10.10}
     */
    @Override
    public String toString() {
        return toText();
    }
}
