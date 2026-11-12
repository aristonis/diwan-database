package org.aristonis.diwan.database.eloquent;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.CastComparator;

/**
 * Decides whether a cast attribute still equals its original, for dirty detection.
 *
 * <p>Port of Laravel's {@code HasAttributes::originalIsEquivalent}. The steps run in order:
 * absent original is dirty; identical raws (arrays by content) are clean; a null current value is
 * dirty; a cast that implements {@link CastComparator} decides; any other cast compares the values
 * its {@link Cast#get} returns; an uncast key falls back to {@link AttributeValues#equivalent},
 * the same check models without casts have always used.
 *
 * <p>Laravel's encrypted and {@code As*} steps are reserved: Diwan has no such casts yet, so
 * they are no-ops here. The object and collection casts decide through {@link CastComparator}.
 */
final class DirtyLadder {

    private DirtyLadder() {
    }

    /**
     * Returns whether the current value of an attribute is equivalent to its original.
     *
     * <p>Never throws: a cast that fails to convert or compare reports the attribute dirty. Reads
     * of the attribute still throw.
     *
     * @param model the model that owns the attribute
     * @param key the attribute name
     * @param hasOriginal whether the attribute has an original value at all
     * @param original the stored value at the last sync; may be null
     * @param current the current stored value; may be null
     * @param cast the cast for the key, or null when the key is uncast
     * @param attributes the model's stored attributes, the live read-only view a cast receives
     * @return {@code true} if the attribute is not dirty
     */
    static boolean equivalent(
            Model model, String key, boolean hasOriginal, Object original, Object current, Cast<?> cast,
            Map<String, Object> attributes) {
        if (!hasOriginal) {
            return false;
        }
        if (identical(original, current)) {
            return true;
        }
        if (cast == null) {
            return AttributeValues.equivalent(current, original, model.getDateFormat());
        }
        if (current == null) {
            return false;
        }
        try {
            return castEquivalent(model, key, original, current, cast, attributes);
        } catch (RuntimeException e) {
            // Conversion failure counts as a change; the read path reports the error.
            return false;
        }
    }

    private static boolean identical(Object original, Object current) {
        if (original instanceof byte[] && current instanceof byte[]) {
            return Arrays.equals((byte[]) original, (byte[]) current);
        }
        return Objects.equals(original, current);
    }

    private static boolean castEquivalent(
            Model model, String key, Object original, Object current, Cast<?> cast,
            Map<String, Object> attributes) {
        // Laravel steps for dates, floats, decimals, JSON and primitives live in each built-in
        // cast's CastComparator. Encrypted and As* steps are reserved.
        if (cast instanceof CastComparator) {
            return ((CastComparator) cast).compare(model, key, original, current);
        }
        if (original == null && !cast.handlesNull()) {
            return false;
        }
        return Objects.equals(
                cast.get(model, key, original, attributes), cast.get(model, key, current, attributes));
    }
}
