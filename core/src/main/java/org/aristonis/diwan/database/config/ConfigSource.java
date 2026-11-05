package org.aristonis.diwan.database.config;

import java.util.Optional;
import java.util.Set;

/**
 * A read-only source of string configuration values addressed by dot-separated keys.
 *
 * <p>Keys look like {@code database.connections.main.host}. The dot is the only separator; a
 * segment never contains one.
 *
 * @implSpec Implementations must be safe to read from several threads once built, must never
 *     return {@code null} from any method, and must not print values from {@code toString}.
 */
public interface ConfigSource {

    /**
     * Returns the value stored under a key.
     *
     * @param key the full key, never {@code null}
     * @return the value, or empty when the key is not set; an empty string is a value
     * @throws NullPointerException if {@code key} is {@code null}
     */
    Optional<String> get(String key);

    /**
     * Returns the names one level below a prefix.
     *
     * <p>For the keys {@code a.b.x}, {@code a.b.y.z} and {@code a.c}, the prefix {@code a} yields
     * {@code b} and {@code c}, and the prefix {@code a.b} yields {@code x} and {@code y}. The empty
     * prefix yields the first segment of every key.
     *
     * @param prefix the dot-separated prefix, empty for the top level, never {@code null}
     * @return the direct child segments in natural string order; the set is unmodifiable and empty
     *     when nothing sits under the prefix
     * @throws NullPointerException if {@code prefix} is {@code null}
     */
    Set<String> keys(String prefix);

    /**
     * Returns the value stored under a key, failing when it is absent.
     *
     * @param key the full key, never {@code null}
     * @return the value, never {@code null}
     * @throws IllegalArgumentException if the key is not set
     * @throws NullPointerException if {@code key} is {@code null}
     */
    default String require(String key) {
        return get(key).orElseThrow(() -> new IllegalArgumentException("Missing config key [" + key + "]"));
    }
}
