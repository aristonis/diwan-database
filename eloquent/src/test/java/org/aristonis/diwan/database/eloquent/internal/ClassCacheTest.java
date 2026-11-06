package org.aristonis.diwan.database.eloquent.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ClassCacheTest {

    private static final int TIMEOUT_SECONDS = 10;

    @Test
    void sameClassGivesSameValueAndOtherClassGivesOther() {
        ClassCache<Object> cache = ClassCache.of(type -> new Object());

        assertSame(cache.get(String.class), cache.get(String.class));
        assertNotSame(cache.get(String.class), cache.get(Integer.class));
    }

    @Test
    void twoThreadsGetTheSameValue() throws Exception {
        ClassCache<Object> cache = ClassCache.of(type -> new Object());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> {
                start.await();
                return cache.get(String.class);
            });
            Future<Object> second = pool.submit(() -> {
                start.await();
                return cache.get(String.class);
            });
            start.countDown();

            assertSame(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void mapCacheComputesOncePerClass() {
        AtomicInteger calls = new AtomicInteger();
        Function<Class<?>, String> compute = type -> {
            calls.incrementAndGet();
            return type.getSimpleName();
        };
        MapCache<String> cache = new MapCache<>(compute);

        assertEquals("String", cache.get(String.class));
        assertSame(cache.get(String.class), cache.get(String.class));
        assertEquals(1, calls.get());
    }

    @Test
    void mapCacheRejectsNullClassAndNullValue() {
        MapCache<String> cache = new MapCache<>(type -> null);

        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.get(String.class));
    }

    @Test
    void ofRejectsNullFunction() {
        assertThrows(NullPointerException.class, () -> ClassCache.of(null));
    }
}
