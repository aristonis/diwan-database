package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.junit.jupiter.api.Test;

/** The per-instance cast overlay: {@code mergeCasts} on top of the class casts. */
class ModelCastOverlayTest {

    private static final int THREAD_ROUNDS = 200;

    private static final long WAIT_SECONDS = 10;

    /** A user with an int cast on price and no cast on flag. */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("price", Casts.integer());
        }
    }

    @Test
    void onlyThisInstance() {
        User user = new User();
        user.setRawAttributes(row("flag", 1), true);
        User other = new User();
        other.setRawAttributes(row("flag", 1), true);

        user.mergeCasts(Map.of("flag", "boolean"));

        assertEquals(Boolean.TRUE, user.getAttribute("flag"));
        assertEquals(1, other.getAttribute("flag"));
        assertTrue(user.hasCast("flag", "bool"));
        assertFalse(other.hasCast("flag"));
        assertEquals(Boolean.TRUE, user.toMap().get("flag"));
    }

    @Test
    void overlayWinsClassUnchanged() {
        User user = new User();
        user.setRawAttributes(row("price", "2.5"), true);

        Model returned = user.mergeCasts(Map.of("price", Casts.floating()));

        assertSame(user, returned);
        assertEquals(2.5d, user.getAttribute("price"));
        assertTrue(user.hasCast("price", "float"));
        assertTrue(user.getCasts().containsKey("price"));
        User plain = new User();
        assertTrue(plain.hasCast("price", "int"));
        assertEquals(1, plain.getCasts().size());
    }

    @Test
    void getCastsMergedClassThenOverlay() {
        User user = new User();

        user.mergeCasts(Map.of("flag", "bool"));
        Map<String, Cast<?>> first = user.getCasts();

        assertEquals(List.of("price", "flag"), List.copyOf(first.keySet()));
        assertSame(first, user.getCasts());
        assertThrows(UnsupportedOperationException.class, () -> first.put("x", Casts.string()));
        user.mergeCasts(Map.of("name", "string"));
        assertNotSame(first, user.getCasts());
        assertEquals(3, user.getCasts().size());
    }

    @Test
    void overlayAffectsDirtyChecks() {
        User user = new User();
        user.setRawAttributes(row("flag", 1), true);
        user.mergeCasts(Map.of("flag", "boolean"));

        user.setAttribute("flag", true);

        assertFalse(user.isDirty("flag"));
    }

    @Test
    void separateInstancesKeepSeparateOverlays() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> asBool = pool.submit(reader("boolean"));
            Future<Boolean> asString = pool.submit(reader("string"));

            assertTrue(asBool.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(asString.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Boolean> reader(String spec) {
        return () -> {
            Object expected = "boolean".equals(spec) ? Boolean.TRUE : "1";
            for (int i = 0; i < THREAD_ROUNDS; i++) {
                User user = new User();
                user.setRawAttributes(row("flag", 1), true);
                user.mergeCasts(Map.of("flag", spec));
                if (!expected.equals(user.getAttribute("flag"))) {
                    return false;
                }
            }
            return true;
        };
    }

    @Test
    void sharedResolvedMapCopiedOnWrite() throws Exception {
        Map<String, Cast<?>> shared = Collections.unmodifiableMap(
                new LinkedHashMap<>(Map.of("flag", Casts.bool())));
        User writer = new User();
        writer.setRawAttributes(row("flag", 1, "name", 7), true);
        User reader = new User();
        reader.setRawAttributes(row("flag", 1, "name", 7), true);
        writer.mergeResolvedCasts(shared);
        reader.mergeResolvedCasts(shared);

        writer.mergeCasts(Map.of("name", "string"));

        assertSame(shared, reader.castOverlay());
        assertEquals(1, shared.size());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> fromWriter = pool.submit(sharedReader(writer, "7"));
            Future<Boolean> fromReader = pool.submit(sharedReader(reader, 7));

            assertTrue(fromWriter.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(fromReader.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertSame(shared, reader.castOverlay());
        assertEquals(1, shared.size());
    }

    private static Callable<Boolean> sharedReader(User user, Object expectedName) {
        return () -> {
            for (int i = 0; i < THREAD_ROUNDS; i++) {
                if (!Boolean.TRUE.equals(user.getAttribute("flag"))
                        || !expectedName.equals(user.getAttribute("name"))) {
                    return false;
                }
            }
            return true;
        };
    }

    @Test
    void notCopied() {
        User user = User.QUERY.newBareModel();
        user.setRawAttributes(row("id", 1, "flag", 1), true);
        user.markExisting();
        user.mergeCasts(Map.of("flag", "boolean"));

        User copy = user.replicate();
        Model bare = user.withoutRelations();

        assertEquals(1, copy.getAttribute("flag"));
        assertTrue(copy.castOverlay().isEmpty());
        assertEquals(1, bare.getAttribute("flag"));
        assertTrue(bare.castOverlay().isEmpty());
    }

    @Test
    void badCastRejected() {
        User user = new User();

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> user.mergeCasts(Map.of("flag", "no_such_cast")));

        assertTrue(e.getMessage().contains("flag"));
        assertTrue(user.castOverlay().isEmpty());
    }

    @Test
    void badValueTypeRejected() {
        User user = new User();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> user.mergeCasts(Map.of("flag", 42)));

        assertTrue(e.getMessage().contains("flag"));
        assertTrue(e.getMessage().contains(Integer.class.getName()));
        assertTrue(user.castOverlay().isEmpty());
    }

    @Test
    void nullKeyOrValueRejected() {
        User user = new User();
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("flag", null);
        Map<String, Object> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "bool");

        assertThrows(NullPointerException.class, () -> user.mergeCasts(null));
        assertThrows(IllegalArgumentException.class, () -> user.mergeCasts(nullValue));
        assertThrows(NullPointerException.class, () -> user.mergeCasts(nullKey));
    }

    @Test
    void objectCastReplaced() {
        User user = new User();
        user.setRawAttributes(row("meta", "{\"a\":1}"), true);
        user.mergeCasts(Map.of("meta", "json"));
        Object decoded = user.getAttribute("meta");
        assertTrue(decoded instanceof Map);

        user.mergeCasts(Map.of("meta", "string"));

        assertEquals("{\"a\":1}", user.getAttribute("meta"));
    }

    @Test
    void sharedMapThenMergeCopies() {
        User user = new User();
        user.setRawAttributes(row("flag", 1, "name", 7), true);
        Map<String, Cast<?>> shared = Collections.unmodifiableMap(
                new LinkedHashMap<>(Map.of("flag", Casts.bool())));

        user.mergeResolvedCasts(shared);

        assertSame(shared, user.castOverlay());
        user.mergeCasts(Map.of("name", "string"));
        assertNotSame(shared, user.castOverlay());
        assertEquals(1, shared.size());
        assertEquals(Boolean.TRUE, user.getAttribute("flag"));
        assertEquals("7", user.getAttribute("name"));
    }

    @Test
    void noOverlayUsesClassCache() {
        User user = new User();
        User other = new User();

        assertTrue(user.castOverlay().isEmpty());
        assertSame(user.getCasts(), other.getCasts());
        user.mergeCasts(Map.of());
        assertSame(other.getCasts(), user.getCasts());
    }
}
