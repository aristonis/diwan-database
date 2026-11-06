package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The enum cast: stores a constant's {@link BackedValue#value()} when it has one, its
 * {@code name()} otherwise.
 *
 * <p>Lookup goes by value text, so a stored {@code "2"} finds a constant backed by {@code 2L}.
 * The text map is built once from {@link Class#getEnumConstants()}; two constants with the same
 * text are rejected at construction. A stored value that matches no constant throws
 * {@link InvalidCastException}. Immutable and safe to share.
 */
final class EnumCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

    private final Class<?> enumType;

    private final String castName;

    private final Map<String, Object> byText;

    /**
     * Creates the cast for an enum type.
     *
     * @param enumType the enum class
     * @throws IllegalArgumentException when the class is not an enum, a backed value is null, or
     *     two constants share the same value text
     */
    EnumCast(Class<?> enumType) {
        this.enumType = Objects.requireNonNull(enumType, "enumType");
        Object[] constants = enumType.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException("Not an enum type: " + enumType.getName());
        }
        this.castName = "enum:" + enumType.getName();
        Map<String, Object> map = new HashMap<>();
        for (Object constant : constants) {
            String text = String.valueOf(storedValue(constant));
            if (map.putIfAbsent(text, constant) != null) {
                throw new IllegalArgumentException(
                        "Duplicate stored value [" + text + "] in enum " + enumType.getName());
            }
        }
        this.byText = Collections.unmodifiableMap(map);
    }

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        if (raw == null) {
            return null;
        }
        return resolve(model, key, raw);
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        if (value == null) {
            return null;
        }
        return storedValue(resolve(model, key, value));
    }

    @Override
    public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
        return value == null ? null : storedValue(value);
    }

    @Override
    public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
        if (Objects.equals(originalRaw, currentRaw)) {
            return true;
        }
        if (originalRaw == null || currentRaw == null) {
            return false;
        }
        Object original = find(originalRaw);
        return original != null && original == find(currentRaw);
    }

    private Object resolve(Model model, String key, Object value) {
        Object constant = find(value);
        if (constant == null) {
            // Only the types: the stored value can be personal data and must not reach logs (ADR 0005).
            throw new InvalidCastException(model == null ? null : model.getClass(), key, castName,
                    new IllegalArgumentException("No constant of " + enumType.getName()
                            + " has the given "
                            + (value == null ? "null" : value.getClass().getSimpleName()) + " value"));
        }
        return constant;
    }

    private Object find(Object value) {
        if (enumType.isInstance(value)) {
            return value;
        }
        if (value instanceof Enum) {
            return null;
        }
        return byText.get(String.valueOf(value));
    }

    private static Object storedValue(Object constant) {
        if (constant instanceof BackedValue) {
            Object value = ((BackedValue) constant).value();
            if (value == null) {
                throw new IllegalArgumentException("Null backed value on " + constant.getClass().getName());
            }
            return value;
        }
        return ((Enum<?>) constant).name();
    }
}
