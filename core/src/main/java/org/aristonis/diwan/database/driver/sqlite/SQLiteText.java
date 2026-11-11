package org.aristonis.diwan.database.driver.sqlite;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The text SQLite drivers store for values SQLite has no type for, so every driver writes the same
 * text and the text sorts and compares as the values do.
 *
 * <p>Dates follow Laravel: {@code yyyy-MM-dd HH:mm:ss}, with a fraction only when there is one.
 * A moment ({@code Instant}, {@code OffsetDateTime}, {@code OffsetTime}) is stored as its UTC time
 * with an explicit {@code +00:00}, so text order is time order and the value stays unambiguous when copied into a
 * zoned column elsewhere. Years outside 0000 to 9999 are refused, since their text would not sort.
 * Decimals are written in plain form, never with an exponent. Other values are returned as they
 * are.
 *
 * @apiNote For drivers in this library; the exact spelling may change between versions.
 */
public final class SQLiteText {

    private static final int MAX_YEAR = 9999;

    private SQLiteText() {
    }

    /**
     * Returns the value as SQLite should store it.
     *
     * @param value the bound value, may be {@code null}
     * @return text for dates, times and decimals; a {@code BigInteger} outside a long as text;
     *     anything else unchanged
     */
    public static Object toStorage(Object value) {
        if (value instanceof LocalDateTime) {
            LocalDateTime time = (LocalDateTime) value;
            return date(time.toLocalDate()) + " " + time(time.toLocalTime());
        }
        if (value instanceof LocalDate) {
            return date((LocalDate) value);
        }
        if (value instanceof LocalTime) {
            return time((LocalTime) value);
        }
        if (value instanceof OffsetDateTime) {
            return toStorage(((OffsetDateTime) value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())
                    + "+00:00";
        }
        if (value instanceof OffsetTime) {
            return time(((OffsetTime) value).withOffsetSameInstant(ZoneOffset.UTC).toLocalTime()) + "+00:00";
        }
        if (value instanceof ZonedDateTime) {
            return toStorage(((ZonedDateTime) value).toOffsetDateTime());
        }
        if (value instanceof Instant) {
            return toStorage(OffsetDateTime.ofInstant((Instant) value, ZoneOffset.UTC));
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof BigInteger && ((BigInteger) value).bitLength() >= Long.SIZE) {
            return value.toString();
        }
        return value;
    }

    /**
     * Refuses a number no database stores faithfully.
     *
     * @param value the bound value, may be {@code null}
     * @param position the binding's position, from 1, named in the message instead of the value
     * @throws IllegalArgumentException if the value is a NaN or infinite {@code Double} or
     *     {@code Float}
     */
    public static void refuseNonFinite(Object value, int position) {
        boolean isNonFinite = value instanceof Double && !Double.isFinite((Double) value)
                || value instanceof Float && !Float.isFinite((Float) value);
        if (isNonFinite) {
            throw new IllegalArgumentException("Binding " + position + " is not a finite number");
        }
    }

    private static String date(LocalDate date) {
        if (date.getYear() < 0 || date.getYear() > MAX_YEAR) {
            throw new IllegalArgumentException("SQLite dates must fall in the years 0000 to 9999");
        }
        return date.toString();
    }

    // Seconds always, a fraction only when present and without trailing zeros.
    private static String time(LocalTime time) {
        String text = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", time.getHour(), time.getMinute(),
                time.getSecond());
        if (time.getNano() == 0) {
            return text;
        }
        String fraction = String.format(java.util.Locale.ROOT, "%09d", time.getNano()).replaceAll("0+$", "");
        return text + "." + fraction;
    }
}
