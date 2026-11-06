package org.aristonis.diwan.database.schema;

import java.util.ArrayList;

import java.util.Collections;

import java.util.List;

import org.aristonis.diwan.database.values.Instants;

/** The column types of a {@link Blueprint}, with Laravel's names and defaults. */
abstract class BlueprintColumns extends BlueprintKeys {

    BlueprintColumns(String table, String prefix, boolean isCreating) {
        super(table, prefix, isCreating);
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
}
