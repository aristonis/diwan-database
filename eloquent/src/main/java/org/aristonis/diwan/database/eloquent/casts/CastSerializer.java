package org.aristonis.diwan.database.eloquent.casts;

import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * Controls how a cast attribute appears in {@code toMap()} and {@code toJson()} output.
 *
 * <p>Implemented by a {@link Cast} that needs a serialized form different from its Java value.
 * Without it, the value returned by {@link Cast#get} is serialized.
 *
 * @implSpec Implementations must be safe to share across threads.
 *
 * @param <T> the Java type the attribute is read as
 */
public interface CastSerializer<T> {

    /**
     * Returns the serialized form of a cast value.
     *
     * @param model the model that owns the attribute
     * @param key the attribute name
     * @param value the Java value, as returned by {@link Cast#get}; may be null
     * @param attributes the live unmodifiable view of the model's raw attributes
     * @return the value to place in the serialized output; may be null
     * @throws RuntimeException if the value cannot be serialized; the model wraps it in an
     *     {@link InvalidCastException}
     */
    Object serialize(Model model, String key, T value, Map<String, Object> attributes);
}
