package org.aristonis.diwan.database.eloquent;

import java.time.temporal.Temporal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.casts.CastColumns;
import org.aristonis.diwan.database.eloquent.internal.ClassCache;

/**
 * The accessor layer of {@link Model}: applies the getters and setters from {@link #accessors()}
 * ahead of any cast on the same key.
 *
 * <p>Accessors resolve once per model class. A getter receives the stored value and a live
 * read-only view of every stored value, so a computed accessor needs no column of its own. A
 * setter returns the value to store, or {@link CastColumns} to store several columns at once; the
 * cast on that key is not applied either way.
 *
 * <p>A getter result is kept per instance when the accessor asks for caching, or when it is a
 * mutable object (anything but text, numbers, booleans, characters, enums and date-time values)
 * and object caching is on. The whole cache is cleared on any stored write — Laravel clears only
 * the key being set, so an accessor computed from other columns goes stale, and a stale object is
 * then written back through the setter on save. Here a cached accessor object is never written
 * back: the setter runs only when the caller sets the key.
 */
abstract class ModelAccessors extends ModelCasts {

    /** One slot per class, filled on first use; a racing second resolve is dropped. */
    private static final ClassCache<AtomicReference<Map<String, Attribute<?>>>> RESOLVED =
            ClassCache.of(type -> new AtomicReference<>());

    private final Map<String, Object> accessorCache = new HashMap<>();

    ModelAccessors() {
    }

    /**
     * Returns an attribute's value through its accessor when it has a getter, else through its
     * cast, else as stored. The key need not be a stored attribute when it has a getter.
     *
     * @param key the attribute, never {@code null}
     * @return the value, or {@code null} when absent or {@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.casts.InvalidCastException if a cast cannot
     *     read the stored value
     */
    @Override
    public Object getAttribute(String key) {
        Objects.requireNonNull(key, "key");
        Attribute<?> accessor = resolvedAccessors().get(key);
        if (accessor == null || accessor.getter() == null) {
            return super.getAttribute(key);
        }
        if (accessorCache.containsKey(key)) {
            return accessorCache.get(key);
        }
        Object value = accessor.getter().get(self(), getRawAttribute(key), rawAttributesView());
        if (accessor.isCaching() || (accessor.isObjectCaching() && isMutable(value))) {
            accessorCache.put(key, value);
        }
        return value;
    }

    /**
     * Sets an attribute's value through its accessor when it has a setter, else through its cast,
     * without any mass-assignment check. A setter that returns {@link CastColumns} stores every
     * column it names.
     *
     * @param key the attribute, never {@code null}
     * @param value the value, may be {@code null}
     * @return this model
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws ClassCastException if the setter does not accept the value's type
     * @throws org.aristonis.diwan.database.eloquent.casts.InvalidCastException if a cast cannot
     *     store the value
     */
    @Override
    public Model setAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Attribute.Setter<Object> setter = setterFor(key);
        if (setter == null) {
            return super.setAttribute(key, value);
        }
        Object stored = setter.set(self(), value, rawAttributesView());
        if (stored instanceof CastColumns) {
            for (Map.Entry<String, Object> column : ((CastColumns) stored).columns().entrySet()) {
                putRawAttribute(column.getKey(), column.getValue());
            }
        } else {
            putRawAttribute(key, stored);
        }
        return self();
    }

    /**
     * Returns an attribute's original value through its accessor when it has a getter, else
     * through its cast. The getter sees the stored originals, and its result is never cached.
     *
     * @param key the attribute, never {@code null}
     * @return the value, or {@code null} when absent or {@code null}
     * @throws org.aristonis.diwan.database.eloquent.casts.InvalidCastException if a cast cannot
     *     read the stored value
     */
    @Override
    public Object getOriginal(String key) {
        Attribute<?> accessor = key == null ? null : resolvedAccessors().get(key);
        if (accessor == null || accessor.getter() == null) {
            return super.getOriginal(key);
        }
        return accessor.getter().get(self(), getRawOriginal(key), rawOriginals());
    }

    /**
     * Returns whether an attribute has an accessor with a getter.
     *
     * @param key the attribute
     * @return whether a getter is declared for it
     */
    final boolean hasGetter(String key) {
        Attribute<?> accessor = key == null ? null : resolvedAccessors().get(key);
        return accessor != null && accessor.getter() != null;
    }

    @Override
    void rawWritten(String key) {
        super.rawWritten(key);
        accessorCache.clear();
    }

    @Override
    void rawReplaced() {
        super.rawReplaced();
        accessorCache.clear();
    }

    @SuppressWarnings("unchecked")
    private Attribute.Setter<Object> setterFor(String key) {
        Attribute<?> accessor = resolvedAccessors().get(key);
        // The accessor's value type is erased; a value of the wrong type surfaces as
        // ClassCastException inside the setter.
        return accessor == null ? null : (Attribute.Setter<Object>) accessor.setter();
    }

    private Map<String, Object> rawOriginals() {
        // Originals are copied from the attributes as a whole, so every original key is also an
        // attribute key; reading through getRawOriginal avoids running any cast.
        Map<String, Object> originals = new LinkedHashMap<>();
        for (String name : rawAttributesView().keySet()) {
            if (hasRawOriginal(name)) {
                originals.put(name, getRawOriginal(name));
            }
        }
        return Collections.unmodifiableMap(originals);
    }

    private Map<String, Attribute<?>> resolvedAccessors() {
        AtomicReference<Map<String, Attribute<?>>> slot = RESOLVED.get(getClass());
        Map<String, Attribute<?>> cached = slot.get();
        if (cached != null) {
            return cached;
        }
        Map<String, Attribute<?>> resolved = resolve();
        return slot.compareAndSet(null, resolved) ? resolved : slot.get();
    }

    private Map<String, Attribute<?>> resolve() {
        Map<String, Attribute<?>> declared = Objects.requireNonNull(accessors(), "accessors");
        Map<String, Attribute<?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Attribute<?>> entry : declared.entrySet()) {
            resolved.put(Objects.requireNonNull(entry.getKey(), "accessor key"),
                    Objects.requireNonNull(entry.getValue(), "accessor"));
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static boolean isMutable(Object value) {
        return value != null
                && !(value instanceof String)
                && !(value instanceof Number)
                && !(value instanceof Boolean)
                && !(value instanceof Character)
                && !(value instanceof Enum)
                && !(value instanceof Temporal);
    }
}
