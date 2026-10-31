package org.aristonis.diwan.database.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.aristonis.diwan.database.values.Instants;
import org.aristonis.diwan.database.values.Money;

/**
 * Describes the columns and commands for one table, inside a {@link Schema} callback.
 *
 * <p>Column methods use Laravel's names and defaults. Each returns the new
 * {@link ColumnDefinition} for chaining modifiers.
 *
 * <p>A blueprint belongs to the callback it is handed to: it is not thread-safe, and it can no
 * longer be changed once the callback returns.
 */
public final class Blueprint {

    /** Laravel's default length of a {@code string} column. */
    public static final int DEFAULT_STRING_LENGTH = 255;
    /** Laravel's default total digits of a {@code decimal} column. */
    public static final int DEFAULT_DECIMAL_TOTAL = 8;
    /** Laravel's default digits after the point of a {@code decimal} column. */
    public static final int DEFAULT_DECIMAL_PLACES = 2;

    /** Laravel's default precision of a {@code float} column, in binary digits. */
    public static final int DEFAULT_FLOAT_PRECISION = 53;
    /** The largest {@code float} precision, in binary digits. */
    public static final int MAX_FLOAT_PRECISION = 53;
    /** The largest fractional-second precision of a time or timestamp column. */
    public static final int MAX_TIME_PRECISION = 6;
    /** The length of a ULID, which Laravel stores as {@code char(26)}. */
    public static final int ULID_LENGTH = 26;
    /** The length of Laravel's {@code remember_token} column. */
    public static final int REMEMBER_TOKEN_LENGTH = 100;

    private enum MorphKey { NUMERIC, UUID, ULID }

    // Holds a requested index name until the blueprint is checked; never reaches a grammar.
    private static final String REQUESTED = "requestedName";

    private static final int MAX_MONEY_SCALE = 18;
    private final String table;
    private final String prefix;
    private final boolean isCreating;
    private final List<ColumnDefinition> columns = new ArrayList<>();
    private final List<Command> commands = new ArrayList<>();
    private boolean isPrepared;

    Blueprint(String table, String prefix, boolean isCreating) {
        this.table = table;
        this.prefix = prefix;
        this.isCreating = isCreating;
    }

