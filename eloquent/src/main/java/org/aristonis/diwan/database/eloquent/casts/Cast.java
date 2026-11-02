package org.aristonis.diwan.database.eloquent.casts;

import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * Converts one model attribute between its stored form and its Java form.
 *
 * <p>Built-in casts and user casts implement this same interface. A cast may also implement
 * {@link CastSerializer} to control serialized output and {@link CastComparator} to control dirty
 * detection.
 *
 * @implSpec Implementations must be safe to share across model instances and threads, because a
 * resolved cast is cached once per model class. A runtime failure thrown from {@link #get} or
 * {@link #set} is wrapped by the model in an {@link InvalidCastException}, except during a dirty
 * check, where an exception from {@link #get} counts the attribute as changed.
 *
 * @param <T> the Java type the attribute is read as
 */
public interface Cast<T> {

    /**
     * Converts a stored value into its Java form.
     *
     * <p>The model calls this with a null {@code raw} only when {@link #handlesNull()} returns
     * {@code true}; otherwise a null stored value reads as null without calling the cast.
     *
     * @param model the model that owns the attribute
     * @param key the attribute name
     * @param raw the stored value; null only when {@link #handlesNull()} is {@code true}
     * @param attributes the live unmodifiable view of the model's raw attributes
     * @return the Java value, which may be null even for a non-null {@code raw}
     * @throws RuntimeException if the stored value cannot be converted; the model wraps it in an
     *     {@link InvalidCastException}
     */
    T get(Model model, String key, Object raw, Map<String, Object> attributes);

    /**
     * Converts a Java value into its stored form.
     *
     * <p>The result is either a single storage value written to {@code key}, or a
     * {@link CastColumns} whose entries are written to their own columns. This mirrors Laravel,
     * where a cast returns either a value or an array of columns. Any other value that the driver
     * cannot store fails when the statement binds.
     *
     * @param model the model that owns the attribute
     * @param key the attribute name
     * @param value the Java value; null only when {@link #handlesNull()} is {@code true}
     * @param attributes the live unmodifiable view of the model's raw attributes
     * @return the storage value for {@code key}, or a {@link CastColumns} for several columns
     * @throws RuntimeException if the value cannot be converted; the model wraps it in an
     *     {@link InvalidCastException}
     */
    Object set(Model model, String key, T value, Map<String, Object> attributes);

    /**
     * Reports whether this cast is called for null values.
     *
     * @implSpec The default returns {@code false}, so null passes through without calling
     * {@link #get} or {@link #set}.
     *
     * @return {@code true} if {@link #get} and {@link #set} receive null values
     */
    default boolean handlesNull() {
        return false;
    }
}
