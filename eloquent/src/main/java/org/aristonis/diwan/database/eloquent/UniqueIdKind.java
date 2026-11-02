package org.aristonis.diwan.database.eloquent;

/**
 * The kind of unique string id a model generates for its primary key on insert.
 */
public enum UniqueIdKind {

    /** No generated id: the key is given by the caller or by the database. */
    NONE,

    /** A version 7 UUID in lower case, as Laravel's {@code HasUuids}. */
    UUID,

    /** A lower-case ULID, as Laravel's {@code HasUlids}. */
    ULID
}
