package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.Model;

/**
 * Decides whether a cast attribute changed, for dirty detection.
 *
 * <p>Implemented by a {@link Cast} whose stored values can differ in form while meaning the same
 * thing. Without it, raw values are compared by equality.
 *
 * <p>An exception thrown during a dirty check never escapes it: the attribute counts as changed.
 *
 * @implSpec Implementations must be safe to share across threads and must not modify either value.
 */
public interface CastComparator {

    /**
     * Compares the original and current stored values of an attribute.
     *
     * @param model the model that owns the attribute
     * @param key the attribute name
     * @param originalRaw the stored value at the last sync; may be null
     * @param currentRaw the current stored value; may be null
     * @return {@code true} if the two values are equivalent, so the attribute is not dirty
     * @throws RuntimeException if the values cannot be compared; the model's dirty check catches
     *     it and counts the attribute as changed
     */
    boolean compare(Model model, String key, Object originalRaw, Object currentRaw);
}
