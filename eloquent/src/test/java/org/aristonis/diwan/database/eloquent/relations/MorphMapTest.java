package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aristonis.diwan.database.eloquent.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.Test;

class MorphMapTest {

    /** A model with no alias unless a test adds one. */
    static final class Alpha extends Model {
        static final ModelQuery<Alpha> QUERY = ModelQuery.of(Alpha.class, Alpha::new);
    }

    /** A second model, for conflicts with {@link Alpha}. */
    static final class Beta extends Model {
        static final ModelQuery<Beta> QUERY = ModelQuery.of(Beta.class, Beta::new);
    }

    // Kept outside NotAModel: reading a static field of NotAModel would run its initialiser.
    static final AtomicBoolean NOT_A_MODEL_INITIALISED = new AtomicBoolean(false);

    /** A class that is not a model; its static initialiser records that it ran. */
    static final class NotAModel {
        static {
            NOT_A_MODEL_INITIALISED.set(true);
        }
    }

    private static Map<String, Class<? extends Model>> single(String alias, Class<? extends Model> type) {
        Map<String, Class<? extends Model>> map = new LinkedHashMap<>();
        map.put(alias, type);
        return map;
    }

    @Test
    void mapIsRequiredByDefault() {
        assertTrue(MorphMap.requiresMorphMap());
    }

    @Test
    void unmappedModelIsRefusedWhileTheMapIsRequired() {
        ClassMorphViolationException thrown = assertThrows(ClassMorphViolationException.class,
                () -> new Alpha().getMorphClass());

        assertEquals(Alpha.class.getName(), thrown.model());
        assertTrue(thrown.getMessage().contains(Alpha.class.getName()));
    }

    @Test
    void mappedModelWritesItsAlias() {
        MorphMap.morphMap(single("alphas", Alpha.class));

        assertEquals("alphas", new Alpha().getMorphClass());
        assertEquals(Optional.of("alphas"), MorphMap.getMorphAlias(Alpha.class));
        assertEquals(Optional.of(Alpha.class), MorphMap.getMorphedModel("alphas"));
    }

    @Test
    void optingOutWritesAndResolvesTheClassName() {
        MorphMap.requireMorphMap(false);

        assertFalse(MorphMap.requiresMorphMap());
        assertEquals(Alpha.class.getName(), new Alpha().getMorphClass());
        assertSame(Alpha.class, MorphMap.resolve(Alpha.class.getName(), getClass().getClassLoader()));
    }

    @Test
    void requiredMapRefusesAnUnknownTypeString() {
        assertThrows(ClassMorphViolationException.class,
                () -> MorphMap.resolve(Alpha.class.getName(), getClass().getClassLoader()));
    }

    @Test
    void aliasResolvesBeforeAnyClassLookup() {
        MorphMap.morphMap(single("alphas", Alpha.class));
        MorphMap.requireMorphMap(false);

        assertSame(Alpha.class, MorphMap.resolve("alphas", getClass().getClassLoader()));
    }

    @Test
    void unknownClassNameIsRefusedWhenOptedOut() {
        MorphMap.requireMorphMap(false);

        ClassMorphViolationException thrown = assertThrows(ClassMorphViolationException.class,
                () -> MorphMap.resolve("no.such.Thing", getClass().getClassLoader()));

        assertEquals("no.such.Thing", thrown.model());
    }

    @Test
    void nonModelClassNameIsRefusedAndItsInitialiserDoesNotRun() {
        MorphMap.requireMorphMap(false);

        assertThrows(ClassMorphViolationException.class,
                () -> MorphMap.resolve(NotAModel.class.getName(), getClass().getClassLoader()));

        assertFalse(NOT_A_MODEL_INITIALISED.get());
    }

    @Test
    void aliasBoundToAnotherClassIsRefused() {
        MorphMap.morphMap(single("things", Alpha.class));

        assertThrows(IllegalArgumentException.class, () -> MorphMap.morphMap(single("things", Beta.class)));
        assertEquals(Optional.of(Alpha.class), MorphMap.getMorphedModel("things"));
    }

    @Test
    void classUnderASecondAliasIsRefused() {
        MorphMap.morphMap(single("first", Alpha.class));

        assertThrows(IllegalArgumentException.class, () -> MorphMap.morphMap(single("second", Alpha.class)));
        assertEquals(Optional.empty(), MorphMap.getMorphedModel("second"));
    }

