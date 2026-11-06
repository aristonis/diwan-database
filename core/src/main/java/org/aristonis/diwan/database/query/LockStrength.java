package org.aristonis.diwan.database.query;

/**
 * How strongly a locked select holds the rows it reads.
 *
 * <p>PostgreSQL renders all four. SQLite has no row locks, so it accepts only {@link #UPDATE} and
 * {@link #SHARE}, as a no-op, and refuses the others with {@link UnsupportedOperationException}.
 */
public enum LockStrength {

    /** {@code for update}: blocks other writers and other locks on the rows. */
    UPDATE,

    /** {@code for no key update}: like {@link #UPDATE}, but lets other transactions take {@link #KEY_SHARE}. */
    NO_KEY_UPDATE,

    /** {@code for share}: blocks writers, lets other transactions share the rows. */
    SHARE,

    /** {@code for key share}: blocks only changes to the rows' keys and deletes. */
    KEY_SHARE
}
