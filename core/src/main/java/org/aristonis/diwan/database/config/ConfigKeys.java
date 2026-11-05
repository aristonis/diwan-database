package org.aristonis.diwan.database.config;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Splits flat dot-separated keys into the child segments below a prefix. */
final class ConfigKeys {

    /** The segment separator. */
    private static final char SEPARATOR = '.';

    private ConfigKeys() {
    }

    /**
     * Collects the direct child segments of a prefix.
     *
     * @param keys the flat keys to scan, never {@code null}
     * @param prefix the dot-separated prefix, empty for the top level, never {@code null}
     * @return the sorted, unmodifiable set of segments; empty when no key is below the prefix
     */
    static Set<String> children(Collection<String> keys, String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String start = prefix.isEmpty() ? "" : prefix + SEPARATOR;
        Set<String> found = new TreeSet<>();
        for (String key : keys) {
            if (key.length() <= start.length() || !key.startsWith(start)) {
                continue;
            }
            int end = key.indexOf(SEPARATOR, start.length());
            found.add(end < 0 ? key.substring(start.length()) : key.substring(start.length(), end));
        }
        return Collections.unmodifiableSet(found);
    }
}
