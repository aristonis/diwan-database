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
    JSON
}
