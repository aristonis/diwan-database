package org.aristonis.diwan.database;

/** Tells which {@link Connection} method ran a statement. */
public enum QueryKind {
    /** A {@code select} that returns all rows. */
    SELECT,
    /** A {@code select} that returns at most one row. */
    SELECT_ONE,
    /** A {@code select} that returns one value. */
    SCALAR,
    /** A {@code select} read through a cursor. */
    CURSOR,
    /** An {@code insert} statement. */
    INSERT,
    /** An {@code update} statement. */
    UPDATE,
    /** A {@code delete} statement. */
    DELETE,
    /** A general statement that returns nothing. */
    STATEMENT,
    /** A statement run only for its affected-row count. */
    AFFECTING
}
