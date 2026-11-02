package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.CastComparator;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.Test;

/**
 * Tests the dirty ladder with the built-in float and JSON casts from {@link Casts}, and small
 * class casts for the comparator and failure rules.
 */
class DirtyLadderTest {

    private static final String KEY = "value";

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();

    private final Model model = new TestModels.BlogPost();

    private boolean ladder(Object original, Object current, Cast<?> cast) {
        return DirtyLadder.equivalent(model, KEY, true, original, current, cast, NO_ATTRIBUTES);
    }

    @Test
    void absentOriginalIsDirty() {
        assertFalse(DirtyLadder.equivalent(model, KEY, false, null, null, null, NO_ATTRIBUTES));
        assertFalse(DirtyLadder.equivalent(model, KEY, false, "a", "a", Casts.floating(), NO_ATTRIBUTES));
    }

    @Test
    void identicalValuesAreClean() {
        assertTrue(ladder("a", "a", new ThrowingCast()));
        assertTrue(ladder(null, null, new ThrowingCast()));
        assertTrue(ladder(new byte[] {1, 2}, new byte[] {1, 2}, new ThrowingCast()));
    }

    @Test
    void nullCurrentIsDirty() {
        assertFalse(ladder("a", null, Casts.floating()));
    }

    @Test
    void uncastFallsBackToNumericEquivalence() {
        assertTrue(ladder("1", 1L, null));
        assertFalse(ladder("1", 2L, null));
    }

    @Test
    void comparatorWins() {
        Cast<?> alwaysEqual = new FixedComparator(true);
        Cast<?> neverEqual = new FixedComparator(false);

        assertTrue(ladder("x", "y", alwaysEqual));
        assertFalse(ladder("x", "x2", neverEqual));
    }

    @Test
    void classCastWithoutComparatorUsesEqualsOfGetValues() {
        Cast<String> trimming = new TrimCast();

        assertTrue(ladder(" a", "a ", trimming));
        assertFalse(ladder("a", "b", trimming));
        assertFalse(ladder(null, "a", trimming));
    }

    @Test
    void conversionFailureIsDirtyNotThrown() {
        assertFalse(ladder("a", "b", new ThrowingCast()));
        assertFalse(ladder("a", "b", new ThrowingComparator()));
    }

    @Test
    void jsonKeyOrderIsClean() {
        assertTrue(ladder("{\"a\":1,\"b\":2}", "{\"b\": 2, \"a\": 1}", Casts.json()));
    }

    @Test
    void jsonOneEqualsOnePointZero() {
        assertTrue(ladder("1", "1.0", Casts.json()));
    }

    @Test
    void jsonListOrderIsDirty() {
        assertFalse(ladder("[1,2]", "[2,1]", Casts.json()));
    }

    @Test
    void floatTinyChangeIsDirty() {
        assertFalse(ladder(1e-17, 5e-17, Casts.floating()));
    }

    @Test
    void floatNanAndInfinityAreClean() {
        assertTrue(ladder(Double.NaN, Double.NaN, Casts.floating()));
        assertTrue(ladder(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Casts.floating()));
        assertTrue(ladder("NaN", Double.NaN, Casts.floating()));
    }

    @Test
    void infiniteVsFiniteIsDirty() {
        assertFalse(ladder(Double.POSITIVE_INFINITY, Double.MAX_VALUE, Casts.floating()));
        assertFalse(ladder(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Casts.floating()));
    }

    @Test
    void floatLastUlpIsClean() {
        assertTrue(ladder(1e300, Math.nextUp(1e300), Casts.floating()));
    }

    @Test
    void floatNegativeZeroIsClean() {
        assertTrue(ladder(0.0, -0.0, Casts.floating()));
    }

    /** Base for test casts whose {@code get} returns the raw value and {@code set} stores it. */
    private abstract static class PassCast implements Cast<Object> {
        @Override
        public Object get(Model owner, String key, Object raw, Map<String, Object> attributes) {
            return raw;
        }

        @Override
        public Object set(Model owner, String key, Object value, Map<String, Object> attributes) {
            return value;
        }
    }

    /** A comparator that always answers the same. */
    private static final class FixedComparator extends PassCast implements CastComparator {
        private final boolean answer;

        FixedComparator(boolean answer) {
            this.answer = answer;
        }

        @Override
        public boolean compare(Model owner, String key, Object originalRaw, Object currentRaw) {
            return answer;
        }
    }

    /** A comparator that fails. */
    private static final class ThrowingComparator extends PassCast implements CastComparator {
        @Override
        public boolean compare(Model owner, String key, Object originalRaw, Object currentRaw) {
            throw new IllegalStateException("cannot compare");
        }
    }

    /** A class cast without comparator whose reads fail. */
    private static final class ThrowingCast extends PassCast {
        @Override
        public Object get(Model owner, String key, Object raw, Map<String, Object> attributes) {
            throw new IllegalArgumentException("cannot convert");
        }
    }

    /** A class cast without comparator that trims text. */
    private static final class TrimCast implements Cast<String> {
        @Override
        public String get(Model owner, String key, Object raw, Map<String, Object> attributes) {
            return raw.toString().trim();
        }

        @Override
        public Object set(Model owner, String key, String value, Map<String, Object> attributes) {
            return value;
        }
    }
}
