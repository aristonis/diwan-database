package org.aristonis.diwan.database.schema;

/**
 * The column types a {@link Blueprint} can declare.
 *
 * <p>More constants are added in later versions. A grammar that does not handle a type refuses it
 * with an error naming the type, instead of writing wrong SQL.
 */
public enum ColumnType {
    /** Text with a maximum length, Laravel's {@code string}. */
    STRING,
    /** Long text. */
    TEXT,
    /** A 32-bit integer. */
    INTEGER,
    /** A 64-bit integer. */
    BIG_INTEGER,
    /** A true or false flag. */
    BOOLEAN,
    /** An exact number with a total number of digits and digits after the point. */
    DECIMAL,
    /** A date without time. */
    DATE,
    /** A date and time. */
    TIMESTAMP,
    /** A JSON document. */
    JSON,
    /** An 8-bit integer. */
    TINY_INTEGER,
    /** A 16-bit integer. */
    SMALL_INTEGER,
    /** A 24-bit integer. */
    MEDIUM_INTEGER,
    /** An approximate number; its precision is in binary digits. */
    FLOAT,
    /** A double-precision approximate number. */
    DOUBLE,
    /** Text of a fixed length. */
    CHAR,
    /** Short text, Laravel's {@code tinyText}. */
    TINY_TEXT,
    /** Medium text. */
    MEDIUM_TEXT,
    /** Very long text. */
    LONG_TEXT,
    /** A date and time without a time zone. */
    DATE_TIME,
    /** A date and time with a time zone. */
    DATE_TIME_TZ,
    /** A time of day. */
    TIME,
    /** A time of day with a time zone. */
    TIME_TZ,
    /** A timestamp with a time zone. */
    TIMESTAMP_TZ,
    /** A year. */
    YEAR,
    /** Raw bytes. */
    BINARY,
    /** A UUID. */
    UUID,
    /** An IPv4 or IPv6 address. */
    IP_ADDRESS,
    /** A MAC address. */
    MAC_ADDRESS,
    /** A JSON document stored in binary form where the database has one. */
    JSONB,
    /** One value out of a fixed list. */
    ENUM,
    /** Any number of values out of a fixed list. */
    SET,
    /** A column whose whole type is SQL the caller writes. */
    RAW,
    /** A moment in time: whole microseconds on SQLite, a zoned timestamp elsewhere. */
    INSTANT;

    /**
     * Tells whether the type is an integer that can count up as a key.
     *
     * @return {@code true} for the five integer types
     */
    public boolean isInteger() {
        return this == INTEGER || this == BIG_INTEGER || this == TINY_INTEGER || this == SMALL_INTEGER
                || this == MEDIUM_INTEGER;
    }

    /**
     * Tells whether the type holds a date, a time or both.
     *
     * @return {@code true} for the date, time, timestamp and year types
     */
    public boolean isTemporal() {
        return this == DATE || this == TIMESTAMP || this == DATE_TIME || this == DATE_TIME_TZ || this == TIME
                || this == TIME_TZ || this == TIMESTAMP_TZ || this == YEAR;
    }
}
