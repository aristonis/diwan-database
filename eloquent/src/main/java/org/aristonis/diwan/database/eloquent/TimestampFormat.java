package org.aristonis.diwan.database.eloquent;

/**
 * How a model writes its {@code created_at} and {@code updated_at} values.
 */
public enum TimestampFormat {

    /**
     * Text in UTC in the model's date format, {@code yyyy-MM-dd HH:mm:ss} by default, as Laravel
     * writes it. Fits columns made by {@code Blueprint.timestamps()} on every database.
     */
    TEXT,

    /**
     * An {@link java.time.Instant}, which the query grammar converts for its database: whole
     * microseconds on SQLite, {@code timestamptz} on PostgreSQL. For columns made by
     * {@code Blueprint.instant}.
     */
    INSTANT
}
