package org.aristonis.diwan.database.eloquent;

import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.CastColumns;
import org.aristonis.diwan.database.eloquent.casts.CastComparator;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.casts.InvalidCastException;

/**
 * The cast layer of {@link Model}: applies the casts from {@link #casts()} when attributes are
 * read and written, and decides dirtiness through the cast-aware dirty ladder.
 *
 * <p>Casts resolve once per model class. A read of a cast attribute returns the cast value; a
 * mutable one (anything but text, numbers, booleans, characters, enums and date-time values) is
 * kept per instance, so changes made to it are written back to the stored value before the
 * attributes are read as a whole, checked for changes, synced or saved. A write-back happens only
 * when the value really changed, so reading alone never makes a model dirty. Setting a mutable
 * value keeps that very object, so later changes to the caller's map show up too.
 *
 * <p>A {@code null} stored value reads as {@code null} and a {@code null} value stores
 * {@code null}, unless the cast's {@link Cast#handlesNull()} says otherwise. A failure inside a
 * cast is reported as an {@link InvalidCastException} naming the model, key and cast.
 */
abstract class ModelCasts extends ModelAttributes {

    private static final ConcurrentMap<Class<?>, Map<String, Cast<?>>> RESOLVED =
            new ConcurrentHashMap<>();

    private static final String ALL_CASTS = "*";

    private static final String SPEC_PREFIX = "SpecCast[";

    private static final Map<String, String> ALIASES = aliases();

    private final Map<String, Object> objectCache = new HashMap<>();

    private boolean isMerging;

    ModelCasts() {
    }

    /**
     * Returns an attribute's value with its cast applied, or the stored value when it has none.
     *
     * @param key the attribute, never {@code null}
     * @return the value, or {@code null} when absent or {@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws InvalidCastException if the stored value cannot be read by the cast
     */
    @Override
    public Object getAttribute(String key) {
        Objects.requireNonNull(key, "key");
        Cast<?> cast = castFor(key);
        if (cast == null) {
            return getRawAttribute(key);
        }
        if (objectCache.containsKey(key)) {
            return objectCache.get(key);
        }
        Object value = castGet(cast, key, getRawAttribute(key));
        if (isMutable(value)) {
            objectCache.put(key, value);
        }
        return value;
    }

