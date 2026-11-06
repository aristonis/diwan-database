package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.Attribute;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.Test;

/** A model class loaded by a throw-away loader is collected once the loader is dropped. */
class ClassLoaderLeakTest {

    private static final int MAX_GC_ROUNDS = 20;
    private static final long GC_PAUSE_MILLIS = 50;
    private static final int GARBAGE_BLOCKS = 8;
    private static final int GARBAGE_BLOCK_BYTES = 512 * 1024;
    private static final String PROBE_PREFIX = ClassLoaderLeakTest.class.getName() + "$";

    /** Runs inside the throw-away loader and touches casts, accessors and the registry. */
    public static final class Probe implements Runnable {
        @Override
        public void run() {
            ModelQuery.of(Leaky.class, Leaky::new);
            Leaky model = new Leaky();
            model.setAttribute("score", "3");
            assertEquals(3L, model.getAttribute("score"));
            assertEquals("label:3", model.getAttribute("label"));
        }
    }

    /** The model the probe loads through the throw-away loader. */
    public static final class Leaky extends Model {
        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("score", Casts.integer());
        }

        @Override
        protected Map<String, Attribute<?>> accessors() {
            return Map.of("label", Attribute.<String>get((model, value, attributes) -> "label:" + attributes.get("score")));
        }
    }

    /** Defines the probe classes itself and leaves every other class to the parent. */
    private static final class ThrowawayLoader extends ClassLoader {
        ThrowawayLoader() {
            super(ClassLoaderLeakTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PROBE_PREFIX)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : define(name);
            }
        }

        private Class<?> define(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    @Test
    void droppedLoaderIsCollected() throws Exception {
        WeakReference<ClassLoader> ref = touchThroughFreshLoader();

        for (int round = 0; round < MAX_GC_ROUNDS && ref.get() != null; round++) {
            allocateGarbage();
            System.gc();
            Thread.sleep(GC_PAUSE_MILLIS);
        }

        assertNull(ref.get(), "the loader of a dropped model class must be collectable");
    }

    /** Allocates and drops a few megabytes, so each round gives the collector work to reclaim. */
    private static int allocateGarbage() {
        int total = 0;
        for (int i = 0; i < GARBAGE_BLOCKS; i++) {
            total += new byte[GARBAGE_BLOCK_BYTES].length;
        }
        return total;
    }

    private static WeakReference<ClassLoader> touchThroughFreshLoader() throws Exception {
        ThrowawayLoader loader = new ThrowawayLoader();
        Class<?> probe = Class.forName(Probe.class.getName(), true, loader);
        ((Runnable) probe.getDeclaredConstructor().newInstance()).run();
        return new WeakReference<>(loader);
    }
}
