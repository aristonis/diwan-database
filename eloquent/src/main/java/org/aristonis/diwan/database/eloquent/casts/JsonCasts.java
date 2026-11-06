package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.json.JsonCodec;
import org.aristonis.diwan.database.eloquent.exception.JsonException;

/**
 * The {@code json}, {@code array} and {@code json:unicode} casts.
 *
 * <p>Reads decode stored text (or UTF-8 bytes) through the model's codec; an empty string reads
 * as null and broken JSON throws {@link InvalidCastException}. Writes encode the value, so
 * a {@code String} is stored as a JSON string, not as raw JSON text. Dirty checks compare
 * structurally: key order is ignored and numbers compare by value, so {@code 1} equals
 * {@code 1.0}. Stateless and safe to share.
 */
final class JsonCasts implements Cast<Object>, CastComparator, CastSerializer<Object> {

    /** Deepest nesting the structural compare follows, matching the codec's own limit. */
    static final int MAX_DEPTH = 512;

    private final String castName;

    private final boolean isUnicode;

    /**
     * Creates a JSON cast.
     *
     * @param castName the cast name used in error messages, such as {@code json}
     * @param isUnicode whether writes keep non-ASCII characters unescaped ({@code json:unicode})
     */
    JsonCasts(String castName, boolean isUnicode) {
        this.castName = Objects.requireNonNull(castName, "castName");
        this.isUnicode = isUnicode;
    }

    /**
     * Returns the codec a model uses: its {@link Model#getJsonCodec()}, or the standard codec
     * when there is no model.
     *
     * @param model the model, or null
     * @return the codec
     */
    static JsonCodec codecOf(Model model) {
        return model == null ? JsonCodec.standard() : model.getJsonCodec();
    }

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        String text;
        try {
            text = textOf(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidCastException(modelClass(model), key, castName, e);
        }
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return codecOf(model).decode(text);
        } catch (JsonException e) {
            throw new InvalidCastException(modelClass(model), key, castName, e);
        }
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        if (value == null) {
            return null;
        }
        JsonCodec codec = codecOf(model);
        return isUnicode ? codec.encodeUnescaped(value) : codec.encode(value);
    }

    @Override
    public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
        return value;
    }

    @Override
    public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
        if (Objects.equals(originalRaw, currentRaw)) {
            return true;
        }
        try {
            String original = textOf(originalRaw);
            String current = textOf(currentRaw);
            if (Objects.equals(original, current)) {
                return true;
            }
            if (isBlank(original) || isBlank(current)) {
                return isBlank(original) && isBlank(current);
            }
            JsonCodec codec = codecOf(model);
            return structurallyEqual(codec.decode(original), codec.decode(current), 0);
        } catch (IllegalArgumentException | JsonException e) {
            return false;
        }
    }

    /**
     * Compares two decoded JSON values: maps by key set and values, lists by position, numbers by
     * numeric value. Nesting past {@link #MAX_DEPTH} counts as different.
     *
     * @param left one decoded value
     * @param right the other decoded value
     * @param depth the current nesting depth
     * @return true when the values are structurally equal
     */
    static boolean structurallyEqual(Object left, Object right, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (left instanceof Map && right instanceof Map) {
            return mapsEqual((Map<?, ?>) left, (Map<?, ?>) right, depth);
        }
        if (left instanceof List && right instanceof List) {
            return listsEqual((List<?>) left, (List<?>) right, depth);
        }
        if (left instanceof Number && right instanceof Number) {
            return numbersEqual((Number) left, (Number) right);
        }
        return Objects.equals(left, right);
    }

    private static boolean mapsEqual(Map<?, ?> left, Map<?, ?> right, int depth) {
        if (left.size() != right.size() || !left.keySet().equals(right.keySet())) {
            return false;
        }
        for (Map.Entry<?, ?> entry : left.entrySet()) {
            if (!structurallyEqual(entry.getValue(), right.get(entry.getKey()), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean listsEqual(List<?> left, List<?> right, int depth) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!structurallyEqual(left.get(i), right.get(i), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean numbersEqual(Number left, Number right) {
        BigDecimal a = exact(left);
        BigDecimal b = exact(right);
        if (a == null || b == null) {
            return left.equals(right);
        }
        return a.compareTo(b) == 0;
    }

    /** Exact decimal form of a number, or null for NaN, infinity or an unknown subtype. */
    private static BigDecimal exact(Number number) {
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        }
        if (number instanceof BigInteger) {
            return new BigDecimal((BigInteger) number);
        }
        if (number instanceof Long || number instanceof Integer
                || number instanceof Short || number instanceof Byte) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : new BigDecimal(Double.toString(d));
        }
        return null;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isEmpty();
    }

    /** Stored text of a raw value; bytes decode as UTF-8; other types are rejected. */
    private static String textOf(Object raw) {
        if (raw == null || raw instanceof String) {
            return (String) raw;
        }
        if (raw instanceof byte[]) {
            return new String((byte[]) raw, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("JSON column holds unsupported type " + raw.getClass().getSimpleName());
    }

    private static Class<?> modelClass(Model model) {
        return model == null ? null : model.getClass();
    }
}
