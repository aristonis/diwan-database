package org.aristonis.diwan.database.eloquent.casts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the ordered, unmodifiable cast map a model returns from {@code casts()}.
 *
 * <pre>{@code
 * CastMap.create().put("total", "decimal:2").put("paid_at", Casts.datetime()).build();
 * }</pre>
 *
 * <p>Keys keep insertion order; putting a key again replaces its cast. Not thread-safe; each
 * {@link #build()} returns an independent snapshot.
 */
public final class CastMap {

    private final Map<String, Cast<?>> entries = new LinkedHashMap<>();

    private CastMap() {
    }

    /**
     * Starts an empty builder.
     *
     * @return a new builder
     */
    public static CastMap create() {
        return new CastMap();
    }

    /**
     * Adds a cast for an attribute.
     *
     * @param key the attribute name
     * @param cast the cast
     * @return this builder
     * @throws NullPointerException if {@code key} or {@code cast} is null
     */
    public CastMap put(String key, Cast<?> cast) {
        entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(cast, "cast"));
        return this;
    }

    /**
     * Adds a cast string for an attribute; it is parsed when the model resolves its casts.
     *
     * @param key the attribute name
     * @param spec the cast string, such as {@code decimal:2}
     * @return this builder
     * @throws NullPointerException if {@code key} or {@code spec} is null
     */
    public CastMap put(String key, String spec) {
        return put(key, Casts.of(spec));
    }

    /**
     * Returns the casts added so far.
     *
     * @return an unmodifiable map in insertion order
     */
    public Map<String, Cast<?>> build() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
}