    /**
     * Adds Laravel's {@code id}: an auto-increment big integer primary key named {@code id}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition id() {
        return id("id");
    }

    /**
     * Adds an auto-increment big integer primary key.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition id(String column) {
        return bigInteger(column).unsigned().autoIncrement();
    }

    /**
     * Adds a text column of at most {@value #DEFAULT_STRING_LENGTH} characters.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition string(String column) {
        return string(column, DEFAULT_STRING_LENGTH);
    }

    /**
     * Adds a text column with a maximum length.
     *
     * @param column the column name, never {@code null}
     * @param length the maximum length, at least 1
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code length} is below 1
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition string(String column, int length) {
        if (length < 1) {
            throw new IllegalArgumentException("String length must be at least 1");
        }
        return add(new ColumnDefinition(column, ColumnType.STRING, length, 0, 0));
    }

    /**
     * Adds a long text column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition text(String column) {
        return simple(column, ColumnType.TEXT);
    }

    /**
     * Adds a 32-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition integer(String column) {
        return simple(column, ColumnType.INTEGER);
    }

    /**
     * Adds a 64-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition bigInteger(String column) {
        return simple(column, ColumnType.BIG_INTEGER);
    }

    /**
     * Adds a true or false column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition booleanColumn(String column) {
        return simple(column, ColumnType.BOOLEAN);
    }

    /**
     * Adds an exact number column with {@value #DEFAULT_DECIMAL_TOTAL} digits,
     * {@value #DEFAULT_DECIMAL_PLACES} of them after the point.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition decimal(String column) {
        return decimal(column, DEFAULT_DECIMAL_TOTAL, DEFAULT_DECIMAL_PLACES);
    }

    /**
     * Adds an exact number column.
     *
     * @param column the column name, never {@code null}
     * @param total the total number of digits, at least 1
     * @param places the digits after the point, from 0 to {@code total}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the digits are out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition decimal(String column, int total, int places) {
        if (total < 1 || places < 0 || places > total) {
            throw new IllegalArgumentException("Decimal needs total >= 1 and 0 <= places <= total");
        }
        return add(new ColumnDefinition(column, ColumnType.DECIMAL, 0, total, places));
    }

    /**
     * Adds a money column holding whole minor units: at scale 2, {@code 10.10} is stored as
     * {@code 1010}.
     *
     * <p>Minor units are exact and sum and sort in SQL on every database. Call
     * {@link ColumnDefinition#asDecimal(int)} to store an exact decimal instead. Convert values
     * with {@link Money}; the currency, when there is more than one, belongs in its own column.
     *
     * @param column the column name, never {@code null}
     * @param scale the number of decimal places of the minor unit, 0 to 18 (2 for cents)
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the scale is out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition money(String column, int scale) {
        if (scale < 0 || scale > MAX_MONEY_SCALE) {
            throw new IllegalArgumentException("A money scale must be 0 to " + MAX_MONEY_SCALE);
        }
        return add(new ColumnDefinition(column, ColumnType.MONEY, 0, 0, scale));
    }

    /**
     * Adds a column for a moment in time: whole microseconds since the epoch on SQLite (exact,
     * sortable, without a time zone), {@code timestamptz} on PostgreSQL. Convert with
     * {@link Instants}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition instant(String column) {
        return simple(column, ColumnType.INSTANT);
    }

    /**
     * Adds a date column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition date(String column) {
        return simple(column, ColumnType.DATE);
    }

    /**
     * Adds a date and time column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition timestamp(String column) {
        return simple(column, ColumnType.TIMESTAMP);
    }

    /**
     * Adds a JSON column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition json(String column) {
        return simple(column, ColumnType.JSON);
    }

    /**
     * Adds Laravel's nullable {@code created_at} and {@code updated_at} timestamp columns.
     *
     * @return the two definitions, in that order, as an unmodifiable list
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public List<ColumnDefinition> timestamps() {
        List<ColumnDefinition> added = new ArrayList<>(2);
        added.add(timestamp("created_at").nullable());
        added.add(timestamp("updated_at").nullable());
        return Collections.unmodifiableList(added);
    }

    /**
     * Adds an 8-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition tinyInteger(String column) {
        return simple(column, ColumnType.TINY_INTEGER);
    }

    /**
     * Adds a 16-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition smallInteger(String column) {
        return simple(column, ColumnType.SMALL_INTEGER);
    }

    /**
     * Adds a 24-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition mediumInteger(String column) {
        return simple(column, ColumnType.MEDIUM_INTEGER);
    }

    /**
     * Adds a double-precision number column, Laravel's {@code double}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition doubleColumn(String column) {
        return simple(column, ColumnType.DOUBLE);
    }

    /**
     * Adds a short text column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition tinyText(String column) {
        return simple(column, ColumnType.TINY_TEXT);
    }

    /**
     * Adds a medium text column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition mediumText(String column) {
        return simple(column, ColumnType.MEDIUM_TEXT);
    }

    /**
     * Adds a very long text column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition longText(String column) {
        return simple(column, ColumnType.LONG_TEXT);
    }

    /**
     * Adds a year column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition year(String column) {
        return simple(column, ColumnType.YEAR);
    }

    /**
     * Adds a column of raw bytes.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition binary(String column) {
        return simple(column, ColumnType.BINARY);
    }

    /**
     * Adds a JSON column stored in binary form where the database has one.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition jsonb(String column) {
        return simple(column, ColumnType.JSONB);
    }

    /**
     * Adds an unsigned 8-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition unsignedTinyInteger(String column) {
        return tinyInteger(column).unsigned();
    }

    /**
     * Adds an unsigned 16-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition unsignedSmallInteger(String column) {
        return smallInteger(column).unsigned();
    }

    /**
     * Adds an unsigned 24-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition unsignedMediumInteger(String column) {
        return mediumInteger(column).unsigned();
    }

    /**
     * Adds an unsigned 32-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition unsignedInteger(String column) {
        return integer(column).unsigned();
    }

    /**
     * Adds an unsigned 64-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition unsignedBigInteger(String column) {
        return bigInteger(column).unsigned();
    }

    /**
     * Adds an auto-increment unsigned 32-bit integer primary key.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition increments(String column) {
        return unsignedInteger(column).autoIncrement();
    }

    /**
     * Adds an auto-increment unsigned 32-bit integer primary key.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition integerIncrements(String column) {
        return unsignedInteger(column).autoIncrement();
    }

    /**
     * Adds an auto-increment unsigned 8-bit integer primary key.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition tinyIncrements(String column) {
        return unsignedTinyInteger(column).autoIncrement();
    }

    /**
     * Adds an auto-increment unsigned 16-bit integer primary key.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition smallIncrements(String column) {
        return unsignedSmallInteger(column).autoIncrement();
    }

    /**
     * Adds an auto-increment unsigned 24-bit integer primary key.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition mediumIncrements(String column) {
        return unsignedMediumInteger(column).autoIncrement();
    }

    /**
     * Adds an auto-increment unsigned 64-bit integer primary key.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition bigIncrements(String column) {
        return unsignedBigInteger(column).autoIncrement();
    }

    /**
     * Adds an approximate number column with Laravel's precision of {@value #DEFAULT_FLOAT_PRECISION} binary digits; Laravel's {@code float}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition floatColumn(String column) {
        return floatColumn(column, DEFAULT_FLOAT_PRECISION);
    }

    /**
     * Adds an approximate number column with a precision in binary digits.
     *
     * @param column the column name, never {@code null}
     * @param precision the binary digits of the mantissa, from 1 to {@value #MAX_FLOAT_PRECISION}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the precision is out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition floatColumn(String column, int precision) {
        if (precision < 1 || precision > MAX_FLOAT_PRECISION) {
            throw new IllegalArgumentException("Float precision must be from 1 to " + MAX_FLOAT_PRECISION);
        }
        return add(new ColumnDefinition(column, ColumnType.FLOAT, 0, 0, 0, precision, Collections.<String>emptyList()));
    }

    /**
     * Adds a fixed-length text column of {@value #DEFAULT_STRING_LENGTH} characters; Laravel's {@code char}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition charColumn(String column) {
        return charColumn(column, DEFAULT_STRING_LENGTH);
    }

    /**
     * Adds a fixed-length text column.
     *
     * @param column the column name, never {@code null}
     * @param length the length, at least 1
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code length} is below 1
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition charColumn(String column, int length) {
        if (length < 1) {
            throw new IllegalArgumentException("Char length must be at least 1");
        }
        return add(new ColumnDefinition(column, ColumnType.CHAR, length, 0, 0));
    }

    /**
     * Adds a date and time column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition dateTime(String column) {
        return simple(column, ColumnType.DATE_TIME);
    }

    /**
     * Adds a date and time column with fractional seconds.
     *
     * @param column the column name, never {@code null}
     * @param precision the fractional-second digits, from 0 to {@value #MAX_TIME_PRECISION}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the precision is out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition dateTime(String column, int precision) {
        return add(new ColumnDefinition(column, ColumnType.DATE_TIME, 0, 0, 0, checkTimePrecision(precision),
                Collections.<String>emptyList()));
    }

    /**
     * Adds a date and time column with a time zone.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition dateTimeTz(String column) {
        return simple(column, ColumnType.DATE_TIME_TZ);
    }

    /**
     * Adds a date and time column with a time zone with fractional seconds.
     *
     * @param column the column name, never {@code null}
     * @param precision the fractional-second digits, from 0 to {@value #MAX_TIME_PRECISION}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the precision is out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition dateTimeTz(String column, int precision) {
        return add(new ColumnDefinition(column, ColumnType.DATE_TIME_TZ, 0, 0, 0, checkTimePrecision(precision),
                Collections.<String>emptyList()));
    }

    /**
     * Adds a time of day column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition time(String column) {
        return simple(column, ColumnType.TIME);
    }

    /**
     * Adds a time of day column with fractional seconds.
     *
     * @param column the column name, never {@code null}
     * @param precision the fractional-second digits, from 0 to {@value #MAX_TIME_PRECISION}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the precision is out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition time(String column, int precision) {
        return add(new ColumnDefinition(column, ColumnType.TIME, 0, 0, 0, checkTimePrecision(precision),
                Collections.<String>emptyList()));
    }

    /**
     * Adds a time of day column with a time zone.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition timeTz(String column) {
        return simple(column, ColumnType.TIME_TZ);
    }

    /**
     * Adds a time of day column with a time zone with fractional seconds.
     *
     * @param column the column name, never {@code null}
     * @param precision the fractional-second digits, from 0 to {@value #MAX_TIME_PRECISION}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the precision is out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition timeTz(String column, int precision) {
        return add(new ColumnDefinition(column, ColumnType.TIME_TZ, 0, 0, 0, checkTimePrecision(precision),
                Collections.<String>emptyList()));
    }

    /**
     * Adds a timestamp column with a time zone.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition timestampTz(String column) {
        return simple(column, ColumnType.TIMESTAMP_TZ);
    }

    /**
     * Adds a timestamp column with a time zone with fractional seconds.
     *
     * @param column the column name, never {@code null}
     * @param precision the fractional-second digits, from 0 to {@value #MAX_TIME_PRECISION}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the precision is out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition timestampTz(String column, int precision) {
        return add(new ColumnDefinition(column, ColumnType.TIMESTAMP_TZ, 0, 0, 0, checkTimePrecision(precision),
                Collections.<String>emptyList()));
    }

    /**
     * Adds a date and time column with fractional seconds.
     *
     * @param column the column name, never {@code null}
     * @param precision the fractional-second digits, from 0 to {@value #MAX_TIME_PRECISION}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the precision is out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition timestamp(String column, int precision) {
        return add(new ColumnDefinition(column, ColumnType.TIMESTAMP, 0, 0, 0, checkTimePrecision(precision),
                Collections.<String>emptyList()));
    }

    /**
     * Adds a UUID column named {@code uuid}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition uuid() {
        return uuid("uuid");
    }

    /**
     * Adds a UUID column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition uuid(String column) {
        return simple(column, ColumnType.UUID);
    }

    /**
     * Adds an IP address column named {@code ip_address}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition ipAddress() {
        return ipAddress("ip_address");
    }

    /**
     * Adds an IP address column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition ipAddress(String column) {
        return simple(column, ColumnType.IP_ADDRESS);
    }

    /**
     * Adds a MAC address column named {@code mac_address}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition macAddress() {
        return macAddress("mac_address");
    }

    /**
     * Adds a MAC address column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition macAddress(String column) {
        return simple(column, ColumnType.MAC_ADDRESS);
    }

    /**
     * Adds a ULID column named {@code ulid}: Laravel's 26-character {@code char}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition ulid() {
        return ulid("ulid");
    }

    /**
     * Adds a ULID column: Laravel's 26-character {@code char}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition ulid(String column) {
        return charColumn(column, ULID_LENGTH);
    }

    /**
     * Adds a column holding one value out of a list; Laravel's {@code enum}.
     *
     * @param column the column name, never {@code null}
     * @param allowed the allowed values, at least one, without {@code null} or repeats
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} or {@code allowed} is {@code null}
     * @throws IllegalArgumentException if the name is invalid, or the values are empty, hold {@code null} or repeat
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition enumColumn(String column, List<String> allowed) {
        return add(new ColumnDefinition(column, ColumnType.ENUM, 0, 0, 0, ColumnDefinition.NO_PRECISION,
                checkAllowed(allowed)));
    }

    /**
     * Adds a column holding any number of values out of a list.
     *
     * @param column the column name, never {@code null}
     * @param allowed the allowed values, at least one, without {@code null} or repeats
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} or {@code allowed} is {@code null}
     * @throws IllegalArgumentException if the name is invalid, or the values are empty, hold {@code null} or repeat
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition set(String column, List<String> allowed) {
        return add(new ColumnDefinition(column, ColumnType.SET, 0, 0, 0, ColumnDefinition.NO_PRECISION,
                checkAllowed(allowed)));
    }

    /**
     * Adds Laravel's nullable {@code created_at} and {@code updated_at} timestamp columns with a time zone.
     *
     * @return the two definitions, in that order, as an unmodifiable list
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public List<ColumnDefinition> timestampsTz() {
        List<ColumnDefinition> added = new ArrayList<>(2);
        added.add(timestampTz("created_at").nullable());
        added.add(timestampTz("updated_at").nullable());
        return Collections.unmodifiableList(added);
    }

    /**
     * Adds Laravel's nullable {@code created_at} and {@code updated_at} timestamp columns, as {@link #timestamps()} does.
     *
     * @return the two definitions, in that order, as an unmodifiable list
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public List<ColumnDefinition> nullableTimestamps() {
        List<ColumnDefinition> added = new ArrayList<>(2);
        added.add(timestamp("created_at").nullable());
        added.add(timestamp("updated_at").nullable());
        return Collections.unmodifiableList(added);
    }

    /**
     * Adds Laravel's nullable {@code created_at} and {@code updated_at} date and time columns.
     *
     * @return the two definitions, in that order, as an unmodifiable list
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public List<ColumnDefinition> datetimes() {
        List<ColumnDefinition> added = new ArrayList<>(2);
        added.add(dateTime("created_at").nullable());
        added.add(dateTime("updated_at").nullable());
        return Collections.unmodifiableList(added);
    }

    /**
     * Adds Laravel's nullable {@code deleted_at} timestamp column for soft deletes.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletes() {
        return softDeletes("deleted_at");
    }

    /**
     * Adds a nullable timestamp column for soft deletes.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletes(String column) {
        return timestamp(column).nullable();
    }

    /**
     * Adds Laravel's nullable {@code deleted_at} timestamp with a time zone column for soft deletes.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletesTz() {
        return softDeletesTz("deleted_at");
    }

    /**
     * Adds a nullable timestamp with a time zone column for soft deletes.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletesTz(String column) {
        return timestampTz(column).nullable();
    }

    /**
     * Adds Laravel's nullable {@code deleted_at} date and time column for soft deletes.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletesDatetime() {
        return softDeletesDatetime("deleted_at");
    }

    /**
     * Adds a nullable date and time column for soft deletes.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition softDeletesDatetime(String column) {
        return dateTime(column).nullable();
    }

    /**
     * Adds Laravel's {@code remember_token}: a nullable text column of 100 characters.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition rememberToken() {
        return string("remember_token", REMEMBER_TOKEN_LENGTH).nullable();
    }

    /**
     * Adds a UUID column, ready for {@link ForeignIdColumnDefinition#constrained()}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignIdColumnDefinition foreignUuid(String column) {
        ForeignIdColumnDefinition definition = new ForeignIdColumnDefinition(this, column, ColumnType.UUID, 0);
        add(definition);
        return definition;
    }

    /**
     * Adds a ULID column, ready for {@link ForeignIdColumnDefinition#constrained()}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignIdColumnDefinition foreignUlid(String column) {
        ForeignIdColumnDefinition definition = new ForeignIdColumnDefinition(this, column, ColumnType.CHAR, ULID_LENGTH);
        add(definition);
        return definition;
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a numeric id, Laravel's default, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void morphs(String name) {
        morphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a numeric id, Laravel's default, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void morphs(String name, String indexName) {
        morphColumns(name, MorphKey.NUMERIC, false, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a numeric id, both nullable, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableMorphs(String name) {
        nullableMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a numeric id, both nullable, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.NUMERIC, true, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a numeric id, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void numericMorphs(String name) {
        numericMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a numeric id, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void numericMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.NUMERIC, false, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a numeric id, both nullable, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableNumericMorphs(String name) {
        nullableNumericMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a numeric id, both nullable, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableNumericMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.NUMERIC, true, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a UUID id, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void uuidMorphs(String name) {
        uuidMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a UUID id, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void uuidMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.UUID, false, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a UUID id, both nullable, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableUuidMorphs(String name) {
        nullableUuidMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a UUID id, both nullable, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableUuidMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.UUID, true, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a ULID id, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void ulidMorphs(String name) {
        ulidMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a ULID id, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void ulidMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.ULID, false, indexName);
    }

    /**
     * Adds a polymorphic relation: {@code <name>_type} and {@code <name>_id} with a ULID id, both nullable, and an
     * index on both named {@code <table>_<name>_type_<name>_id_index}.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableUlidMorphs(String name) {
        nullableUlidMorphs(name, null);
    }

    /**
     * Adds a polymorphic relation with a chosen index name: {@code <name>_type} and
     * {@code <name>_id} with a ULID id, both nullable, and an index on both.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void nullableUlidMorphs(String name, String indexName) {
        morphColumns(name, MorphKey.ULID, true, indexName);
    }

    /**
     * Drops Laravel's {@code created_at} and {@code updated_at} columns.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropTimestamps() {
        dropColumn("created_at", "updated_at");
    }

    /**
     * Drops the {@code created_at} and {@code updated_at} columns that {@link #timestampsTz()} adds.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropTimestampsTz() {
        dropColumn("created_at", "updated_at");
    }

    /**
     * Drops Laravel's {@code deleted_at} soft delete column.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropSoftDeletes() {
        dropSoftDeletes("deleted_at");
    }

    /**
     * Drops a soft delete column.
     *
     * @param column the column name, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropSoftDeletes(String column) {
        dropColumn(column);
    }

    /**
     * Drops Laravel's {@code deleted_at} soft delete column.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropSoftDeletesTz() {
        dropSoftDeletesTz("deleted_at");
    }

    /**
     * Drops a soft delete column.
     *
     * @param column the column name, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropSoftDeletesTz(String column) {
        dropColumn(column);
    }

    /**
     * Drops Laravel's {@code remember_token} column.
     *
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropRememberToken() {
        dropColumn("remember_token");
    }

    /**
     * Drops a polymorphic relation: its index, then its two columns.
     *
     * @param name the relation name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropMorphs(String name) {
        dropMorphs(name, null);
    }

    /**
     * Drops a polymorphic relation whose index has a chosen name: the index, then its two columns.
     *
     * @param name the relation name, never {@code null}
     * @param indexName the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if a resulting name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropMorphs(String name, String indexName) {
        List<String> pair = morphPair(name);
        if (indexName == null) {
            dropIndex(pair);
        } else {
            dropIndex(indexName);
        }
        dropColumn(pair.get(0), pair.get(1));
    }

    /**
     * Adds Laravel's {@code foreignId}: an unsigned big integer column, ready for
     * {@link ForeignIdColumnDefinition#constrained()}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignIdColumnDefinition foreignId(String column) {
        ForeignIdColumnDefinition definition = new ForeignIdColumnDefinition(this, column);
        add(definition);
        return definition;
    }

    /**
     * Adds an index over columns, named {@code <table>_<columns>_index}.
     *
     * @param columns the columns in index order, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void index(String... columns) {
        index(list(columns), null);
    }

    /**
     * Adds an index over columns with a chosen name.
     *
     * @param columns the columns in index order, at least one, never {@code null}
     * @param name the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code columns} or a column name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void index(List<String> columns, String name) {
        indexCommand(Command.PLAIN_INDEX, columns, name);
    }

    /**
     * Adds a unique index over columns, named {@code <table>_<columns>_unique}.
     *
     * @param columns the columns in index order, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void unique(String... columns) {
        unique(list(columns), null);
    }

    /**
     * Adds a unique index over columns with a chosen name.
     *
     * @param columns the columns in index order, at least one, never {@code null}
     * @param name the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code columns} or a column name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void unique(List<String> columns, String name) {
        indexCommand(Command.UNIQUE, columns, name);
    }

    /**
     * Makes columns the table's primary key.
     *
     * @apiNote On SQLite, adding a primary key to an existing table rebuilds the table.
     * @param columns the key's columns in order, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void primary(String... columns) {
        primary(list(columns), null);
    }

    /**
     * Makes columns the table's primary key, with a chosen constraint name.
     *
     * @param columns the key's columns in order, at least one, never {@code null}
     * @param name the constraint name, or {@code null} for Laravel's default; SQLite ignores it
     * @throws NullPointerException if {@code columns} or a column name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void primary(List<String> columns, String name) {
        indexCommand(Command.PRIMARY, columns, name);
    }

    /**
     * Adds a foreign key from columns of this table; chain
     * {@link ForeignKeyDefinition#references(String...)} and {@link ForeignKeyDefinition#on(String)}.
     *
     * @apiNote On SQLite, adding a foreign key to an existing table rebuilds the table.
     * @param columns the referencing columns, at least one, never {@code null}
     * @return the foreign key, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition foreign(String... columns) {
        return foreign(list(columns), null);
    }

    /**
     * Adds a foreign key with a chosen name.
     *
     * @param columns the referencing columns, at least one, never {@code null}
     * @param name the key name, or {@code null} for Laravel's {@code <table>_<columns>_foreign}
     * @return the foreign key, never {@code null}
     * @throws NullPointerException if {@code columns} or a column name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition foreign(List<String> columns, String name) {
        ensureOpen();
        ForeignKeyDefinition key = new ForeignKeyDefinition(Objects.requireNonNull(columns, "columns"), name);
        commands.add(new Command(Command.FOREIGN, single(Command.FOREIGN_KEY, key)));
        return key;
    }

    /**
     * Drops a plain index by name.
     *
     * @param name the index name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropIndex(String name) {
        dropByName(Command.DROP_INDEX, name);
    }

    /**
     * Drops the plain index Laravel names after these columns.
     *
     * @param columns the index's columns, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropIndex(List<String> columns) {
        dropByColumns(Command.DROP_INDEX, "index", columns);
    }

    /**
     * Drops a unique index by name.
     *
     * @param name the index name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropUnique(String name) {
        dropByName(Command.DROP_UNIQUE, name);
    }

    /**
     * Drops the unique index Laravel names after these columns.
     *
     * @param columns the index's columns, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropUnique(List<String> columns) {
        dropByColumns(Command.DROP_UNIQUE, "unique", columns);
    }

    /**
     * Drops the table's primary key.
     *
     * @apiNote On SQLite this rebuilds the table.
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropPrimary() {
        dropByName(Command.DROP_PRIMARY, defaultName("primary", Collections.<String>emptyList()));
    }

    /**
     * Drops the table's primary key by its constraint name.
     *
     * @param name the constraint name, never {@code null}; SQLite ignores it
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropPrimary(String name) {
        dropByName(Command.DROP_PRIMARY, name);
    }

    /**
     * Drops a foreign key by name.
     *
     * @apiNote SQLite keys have no names; there, use {@link #dropForeign(List)}.
     * @param name the key name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropForeign(String name) {
        dropByName(Command.DROP_FOREIGN, name);
    }

    /**
     * Drops the foreign key over these columns.
     *
     * @apiNote On SQLite this rebuilds the table.
     * @param columns the key's referencing columns, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropForeign(List<String> columns) {
        dropByColumns(Command.DROP_FOREIGN, "foreign", columns);
    }

    /**
     * Drops the foreign key on a column, then the column, as Laravel's
     * {@code dropConstrainedForeignId} does.
     *
     * @param column the column, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropConstrainedForeignId(String column) {
        dropForeign(Collections.singletonList(column));
        dropColumn(column);
    }

    /**
     * Renames an index.
     *
     * @param from the current name, never {@code null}
     * @param to the new name, never {@code null}
     * @throws NullPointerException if a name is {@code null}
     * @throws IllegalArgumentException if a name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void renameIndex(String from, String to) {
        rename(Command.RENAME_INDEX, "index", from, to);
    }

    /**
     * Drops columns.
     *
     * @apiNote A column still used by an index or foreign key is refused before any SQL runs;
     *     drop the index or key first.
     * @param columns the columns, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropColumn(String... columns) {
        ensureOpen();
        List<String> checked = ForeignKeyDefinition.checkColumns(list(columns));
        commands.add(new Command(Command.DROP_COLUMN, single(Command.COLUMNS, checked)));
    }

    /**
     * Adds a column whose type and everything after it is SQL the caller writes, for what the
     * builder cannot say.
     *
     * <p>The SQL is written as given, like an {@link Expression}: build it from constants only, never from user input.
     * It says the column's nullability and default itself; {@code nullable()} and
     * {@code defaultValue()} add nothing, and only {@code check()} is written after it. On SQLite
     * it cannot hold COLLATE, which a later table rebuild could not carry, and on an existing table
     * it cannot hold a check or generated expression.
     *
     * @param column the column name, never {@code null}
     * @param sql the definition after the name, such as {@code "integer not null default 0"},
     *     never {@code null} or blank
     * @return the column definition, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code sql} is blank
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition rawColumn(String column, String sql) {
        ColumnDefinition definition = new ColumnDefinition(column, ColumnType.RAW, 0, 0, 0);
        definition.rawDefinition(sql);
        return add(definition);
    }

    /**
     * Adds a named {@code CHECK} constraint on the table.
     *
     * <p>The SQL is written as given, like an {@link Expression}: build it from constants only, never from user input.
     *
     * @param name the constraint name, never {@code null}; {@link #dropCheck(String)} drops it by this
     *     name
     * @param sql the condition, without the {@code check} keyword or outer parentheses, never
     *     {@code null} or blank
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code sql} is blank
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void check(String name, String sql) {
        ensureOpen();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.NAME, SchemaNames.check("check", name));
        attributes.put(Command.SQL, ColumnDefinition.trustedSql("check", sql));
        commands.add(new Command(Command.CHECK, attributes));
    }

    /**
     * Drops a named table {@code CHECK} constraint.
     *
     * @param name the constraint name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropCheck(String name) {
        ensureOpen();
        commands.add(new Command(Command.DROP_CHECK, single(Command.NAME, SchemaNames.check("check", name))));
    }

    /**
     * Renames a column.
     *
     * @param from the current name, never {@code null}
     * @param to the new name, never {@code null}
     * @throws NullPointerException if a name is {@code null}
     * @throws IllegalArgumentException if a name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void renameColumn(String from, String to) {
        rename(Command.RENAME_COLUMN, "column", from, to);
    }

    /**
     * Returns the table name, with the connection's prefix.
     *
     * @return the name, never {@code null}
     */
    public String table() {
        return table;
    }

