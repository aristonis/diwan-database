package org.aristonis.diwan.database.eloquent.internal;

import java.util.Objects;
import java.util.function.Function;

/**
 * A per-class cache that, where the platform allows it, does not keep a class or its loader alive.
 *
 * <p>On a JVM the values live on the {@code Class} itself (through {@code java.lang.ClassValue}),
 * so a class, its loader and its cached value are collected together when the application drops
 * the loader. Where {@code ClassValue} is missing (Android below API 34) the cache is a strong
 * concurrent map that holds every class it has seen for the life of the process, which is fine
 * there because an Android app is never redeployed inside a running process.
 *
 * <p>The implementation is picked once, the first time {@link #of} runs. The {@code ClassValue}
 * based class is only referenced after a probe finds {@code ClassValue}, because Android resolves
 * classes lazily and would otherwise fail on the first reference.
 *
 * <p>The compute function may run more than once for the same class when threads race, and only
 * one result is kept; every caller gets that one. A cached value must be safe to compute twice
 * and must not reference the class loader of the cached class from outside that loader.
 *
 * <p>Internal: not API.
 *
 * @param <V> the cached value type
 */
public interface ClassCache<V> {

    /**
     * Creates a cache that computes a class's value on first use.
     *
     * @param <V> the cached value type
     * @param compute computes the value for a class, never {@code null} and never returning
     *     {@code null}; it may run twice for one class when threads race
     * @return a new empty cache, never {@code null}
     * @throws NullPointerException if {@code compute} is {@code null}
     */
    static <V> ClassCache<V> of(Function<Class<?>, V> compute) {
        Objects.requireNonNull(compute, "compute");
        return hasClassValue() ? new ClassValueCache<>(compute) : new MapCache<>(compute);
    }

    /**
     * Returns the value of a class, computing it on first use.
     *
     * @param type the class, never {@code null}
     * @return the value, never {@code null}; the same instance on every call for that class
     * @throws NullPointerException if {@code type} is {@code null}
     */
    V get(Class<?> type);

    /**
     * Tells whether {@code java.lang.ClassValue} exists on this platform.
     *
     * @return {@code true} when it can be loaded
     */
    private static boolean hasClassValue() {
        try {
            Class.forName("java.lang.ClassValue");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
