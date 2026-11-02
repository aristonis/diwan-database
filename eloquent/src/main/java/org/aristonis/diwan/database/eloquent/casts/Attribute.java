package org.aristonis.diwan.database.eloquent.casts;

import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * Describes an accessor and mutator pair for one model attribute.
 *
 * <p>This is the Java form of Laravel's {@code Attribute}. Instances are immutable:
 * {@link #shouldCache()} and {@link #withoutObjectCaching()} return new copies instead of changing
 * the receiver, so one instance can be shared across model instances and threads, provided its
 * getter and setter are.
 *
 * @param <T> the Java type the attribute is read as
 */
public final class Attribute<T> {

    /**
     * Computes the value an accessor returns.
     *
     * @param <T> the Java type the attribute is read as
     */
    @FunctionalInterface
    public interface Getter<T> {

        /**
         * Computes the accessor value.
         *
         * @param model the model that owns the attribute
         * @param value the stored value of the attribute, or null when it has none
         * @param attributes the live unmodifiable view of the model's raw attributes
         * @return the accessor value, possibly null
         */
        T get(Model model, Object value, Map<String, Object> attributes);
    }

    /**
     * Converts a value given to a mutator into its stored form.
     *
     * @param <T> the Java type the attribute is written as
     */
    @FunctionalInterface
    public interface Setter<T> {

        /**
         * Converts the value into its stored form.
         *
         * <p>The result is either a single storage value written to the attribute, or a
         * {@link CastColumns} whose entries are written to their own columns, as in
         * {@link Cast#set}.
         *
         * @param model the model that owns the attribute
         * @param value the value being written, possibly null
         * @param attributes the live unmodifiable view of the model's raw attributes
         * @return the storage value, or a {@link CastColumns} for several columns
         */
        Object set(Model model, T value, Map<String, Object> attributes);
    }

    private final Getter<T> getter;
    private final Setter<T> setter;
    private final boolean caching;
    private final boolean objectCaching;

    private Attribute(Getter<T> getter, Setter<T> setter, boolean caching, boolean objectCaching) {
        this.getter = getter;
        this.setter = setter;
        this.caching = caching;
        this.objectCaching = objectCaching;
    }

    /**
     * Creates an attribute from a getter and a setter.
     *
     * <p>Either function may be null, as in Laravel's {@code Attribute::make}.
     *
     * @param <T> the Java type the attribute is read as
     * @param getter the accessor function, or null for none
     * @param setter the mutator function, or null for none
     * @return the attribute, with caching off and object caching on
     */
    public static <T> Attribute<T> of(Getter<T> getter, Setter<T> setter) {
        return new Attribute<>(getter, setter, false, true);
    }

    /**
     * Creates an attribute that has only a getter.
     *
     * @param <T> the Java type the attribute is read as
     * @param getter the accessor function
     * @return the attribute, with caching off and object caching on
     * @throws NullPointerException if {@code getter} is null
     */
    public static <T> Attribute<T> get(Getter<T> getter) {
        return of(Objects.requireNonNull(getter, "getter"), null);
    }

    /**
     * Creates an attribute that has only a setter.
     *
     * @param <T> the Java type the attribute is written as
     * @param setter the mutator function
     * @return the attribute, with caching off and object caching on
     * @throws NullPointerException if {@code setter} is null
     */
    public static <T> Attribute<T> set(Setter<T> setter) {
        return of(null, Objects.requireNonNull(setter, "setter"));
    }

    /**
     * Returns the accessor function.
     *
     * @return the getter, or null when the attribute has none
     */
    public Getter<T> getter() {
        return getter;
    }

    /**
     * Returns the mutator function.
     *
     * @return the setter, or null when the attribute has none
     */
    public Setter<T> setter() {
        return setter;
    }

    /**
     * Returns a copy that caches the accessor value on each model instance.
     *
     * <p>The receiver is not changed.
     *
     * @return a new attribute with caching on and the other settings copied
     */
    public Attribute<T> shouldCache() {
        return new Attribute<>(getter, setter, true, objectCaching);
    }

    /**
     * Returns a copy that does not cache object accessor values.
     *
     * <p>The receiver is not changed.
     *
     * @return a new attribute with object caching off and the other settings copied
     */
    public Attribute<T> withoutObjectCaching() {
        return new Attribute<>(getter, setter, caching, false);
    }

    /**
     * Reports whether accessor values are cached for every result type.
     *
     * @return {@code true} if {@link #shouldCache()} produced this attribute
     */
    public boolean isCaching() {
        return caching;
    }

    /**
     * Reports whether accessor values that are objects are cached.
     *
     * @return {@code true} unless {@link #withoutObjectCaching()} produced this attribute
     */
    public boolean isObjectCaching() {
        return objectCaching;
    }
}
