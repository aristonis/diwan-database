package org.aristonis.diwan.database.eloquent.internal;

import java.util.function.Function;

/** The {@link ClassCache} that stores each value on its class through {@code ClassValue}. */
final class ClassValueCache<V> extends ClassValue<V> implements ClassCache<V> {

    private final Function<Class<?>, V> compute;

    ClassValueCache(Function<Class<?>, V> compute) {
        this.compute = compute;
    }

    @Override
    protected V computeValue(Class<?> type) {
        return compute.apply(type);
    }

    @Override
    public V get(Class<?> type) {
        return super.get(type);
    }
}
