package org.aristonis.diwan.database.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads aggregate rows and formats increment amounts for the builder's execution methods. */
final class Results {

    private static final Pattern ALIAS = Pattern.compile(" as ", Pattern.CASE_INSENSITIVE);
    private static final String AGGREGATE = "aggregate";
    /** Integer text that fits in a {@code long}; longer integers are read as {@code BigDecimal}. */
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d{1,18}");

    private Results() {
    }

    /** Returns the {@code aggregate} column of the first row, matched without regard to case as Laravel does. */
    static Optional<Object> aggregate(java.util.List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        for (Map.Entry<String, Object> entry : rows.get(0).entrySet()) {
            if (AGGREGATE.equalsIgnoreCase(entry.getKey())) {
                return Optional.ofNullable(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Converts an aggregate to a number as Laravel's numericAggregate does: a number stays as the
     * driver returned it, plain integer text becomes a {@code Long}, and any other numeric text,
     * such as {@code 12.50} or {@code 1.5E+3}, a {@code BigDecimal} (exact, where Laravel uses a
     * float).
     */
    static Number number(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        String text = value.toString().trim();
        try {
            if (INTEGER.matcher(text).matches()) {
                return Long.valueOf(text);
            }
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("The database returned an aggregate that is not a number", e);
        }
    }

    /** Returns the name a selected column has in a result row: the alias, or the part after the last dot. */
    static String resultName(String column) {
        if (column.toLowerCase(Locale.ROOT).contains(" as ")) {
            String[] parts = ALIAS.split(column);
            return parts[parts.length - 1];
        }
        return column.substring(column.lastIndexOf('.') + 1);
    }

    /** Writes an increment amount as SQL. Only the standard number types pass, finite, so the text is a plain number. */
    static String amount(Number amount) {
        if (amount instanceof Integer || amount instanceof Long || amount instanceof Short || amount instanceof Byte
                || amount instanceof BigInteger) {
            return amount.toString();
        }
        if (amount instanceof BigDecimal) {
            return ((BigDecimal) amount).toPlainString();
        }
        // The shortest decimal text of the float or double itself: widening a float to a double
        // first would write 0.1f as 0.10000000149011612.
        if ((amount instanceof Double || amount instanceof Float) && Double.isFinite(amount.doubleValue())) {
            return new BigDecimal(amount.toString()).toPlainString();
        }
        throw new IllegalArgumentException("An increment amount must be a finite standard number");
    }
}
