package org.aristonis.diwan.database.eloquent.casts;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The {@code collection} and {@code object} casts: a JSON column that must hold an array or an
 * object.
 *
 * <p>Decoding, encoding, the structural dirty check and serialization are the {@code JsonCasts}
 * rules, reached by delegation. This class adds the shape check: a stored value of the other
 * shape, or a written value of the wrong Java type, throws {@code InvalidCastException} with a
 * fixed reason that never carries the value. Null, an empty string and the JSON literal
 * {@code null} read as null. Stateless once built and safe to share.
 *
 * @param <T> the Java type a read returns, {@code List<Object>} or {@code Map<String, Object>}
 */
final class JsonShapeCast<T> implements Cast<T>, CastComparator, CastSerializer<T> {

    /** The two shapes, each with its cast name, the shape text used in messages and its Java type. */
    enum Shape {
        /** A JSON array read as a {@code List}. */
        COLLECTION("collection", "JSON array", List.class),
        /** A JSON object read as a {@code Map}. */
        OBJECT("object", "JSON object", Map.class);

        private final String castName;

        private final String expected;

        private final Class<?> type;

        Shape(String castName, String expected, Class<?> type) {
            this.castName = castName;
            this.expected = expected;
            this.type = type;
        }
    }

    private final Shape shape;

    private final JsonCasts delegate;

    /**
     * Creates the cast.
     *
     * @param shape the shape the column must hold
     */
    JsonShapeCast(Shape shape) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.delegate = new JsonCasts(shape.castName, false);
    }

    /**
     * Returns the cast name, {@code collection} or {@code object}.
     *
     * @return the cast name
     */
    String name() {
        return shape.castName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(Model model, String key, Object raw, Map<String, Object> attributes) {
        Object value = delegate.get(model, key, raw, attributes);
        if (value == null) {
            return null;
        }
        if (!shape.type.isInstance(value)) {
            throw PrimitiveCasts.invalid(model, key, shape.castName,
                    "stored value is a " + kindOf(value) + ", expected a " + shape.expected);
        }
        return (T) value;
    }

    @Override
    public Object set(Model model, String key, T value, Map<String, Object> attributes) {
        if (value == null) {
            return null;
        }
        // T erases to Object, so a value of the wrong type reaches this check without a ClassCastException.
        if (!shape.type.isInstance(value)) {
            throw PrimitiveCasts.invalid(model, key, shape.castName,
                    "expected a " + shape.expected + ", got a " + value.getClass().getSimpleName());
        }
        return delegate.set(model, key, value, attributes);
    }

    @Override
    public Object serialize(Model model, String key, T value, Map<String, Object> attributes) {
        return delegate.serialize(model, key, value, attributes);
    }

    @Override
    public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
        return delegate.compare(model, key, originalRaw, currentRaw);
    }

    private static String kindOf(Object value) {
        if (value instanceof Map) {
            return "JSON object";
        }
        return value instanceof List ? "JSON array" : "JSON scalar";
    }
}
