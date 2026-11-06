package org.aristonis.diwan.database.eloquent.internal;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * The {@link ClassCache} backed by a strong concurrent map, for platforms without
 * {@code ClassValue}. It holds every class it has seen for the life of the process.
 */
final class MapCache<V> implements ClassCache<V> {

    private final ConcurrentMap<Class<?>, V> values = new ConcurrentHashMap<>();
    private final Function<Class<?>, V> compute;

    MapCache(Function<Class<?>, V> compute) {
        this.compute = compute;
    }

    @Override
    public V get(Class<?> type) {
        Objects.requireNonNull(type, "type");
        V cached = values.get(type);
        if (cached != null) {
            return cached;
        }
        V computed = Objects.requireNonNull(compute.apply(type), "computed value");
        V raced = values.putIfAbsent(type, computed);
        return raced == null ? computed : raced;
    }
}