    /**
     * Sets an attribute's value through its cast, without any mass-assignment check. A cast that
     * returns {@link CastColumns} writes every column it names.
     *
     * <p>A mutable value such as a map or list is kept by reference, as Laravel keeps it, so a
     * later change the caller makes to it is saved with the model.
     *
     * @param key the attribute, never {@code null}
     * @param value the value, may be {@code null}
     * @return this model
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws InvalidCastException if the cast cannot store the value
     */
    @Override
    public Model setAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Cast<?> cast = castFor(key);
        if (cast == null || (value == null && !cast.handlesNull())) {
            putRawAttribute(key, value);
            return self();
        }
        store(key, castSet(cast, key, value));
        if (isMutable(value)) {
            objectCache.put(key, value);
        }
        return self();
    }

    /**
     * Returns an attribute's original value with its cast applied; {@link #getRawOriginal(String)}
     * gives the stored one.
     *
     * @param key the attribute, never {@code null}
     * @return the value, or {@code null} when absent or {@code null}
     * @throws InvalidCastException if the stored value cannot be read by the cast
     */
    @Override
    public Object getOriginal(String key) {
        Cast<?> cast = castFor(key);
        Object raw = getRawOriginal(key);
        return cast == null ? raw : castGet(cast, key, raw);
    }

    /**
     * Returns every original value with its cast applied.
     *
     * @return a read-only copy, never {@code null}
     * @throws InvalidCastException if a stored value cannot be read by its cast
     */
    @Override
    public Map<String, Object> getOriginal() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : super.getOriginal().entrySet()) {
            Cast<?> cast = castFor(entry.getKey());
            Object raw = entry.getValue();
            result.put(entry.getKey(), cast == null ? raw : castGet(cast, entry.getKey(), raw));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns whether an attribute's current value is the same as its original, through the cast
     * when it has one and by the uncast rules otherwise. Never throws for a value the cast
     * cannot read: such a value counts as changed.
     *
     * @param key the attribute, never {@code null}
     * @return whether the value is unchanged
     */
    @Override
    public boolean originalIsEquivalent(String key) {
        return DirtyLadder.equivalent(self(), key, hasRawOriginal(key), getRawOriginal(key),
                getRawAttribute(key), castFor(key), rawAttributesView());
    }

    /**
     * Returns the resolved casts of this model class.
     *
     * @return a read-only map by attribute name, never {@code null}
     * @throws InvalidCastException if a cast cannot be resolved
     */
    public Map<String, Cast<?>> getCasts() {
        return resolvedCasts();
    }

    /**
     * Returns whether an attribute has a cast, and when types are given, whether it is one of
     * them. Types ignore case, an argument after {@code :} and the aliases {@code integer},
     * {@code real}, {@code double}, {@code boolean}, {@code array} and the {@code immutable_} and
     * {@code custom_datetime} forms; a class cast matches its class name.
     *
     * @param key the attribute
     * @param types the cast types, or none for any cast
     * @return whether the attribute has such a cast
     * @throws InvalidCastException if a cast cannot be resolved
     */
    public boolean hasCast(String key, String... types) {
        Cast<?> cast = castFor(key);
        if (cast == null) {
            return false;
        }
        if (types.length == 0) {
            return true;
        }
        String name = Casts.typeName(cast);
        return Arrays.stream(types).anyMatch(type -> type != null && normalise(type).equalsIgnoreCase(name));
    }

    @Override
    void mergeObjectCasts() {
        if (isMerging || objectCache.isEmpty()) {
            return;
        }
        isMerging = true;
        try {
            for (Map.Entry<String, Object> entry : new LinkedHashMap<>(objectCache).entrySet()) {
                mergeOne(entry.getKey(), entry.getValue());
            }
        } finally {
            isMerging = false;
        }
    }

    @Override
    void rawWritten(String key) {
        if (!isMerging) {
            objectCache.remove(key);
        }
    }

    @Override
    void rawReplaced() {
        objectCache.clear();
    }

    @Override
    Map<String, Object> castObjects() {
        return new HashMap<>(objectCache);
    }

    @Override
    void restoreCastObjects(Map<String, Object> objects) {
        objectCache.clear();
        objectCache.putAll(objects);
    }

    private void mergeOne(String key, Object value) {
        Cast<?> cast = castFor(key);
        if (cast == null) {
            return;
        }
        Object stored = castSet(cast, key, value);
        if (stored instanceof CastColumns) {
            store(key, stored);
            return;
        }
        Object current = getRawAttribute(key);
        if (sameStored(current, stored) || isEquivalent(cast, key, current, stored, value)) {
            return;
        }
        putRawAttribute(key, stored);
    }

    private boolean isEquivalent(Cast<?> cast, String key, Object current, Object stored, Object value) {
        try {
            if (cast instanceof CastComparator) {
                return ((CastComparator) cast).compare(self(), key, current, stored);
            }
            return current != null && Objects.equals(castGet(cast, key, current), value);
        } catch (RuntimeException e) {
            // A stored value the cast cannot read is replaced by the merged one.
            return false;
        }
    }

    private static boolean sameStored(Object current, Object stored) {
        if (current instanceof byte[] && stored instanceof byte[]) {
            return Arrays.equals((byte[]) current, (byte[]) stored);
        }
        return Objects.equals(current, stored);
    }

    private void store(String key, Object stored) {
        if (stored instanceof CastColumns) {
            for (Map.Entry<String, Object> column : ((CastColumns) stored).columns().entrySet()) {
                putRawAttribute(column.getKey(), column.getValue());
            }
            return;
        }
        putRawAttribute(key, stored);
    }

    private Object castGet(Cast<?> cast, String key, Object raw) {
        if (raw == null && !cast.handlesNull()) {
            return null;
        }
        try {
            return cast.get(self(), key, raw, rawAttributesView());
        } catch (InvalidCastException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidCastException(getClass(), key, Casts.typeName(cast), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object castSet(Cast<?> cast, String key, Object value) {
        // A cast's value type is erased; a value of the wrong type surfaces as ClassCastException.
        Cast<Object> typed = (Cast<Object>) cast;
        try {
            return typed.set(self(), key, value, rawAttributesView());
        } catch (InvalidCastException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidCastException(getClass(), key, Casts.typeName(cast), e);
        }
    }

    private Cast<?> castFor(String key) {
        return key == null ? null : resolvedCasts().get(key);
    }

    private Map<String, Cast<?>> resolvedCasts() {
        Map<String, Cast<?>> cached = RESOLVED.get(getClass());
        if (cached != null) {
            return cached;
        }
        Map<String, Cast<?>> resolved = resolve();
        Map<String, Cast<?>> raced = RESOLVED.putIfAbsent(getClass(), resolved);
        return raced == null ? resolved : raced;
    }

    private Map<String, Cast<?>> resolve() {
        Map<String, Cast<?>> declared;
        try {
            declared = Objects.requireNonNull(casts(), "casts");
        } catch (IllegalArgumentException e) {
            throw new InvalidCastException(getClass(), ALL_CASTS, "casts()", e);
        }
        Map<String, Cast<?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Cast<?>> entry : declared.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "cast key");
            Cast<?> cast = Objects.requireNonNull(entry.getValue(), "cast");
            try {
                resolved.put(key, Casts.resolve(cast));
            } catch (IllegalArgumentException e) {
                throw new InvalidCastException(getClass(), key, specOf(cast), e);
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static String specOf(Cast<?> cast) {
        String text = String.valueOf(cast);
        if (text.startsWith(SPEC_PREFIX) && text.endsWith("]")) {
            return text.substring(SPEC_PREFIX.length(), text.length() - 1);
        }
        return cast.getClass().getName();
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

    private static String normalise(String type) {
        String name = type.trim().toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        String base = colon < 0 ? name : name.substring(0, colon);
        String alias = ALIASES.get(base);
        // A class cast is named by its class name, kept as written.
        return alias != null ? alias : type.trim();
    }

    private static Map<String, String> aliases() {
        Map<String, String> map = new HashMap<>();
        map.put("int", "int");
        map.put("integer", "int");
        map.put("real", "float");
        map.put("float", "float");
        map.put("double", "float");
        map.put("string", "string");
        map.put("bool", "bool");
        map.put("boolean", "bool");
        map.put("decimal", "decimal");
        map.put("date", "date");
        map.put("immutable_date", "date");
        map.put("datetime", "datetime");
        map.put("immutable_datetime", "datetime");
        map.put("custom_datetime", "datetime");
        map.put("immutable_custom_datetime", "datetime");
        map.put("timestamp", "timestamp");
        map.put("json", "json");
        map.put("array", "json");
        map.put("enum", "enum");
        return Collections.unmodifiableMap(map);
    }
}
