package org.aristonis.diwan.database.eloquent.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TouchScopeTest {

    static class Post {
    }

    static final class SpecialPost extends Post {
    }

    static final class Author {
    }

    private static List<Class<?>> classes(Class<?>... types) {
        return Arrays.asList(types);
    }

    @Test
    void noScopeIgnoresNothing() {
        assertFalse(TouchScope.isActive());
        assertFalse(TouchScope.isIgnoring(Post.class));
    }

    @Test
    void listedClassIsIgnoredInsideTheScope() {
        boolean ignored = TouchScope.run(classes(Post.class), () -> TouchScope.isIgnoring(Post.class));

        assertTrue(ignored);
    }

    @Test
    void unlistedClassIsNotIgnored() {
        boolean ignored = TouchScope.run(classes(Post.class), () -> TouchScope.isIgnoring(Author.class));

        assertFalse(ignored);
    }

    @Test
    void subclassOfAListedClassIsIgnored() {
        boolean ignored = TouchScope.run(classes(Post.class),
                () -> TouchScope.isIgnoring(SpecialPost.class));

        assertTrue(ignored);
    }

    @Test
    void superclassOfAListedClassIsNotIgnored() {
        boolean ignored = TouchScope.run(classes(SpecialPost.class),
                () -> TouchScope.isIgnoring(Post.class));

        assertFalse(ignored);
    }

    @Test
    void runReturnsTheWorkValue() {
        String value = TouchScope.run(classes(Post.class), () -> "done");

        assertEquals("done", value);
    }

    @Test
    void emptyAfterTheOutermostScope() {
        TouchScope.run(classes(Post.class), () -> "done");

        assertFalse(TouchScope.isActive());
        assertFalse(TouchScope.isIgnoring(Post.class));
    }

    @Test
    void nestingAddsAndRestoresTheOuterList() {
        List<Boolean> seen = new ArrayList<>();

        TouchScope.run(classes(Post.class), () -> {
            TouchScope.run(classes(Author.class), () -> {
                seen.add(TouchScope.isIgnoring(Post.class));
                seen.add(TouchScope.isIgnoring(Author.class));
                return null;
            });
            seen.add(TouchScope.isIgnoring(Post.class));
            seen.add(TouchScope.isIgnoring(Author.class));
            seen.add(TouchScope.isActive());
            return null;
        });

        assertEquals(Arrays.asList(true, true, true, false, true), seen);
        assertFalse(TouchScope.isActive());
    }

    @Test
    void nestedScopeOverTheSameClassKeepsTheOuterOne() {
        boolean outerStillIgnores = TouchScope.run(classes(Post.class), () -> {
            TouchScope.run(classes(Post.class), () -> null);
            return TouchScope.isIgnoring(Post.class);
        });

        assertTrue(outerStillIgnores);
    }

    @Test
    void exceptionRestoresTheOuterScopeAndPropagates() {
        boolean[] seen = new boolean[2];

        TouchScope.run(classes(Post.class), () -> {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> TouchScope.run(classes(Author.class), () -> {
                        throw new IllegalStateException("boom");
                    }));
            assertEquals("boom", e.getMessage());
            seen[0] = TouchScope.isIgnoring(Post.class);
            seen[1] = TouchScope.isIgnoring(Author.class);
            return null;
        });

        assertTrue(seen[0]);
        assertFalse(seen[1]);
    }

    @Test
    void exceptionInTheOutermostScopeLeavesNoScope() {
        assertThrows(IllegalStateException.class, () -> TouchScope.run(classes(Post.class), () -> {
            throw new IllegalStateException("boom");
        }));

        assertFalse(TouchScope.isActive());
        assertFalse(TouchScope.isIgnoring(Post.class));
    }

    @Test
    void emptyClassListStillOpensAScope() {
        boolean active = TouchScope.run(Collections.<Class<?>>emptyList(), TouchScope::isActive);

        assertTrue(active);
        assertFalse(TouchScope.isActive());
    }

    @Test
    void nullArgumentsAreRefusedWithoutOpeningAScope() {
        List<Class<?>> withNull = new ArrayList<>(Arrays.asList(Post.class, null));

        assertThrows(NullPointerException.class, () -> TouchScope.run(null, () -> null));
        assertThrows(NullPointerException.class, () -> TouchScope.run(classes(Post.class), null));
        assertThrows(NullPointerException.class, () -> TouchScope.run(withNull, () -> null));
        assertThrows(NullPointerException.class, () -> TouchScope.isIgnoring(null));
        assertFalse(TouchScope.isActive());
    }

    @Test
    void laterChangeToTheGivenListDoesNotLeakIn() {
        List<Class<?>> given = new ArrayList<>(classes(Post.class));

        boolean ignored = TouchScope.run(given, () -> {
            given.add(Author.class);
            return TouchScope.isIgnoring(Author.class);
        });

        assertFalse(ignored);
    }

    @Test
    void otherThreadIsUnaffected() throws Exception {
        AtomicBoolean otherIgnores = new AtomicBoolean(true);

        TouchScope.run(classes(Post.class), () -> {
            Thread other = new Thread(() -> otherIgnores.set(TouchScope.isIgnoring(Post.class)));
            other.start();
            join(other);
            return null;
        });

        assertFalse(otherIgnores.get());
    }

    @Test
    void childThreadDoesNotInheritTheScope() {
        AtomicBoolean childActive = new AtomicBoolean(true);

        TouchScope.run(classes(Post.class), () -> {
            Thread child = new Thread(() -> childActive.set(TouchScope.isActive()));
            child.start();
            join(child);
            return null;
        });

        assertFalse(childActive.get());
    }

    @Test
    void pooledThreadCarriesNoScopeIntoTheNextTask() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            boolean insideFirst = pool.submit(
                    () -> TouchScope.run(classes(Post.class), () -> TouchScope.isIgnoring(Post.class))).get();
            boolean activeInSecond = pool.submit(TouchScope::isActive).get();

            assertTrue(insideFirst);
            assertFalse(activeInSecond);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