    /**
     * Tells whether this blueprint creates its table.
     *
     * @return {@code true} for {@link Schema#create}, {@code false} for {@link Schema#table}
     */
    public boolean isCreating() {
        return isCreating;
    }

    /**
     * Returns the columns in the order they were added.
     *
     * @return an unmodifiable list, never {@code null}
     */
    public List<ColumnDefinition> columns() {
        return Collections.unmodifiableList(columns);
    }

    /**
     * Returns the commands in the order a grammar compiles them.
     *
     * @return an unmodifiable list, never {@code null}; complete only once the schema builder has
     *     checked the blueprint
     */
    public List<Command> commands() {
        return Collections.unmodifiableList(commands);
    }

    void addCommand(String name, Map<String, ?> attributes) {
        commands.add(new Command(name, attributes));
    }

    /**
     * Checks the blueprint, adds the commands its columns imply and freezes it.
     *
     * <p>The final order is: create, the added and changed columns, the explicit commands in the
     * order they were called, the indexes dropped on changed columns, then the indexes and keys set
     * on columns.
     *
     * @param maxIdentifierLength the grammar's longest identifier
     * @throws IllegalArgumentException if the blueprint is invalid
     */
    void prepare(int maxIdentifierLength) {
        if (isPrepared) {
            return;
        }
        if (isCreating && columns.isEmpty()) {
            throw new IllegalArgumentException("A table needs at least one column");
        }
        Set<String> seen = new HashSet<>();
        for (ColumnDefinition column : columns) {
            if (!seen.add(column.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The same column is added or changed twice");
            }
            checkChange(column);
        }
        List<Command> ordered = new ArrayList<>();
        int explicitStart = 0;
        if (!commands.isEmpty() && Command.CREATE.equals(commands.get(0).name())) {
            ordered.add(commands.get(0));
            explicitStart = 1;
        }
        for (ColumnDefinition column : columns) {
            column.freeze();
            if (!isCreating) {
                ordered.add(new Command(column.isChange() ? Command.CHANGE : Command.ADD,
                        single(Command.COLUMN, column)));
            }
        }
        Set<String> indexes = new HashSet<>();
        for (Command command : commands.subList(explicitStart, commands.size())) {
            ordered.add(settle(command, indexes, maxIdentifierLength));
        }
        // Drops first, so a primary key can move from one changed column to another in either order.
        for (ColumnDefinition column : columns) {
            addFluentDrops(ordered, column, Collections.singletonList(column.name()));
        }
        for (ColumnDefinition column : columns) {
            List<String> own = Collections.singletonList(column.name());
            if (column.isPrimary()) {
                ordered.add(named(Command.PRIMARY, own, null, indexes, maxIdentifierLength));
            }
            if (column.isUnique()) {
                ordered.add(named(Command.UNIQUE, own, column.uniqueIndex().orElse(null), indexes, maxIdentifierLength));
            }
            if (column.isIndexed()) {
                ordered.add(named(Command.PLAIN_INDEX, own, column.plainIndex().orElse(null), indexes,
                        maxIdentifierLength));
            }
        }
        commands.clear();
        commands.addAll(ordered);
        isPrepared = true;
    }

    private void checkChange(ColumnDefinition column) {
        boolean dropsFluent = column.dropsUnique() || column.dropsIndex() || column.dropsPrimary();
        if (!column.isChange()) {
            if (dropsFluent) {
                throw new IllegalArgumentException("unique(false), index(false) and primary(false) need change() on"
                        + " column " + column.name());
            }
            return;
        }
        if (isCreating) {
            throw new IllegalArgumentException("change() applies only to an existing table; column " + column.name()
                    + " is in a new one");
        }
        if (column.isAutoIncrement()) {
            throw new IllegalArgumentException("A column cannot be changed to auto-increment: " + column.name());
        }
        for (Command command : commands) {
            if (Command.RENAME_COLUMN.equals(command.name())
                    && (command.string(Command.FROM).equalsIgnoreCase(column.name())
                    || command.string(Command.TO).equalsIgnoreCase(column.name()))) {
                // Whether the change means the old or the new name depends on order the blueprint does not keep.
                throw new IllegalArgumentException("Column " + column.name() + " is changed and renamed in one"
                        + " call; split them into two calls");
            }
        }
    }

    private void addFluentDrops(List<Command> ordered, ColumnDefinition column, List<String> own) {
        if (column.dropsPrimary()) {
            ordered.add(new Command(Command.DROP_PRIMARY,
                    single(Command.INDEX, defaultName("primary", Collections.<String>emptyList()))));
        }
        if (column.dropsUnique()) {
            ordered.add(droppedByColumns(Command.DROP_UNIQUE, "unique", own));
        }
        if (column.dropsIndex()) {
            ordered.add(droppedByColumns(Command.DROP_INDEX, "index", own));
        }
    }

    private Command droppedByColumns(String type, String suffix, List<String> columns) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.INDEX, defaultName(suffix, columns));
        attributes.put(Command.COLUMNS, columns);
        return new Command(type, attributes);
    }

    private Command settle(Command command, Set<String> indexes, int maxIdentifierLength) {
        switch (command.name()) {
            case Command.PLAIN_INDEX:
            case Command.UNIQUE:
            case Command.PRIMARY:
                if (command.has(Command.INDEX)) {
                    return command;
                }
                return named(command.name(), command.strings(Command.COLUMNS),
                        (String) command.get(REQUESTED).orElse(null), indexes, maxIdentifierLength);
            case Command.FOREIGN:
                ForeignKeyDefinition key = command.foreignKey();
                key.freeze(defaultName("foreign", key.columns()), prefix);
                checkLength(key.name(), key.requestedName() == null, maxIdentifierLength);
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put(Command.FOREIGN_KEY, key);
                attributes.put(Command.INDEX, key.name());
                attributes.put(Command.COLUMNS, key.columns());
                return new Command(Command.FOREIGN, attributes);
            default:
                return command;
        }
    }

    private Command named(String type, List<String> columns, String requested, Set<String> indexes,
            int maxIdentifierLength) {
        String name = requested != null ? requested : defaultName(type, columns);
        checkLength(name, requested == null, maxIdentifierLength);
        if (!indexes.add(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The same index name is used twice");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.INDEX, name);
        attributes.put(Command.COLUMNS, columns);
        return new Command(type, attributes);
    }

    // Counted in UTF-8 bytes, as PostgreSQL counts: a 40-letter Arabic name is 80 bytes and would
    // be cut silently, so a later drop by its full name would miss it.
    private static String checkLength(String name, boolean isGenerated, int maxIdentifierLength) {
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxIdentifierLength) {
            return name;
        }
        if (isGenerated) {
            throw new IllegalArgumentException("The generated index name is longer than " + maxIdentifierLength
                    + " bytes (UTF-8); pass a name");
        }
        throw new IllegalArgumentException("An index name is longer than " + maxIdentifierLength + " bytes (UTF-8)");
    }

    // Laravel's createIndexName.
    private String defaultName(String type, List<String> columns) {
        StringBuilder name = new StringBuilder(table);
        for (String column : columns) {
            name.append('_').append(column);
        }
        name.append('_').append(type);
        return name.toString().toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private void indexCommand(String type, List<String> columns, String name) {
        ensureOpen();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.COLUMNS, ForeignKeyDefinition.checkColumns(Objects.requireNonNull(columns, "columns")));
        if (name != null) {
            attributes.put(REQUESTED, SchemaNames.check("index", name));
        }
        commands.add(new Command(type, attributes));
    }

    private void dropByName(String type, String name) {
        ensureOpen();
        commands.add(new Command(type, single(Command.INDEX, SchemaNames.check("index", name))));
    }

    private void dropByColumns(String type, String suffix, List<String> columns) {
        ensureOpen();
        List<String> checked = ForeignKeyDefinition.checkColumns(Objects.requireNonNull(columns, "columns"));
        commands.add(droppedByColumns(type, suffix, checked));
    }

    private void rename(String type, String kind, String from, String to) {
        ensureOpen();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.FROM, SchemaNames.check(kind, from));
        attributes.put(Command.TO, SchemaNames.check(kind, to));
        commands.add(new Command(type, attributes));
    }

    private void morphColumns(String name, MorphKey key, boolean isNullable, String indexName) {
        List<String> pair = morphPair(name);
        ColumnDefinition type = string(pair.get(0));
        ColumnDefinition id;
        if (key == MorphKey.UUID) {
            id = uuid(pair.get(1));
        } else if (key == MorphKey.ULID) {
            id = ulid(pair.get(1));
        } else {
            id = unsignedBigInteger(pair.get(1));
        }
        if (isNullable) {
            type.nullable();
            id.nullable();
        }
        index(pair, indexName);
    }

    private static List<String> morphPair(String name) {
        SchemaNames.check("morph", name);
        return Arrays.asList(name + "_type", name + "_id");
    }

    private static int checkTimePrecision(int precision) {
        if (precision < 0 || precision > MAX_TIME_PRECISION) {
            throw new IllegalArgumentException("Time precision must be from 0 to " + MAX_TIME_PRECISION);
        }
        return precision;
    }

    private static List<String> checkAllowed(List<String> values) {
        List<String> allowed = new ArrayList<>(Objects.requireNonNull(values, "allowed"));
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed value is needed");
        }
        Set<String> seen = new HashSet<>();
        for (String value : allowed) {
            if (value == null) {
                throw new IllegalArgumentException("An allowed value is null");
            }
            if (!seen.add(value)) {
                throw new IllegalArgumentException("An allowed value is listed twice");
            }
        }
        return allowed;
    }

    private static List<String> list(String[] columns) {
        return Arrays.asList(Objects.requireNonNull(columns, "columns"));
    }

    private static Map<String, Object> single(String key, Object value) {
        return Collections.singletonMap(key, value);
    }

    private ColumnDefinition simple(String column, ColumnType type) {
        return add(new ColumnDefinition(column, type, 0, 0, 0));
    }

    private ColumnDefinition add(ColumnDefinition column) {
        ensureOpen();
        columns.add(column);
        return column;
    }

    private void ensureOpen() {
        if (isPrepared) {
            throw new IllegalStateException("The blueprint can no longer be changed");
        }
    }
}
