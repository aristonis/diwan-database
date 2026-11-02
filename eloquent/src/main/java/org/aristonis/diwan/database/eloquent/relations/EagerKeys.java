package org.aristonis.diwan.database.eloquent.relations;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Collects, normalises and chunks the keys of an eager load, and matches results back by the same
 * normal form.
 *
 * <p>One normal form serves both sides, so a parent key and a child key are equal exactly when
 * their normal forms are: every whole number becomes a {@code Long}, and so does canonical decimal
 * text such as {@code "42"}, because SQLite and PostgreSQL hand back keys of different Java types
 * for the same column. Text that is not canonical ({@code "01"}, {@code " 1"}, {@code "1.0"},
 * {@code "-0"}) stays text, as does every other string, UUID text included; any other object
 * becomes its {@code toString()}.
 *
 * <p>Laravel drops only {@code null} keys before an integer cast in some paths and casts them to
 * {@code 0} in others, so a parent with no key can load the row whose key is {@code 0}. Here a
 * {@code null} key is dropped on every path, and {@code 0} is an ordinary key.
 *
 * <p>Stateless; safe to use from any thread.
 */
final class EagerKeys {

    /** Canonical decimal integer text: no sign on zero, no leading zero, at most 19 digits. */
    private static final Pattern INTEGER_TEXT = Pattern.compile("0|-?[1-9][0-9]{0,18}");

    /** 2^63 as a double: the first whole double above the {@code long} range. */
    private static final double LONG_RANGE_END = 0x1p63;

    private EagerKeys() {
    }

    /**
     * Returns the normal form of a key: a {@code Long} for any whole number in range and for
     * canonical integer text, the string itself for other text, {@code toString()} otherwise.
     *
     * @param key the key, or {@code null}
     * @return the normal form, or {@code null} for a {@code null} key
     */
    static Object normalise(Object key) {
        if (key == null || key instanceof Long) {
            return key;
        }
        if (key instanceof Integer || key instanceof Short || key instanceof Byte) {
            return ((Number) key).longValue();
        }
        if (key instanceof String) {
            return normaliseText((String) key);
        }
        Long whole = wholeNumber(key);
        return whole != null ? whole : key.toString();
    }

    private static Object normaliseText(String text) {
        if (!INTEGER_TEXT.matcher(text).matches()) {
            return text;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            // Nineteen digits above Long.MAX_VALUE: a key no long column holds, so it stays text.
            return text;
        }
    }

    private static Long wholeNumber(Object key) {
        // bitLength, not BigInteger.longValueExact: the latter needs Android API 31.
        if (key instanceof BigInteger) {
            BigInteger number = (BigInteger) key;
            if (number.bitLength() >= Long.SIZE) {
                return null;
            }
            return number.longValue();
        }
        try {
            if (key instanceof BigDecimal) {
                return ((BigDecimal) key).longValueExact();
            }
        } catch (ArithmeticException e) {
            // A fraction or a value past the long range: no integer key can equal it.
            return null;
        }
        if (key instanceof Double || key instanceof Float) {
            double value = ((Number) key).doubleValue();
            boolean isWhole = !Double.isInfinite(value) && value == Math.rint(value);
            if (isWhole && value >= -LONG_RANGE_END && value < LONG_RANGE_END) {
                return (long) value;
            }
        }
        return null;
    }

    /**
     * Collects the distinct keys of some parents, one per normal form, dropping {@code null}.
     *
     * <p>Each returned key is the first-seen original value of its normal form, so a bound
     * {@code whereIn} keeps the parent's Java type: text key {@code "123"} binds as text, never as a
     * {@code Long} that PostgreSQL would refuse to compare with a {@code varchar} column. The normal
     * form serves matching only. When every normal form is a {@code Long} the list is sorted
     * ascending by it, as Laravel sorts integer keys; otherwise it keeps first-seen order.
     *
     * @param <P> the parent type
     * @param parents the parents, never {@code null}
     * @param keyOf reads one parent's key, which may be {@code null}
     * @return the keys, a new modifiable list, never {@code null}
     */
    static <P> List<Object> collect(Collection<? extends P> parents, Function<? super P, ?> keyOf) {
        Objects.requireNonNull(keyOf, "keyOf");
        Map<Object, Object> seen = new LinkedHashMap<>();
        boolean isAllLong = true;
        for (P parent : parents) {
            Object original = keyOf.apply(parent);
            Object key = normalise(original);
            if (key == null) {
                continue;
            }
            seen.putIfAbsent(key, original);
            isAllLong &= key instanceof Long;
        }
        List<Map.Entry<Object, Object>> entries = new ArrayList<>(seen.entrySet());
        if (isAllLong && entries.size() > 1) {
            entries.sort((a, b) -> Long.compare((Long) a.getKey(), (Long) b.getKey()));
        }
        List<Object> keys = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries) {
            keys.add(entry.getValue());
        }
        return keys;
    }

    /**
     * Returns a key as an exact {@code long} for writing into the SQL.
     *
     * @param key the key, in any form {@link #normalise(Object)} takes
     * @param modelClass the class the key belongs to, for the message
     * @param relation the relation name, for the message
     * @return the key as a {@code long}
     * @throws IllegalArgumentException if the key is not an exact integer; the message names the
     *     key's type, never its value
     */
    static long exactLong(Object key, Class<?> modelClass, String relation) {
        Object normal = normalise(key);
        if (normal instanceof Long) {
            return (Long) normal;
        }
        String type = key == null ? "null" : key.getClass().getName();
        throw new IllegalArgumentException("Key of [" + modelClass.getName() + "] for relation [" + relation
                + "] is not an integer: " + type);
    }

    /**
     * Splits keys into consecutive chunks of at most {@code size} keys, in order.
     *
     * @param <T> the key type
     * @param keys the keys, never {@code null}
     * @param size the largest chunk, at least 1
     * @return the chunks, each a new list, none empty; empty for no keys
     * @throws IllegalArgumentException if {@code size} is below 1
     */
    static <T> List<List<T>> chunks(List<T> keys, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Chunk size must be at least 1: " + size);
        }
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<T>> chunks = new ArrayList<>((keys.size() + size - 1) / size);
        for (int from = 0; from < keys.size(); from += size) {
            chunks.add(new ArrayList<>(keys.subList(from, Math.min(keys.size(), from + size))));
        }
        return chunks;
    }
}
