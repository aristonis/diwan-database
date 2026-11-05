package org.aristonis.diwan.database.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A configuration source over values written in code.
 *
 * <p>The source copies the map it is given, so later changes to that map do not show through.
 * Instances are immutable and safe to share between threads.
 */
public final class MapConfigSource implements ConfigSource {

    /** The copied entries. */
    private final Map<String, String> values;

    private MapConfigSource(Map<String, String> values) {
        this.values = values;
    }

    /**
     * Creates a source from a map of full keys to values.
     *
     * @param values the entries, never {@code null} and without {@code null} keys or values
     * @return a source over a copy of the entries
     * @throws NullPointerException if {@code values}, a key or a value is {@code null}
     */
    public static MapConfigSource of(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "key"),
                    Objects.requireNonNull(entry.getValue(), "value of [" + entry.getKey() + "]"));
        }
        return new MapConfigSource(Collections.unmodifiableMap(copy));
    }

    /**
     * Returns the value stored under a key.
     *
     * @param key the full key, never {@code null}
     * @return the value, or empty when the key is not set
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(values.get(key));
    }

    /**
     * Returns the names one level below a prefix.
     *
     * @param prefix the dot-separated prefix, empty for the top level, never {@code null}
     * @return the direct child segments in natural string order, unmodifiable
     * @throws NullPointerException if {@code prefix} is {@code null}
     */
    @Override
    public Set<String> keys(String prefix) {
        return ConfigKeys.children(values.keySet(), prefix);
    }

    /**
     * Describes the source without its values.
     *
     * @return the class name and the number of entries
     */
    @Override
    public String toString() {
        return "MapConfigSource[keys=" + values.size() + "]";
    }
}