    @Test
    void samePairTwiceIsANoOp() {
        MorphMap.morphMap(single("alphas", Alpha.class));
        MorphMap.morphMap(single("alphas", Alpha.class));

        assertEquals(Collections.singletonMap("alphas", Alpha.class), MorphMap.morphMap());
    }

    @Test
    void refusedBatchAddsNothing() {
        Map<String, Class<? extends Model>> batch = new LinkedHashMap<>();
        batch.put("alphas", Alpha.class);
        batch.put("alphas2", Alpha.class);

        assertThrows(IllegalArgumentException.class, () -> MorphMap.morphMap(batch));
        assertTrue(MorphMap.morphMap().isEmpty());
    }

    @Test
    void emptyAndNullAliasesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> MorphMap.morphMap(single("", Alpha.class)));
        assertThrows(NullPointerException.class, () -> MorphMap.morphMap(single(null, Alpha.class)));
        assertThrows(NullPointerException.class, () -> MorphMap.morphMap(single("alphas", null)));
    }

    @Test
    void aliasZeroIsStoredAndResolvedAsZero() {
        MorphMap.morphMap(single("0", Alpha.class));

        assertEquals("0", new Alpha().getMorphClass());
        assertSame(Alpha.class, MorphMap.resolve("0", getClass().getClassLoader()));
        assertNull(MorphMap.getMorphedModel("00").orElse(null));
    }

    @Test
    void enforceMorphMapAddsAliasesAndRequiresTheMap() {
        MorphMap.requireMorphMap(false);

        MorphMap.enforceMorphMap(single("alphas", Alpha.class));

        assertTrue(MorphMap.requiresMorphMap());
        assertEquals("alphas", MorphMap.aliasOf(Alpha.class));
    }

    @Test
    void laterMapChangeShowsInTheNextLookup() {
        MorphMap.morphMap(single("alphas", Alpha.class));
        Alpha alpha = new Alpha();
        assertEquals("alphas", alpha.getMorphClass());

        MorphMap.resetForTests();
        MorphMap.morphMap(single("alphas_v2", Alpha.class));

        assertEquals("alphas_v2", alpha.getMorphClass());
    }

    @Test
    void snapshotReturnedByMorphMapDoesNotChange() {
        MorphMap.morphMap(single("alphas", Alpha.class));
        Map<String, Class<? extends Model>> before = MorphMap.morphMap();

        MorphMap.morphMap(single("betas", Beta.class));

        assertEquals(1, before.size());
        assertThrows(UnsupportedOperationException.class, () -> before.put("x", Beta.class));
    }

    @Test
    void readersAlwaysSeeAConsistentMapWhileAWriterMerges() throws Exception {
        MorphMap.morphMap(single("alphas", Alpha.class));
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean inconsistent = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(9);
        try {
            Future<?>[] readers = new Future<?>[8];
            for (int i = 0; i < readers.length; i++) {
                readers[i] = pool.submit(() -> {
                    while (!done.get()) {
                        if (!isConsistent()) {
                            inconsistent.set(true);
                        }
                    }
                });
            }
            Future<?> writer = pool.submit(() -> {
                for (int i = 0; i < 2000; i++) {
                    MorphMap.requireMorphMap(i % 2 == 0);
                    MorphMap.morphMap(single("betas", Beta.class));
                    MorphMap.resetForTests();
                    MorphMap.morphMap(single("alphas", Beta.class));
                    MorphMap.resetForTests();
                    MorphMap.morphMap(single("alphas", Alpha.class));
                }
                done.set(true);
            });
            writer.get(30, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(30, TimeUnit.SECONDS);
            }
        } finally {
            done.set(true);
            pool.shutdownNow();
        }

        assertFalse(inconsistent.get());
    }

    /** Checks the two maps of one snapshot agree: every alias maps to a class that maps back. */
    @SuppressWarnings("unchecked")
    private static boolean isConsistent() {
        Object[] maps = MorphMap.snapshotMapsForTests();
        Map<String, Class<? extends Model>> aliases = (Map<String, Class<? extends Model>>) maps[0];
        Map<Class<? extends Model>, String> classes = (Map<Class<? extends Model>, String>) maps[1];
        if (aliases.size() != classes.size()) {
            return false;
        }
        for (Map.Entry<String, Class<? extends Model>> entry : aliases.entrySet()) {
            if (!entry.getKey().equals(classes.get(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }
}
