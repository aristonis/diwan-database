package org.aristonis.diwan.database.eloquent.casts;

/**
 * Supplies the stored value of an enum constant, like a backed enum in PHP.
 *
 * <p>An enum cast stores {@link #value()} for constants of an enum that implements this
 * interface, and {@link Enum#name()} otherwise.
 *
 * @implSpec Each constant of one enum must return a distinct, non-null value that never changes.
 */
public interface BackedValue {

    /**
     * Returns the value stored for this constant.
     *
     * @return the stored value, never null
     */
    Object value();
}
