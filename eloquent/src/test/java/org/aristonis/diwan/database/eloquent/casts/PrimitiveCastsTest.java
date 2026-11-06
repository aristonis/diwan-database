package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises the primitive casts directly, with a null model.
 *
 * <p>The model-level behaviour lives in {@code ModelCastsTest}; here only the cast objects are
 * checked.
 */
class PrimitiveCastsTest {

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();
    private static final String KEY = "n";

    private final PrimitiveCasts.IntCast intCast = new PrimitiveCasts.IntCast("int");
    private final PrimitiveCasts.FloatCast floatCast = new PrimitiveCasts.FloatCast("float");
    private final PrimitiveCasts.StringCast stringCast = new PrimitiveCasts.StringCast("string");
    private final PrimitiveCasts.BoolCast boolCast = new PrimitiveCasts.BoolCast("bool");

    @Test
    void intTruncatesLikeLaravel() {
        assertEquals(1L, intCast.get(null, KEY, "1.9", NO_ATTRIBUTES));
        assertEquals(-1L, intCast.get(null, KEY, "-1.9", NO_ATTRIBUTES));
        assertEquals(1L, intCast.get(null, KEY, 1.9d, NO_ATTRIBUTES));
        assertEquals(7L, intCast.get(null, KEY, 7, NO_ATTRIBUTES));
        assertEquals(Long.MAX_VALUE,
                intCast.get(null, KEY, BigInteger.valueOf(Long.MAX_VALUE), NO_ATTRIBUTES));
        assertTrue(intCast.compare(null, KEY, "1", "1.9"));
        assertFalse(intCast.compare(null, KEY, "1", "2"));
    }

    @Test
    void intSetStoresLong() {
        assertEquals(1L, intCast.set(null, KEY, "1.9", NO_ATTRIBUTES));
        assertEquals(5L, intCast.set(null, KEY, 5, NO_ATTRIBUTES));
        assertEquals(5L, intCast.serialize(null, KEY, 5L, NO_ATTRIBUTES));
    }

    @Test
    void intGarbageThrows() {
        assertThrows(InvalidCastException.class, () -> intCast.get(null, KEY, "abc", NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class,
                () -> intCast.get(null, KEY, "1234567890123456789012345", NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> intCast.get(null, KEY, "1e999999999", NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> intCast.get(null, KEY, Double.NaN, NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class,
                () -> intCast.set(null, KEY, new Object(), NO_ATTRIBUTES));
        assertFalse(intCast.compare(null, KEY, "abc", "abc2"));
    }

    @Test
    void errorMessageNamesTypesNotValues() {
        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> intCast.get(null, KEY, "secret-value", NO_ATTRIBUTES));

        assertFalse(e.getMessage().contains("secret-value"));
        assertEquals(KEY, e.getKey());
        assertEquals("int", e.getCastName());
        assertNull(e.getModelClass());
    }

    @Test
    void boolForms() {
        assertEquals(Boolean.FALSE, boolCast.get(null, KEY, "false", NO_ATTRIBUTES));
        assertEquals(Boolean.FALSE, boolCast.get(null, KEY, 0, NO_ATTRIBUTES));
        assertEquals(Boolean.FALSE, boolCast.get(null, KEY, "0", NO_ATTRIBUTES));
        assertEquals(Boolean.FALSE, boolCast.get(null, KEY, "", NO_ATTRIBUTES));
        assertEquals(Boolean.TRUE, boolCast.get(null, KEY, 1, NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> boolCast.get(null, KEY, "yes", NO_ATTRIBUTES));
        assertEquals(Boolean.TRUE, boolCast.set(null, KEY, "1", NO_ATTRIBUTES));
        assertTrue(boolCast.compare(null, KEY, 1, "true"));
        assertFalse(boolCast.compare(null, KEY, 1, 0));
        assertFalse(boolCast.compare(null, KEY, 1, "yes"));
    }

    @Test
    void nullStaysNull() {
        assertFalse(intCast.handlesNull());
        assertFalse(floatCast.handlesNull());
        assertFalse(stringCast.handlesNull());
        assertFalse(boolCast.handlesNull());
        assertNull(intCast.get(null, KEY, null, NO_ATTRIBUTES));
        assertNull(floatCast.get(null, KEY, null, NO_ATTRIBUTES));
        assertNull(stringCast.get(null, KEY, null, NO_ATTRIBUTES));
        assertNull(boolCast.get(null, KEY, null, NO_ATTRIBUTES));
        assertNull(intCast.set(null, KEY, null, NO_ATTRIBUTES));
        assertNull(floatCast.set(null, KEY, null, NO_ATTRIBUTES));
        assertNull(stringCast.set(null, KEY, null, NO_ATTRIBUTES));
        assertNull(boolCast.set(null, KEY, null, NO_ATTRIBUTES));
        assertTrue(intCast.compare(null, KEY, null, null));
        assertFalse(intCast.compare(null, KEY, null, 1));
    }

    @Test
    void floatReadsNumbersTextAndIeeeWords() {
        assertEquals(1.5d, floatCast.get(null, KEY, "1.5", NO_ATTRIBUTES));
        assertEquals(2.0d, floatCast.get(null, KEY, 2, NO_ATTRIBUTES));
        assertEquals(Double.POSITIVE_INFINITY, floatCast.get(null, KEY, "Infinity", NO_ATTRIBUTES));
        assertEquals(Double.NEGATIVE_INFINITY, floatCast.get(null, KEY, "-Infinity", NO_ATTRIBUTES));
        assertTrue(Double.isNaN((Double) floatCast.get(null, KEY, "NaN", NO_ATTRIBUTES)));
        assertThrows(InvalidCastException.class, () -> floatCast.get(null, KEY, "abc", NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> floatCast.get(null, KEY, "0x1p3", NO_ATTRIBUTES));
        assertEquals(1.5d, floatCast.set(null, KEY, "1.5", NO_ATTRIBUTES));
    }

    @Test
    void floatComparesWithinFourUlp() {
        double a = 0.1d + 0.2d;
        assertTrue(floatCast.compare(null, KEY, a, 0.3d));
        assertTrue(floatCast.compare(null, KEY, Double.NaN, "NaN"));
        assertTrue(floatCast.compare(null, KEY, 0.0d, -0.0d));
        assertTrue(floatCast.compare(null, KEY, Double.POSITIVE_INFINITY, "Infinity"));
        assertFalse(floatCast.compare(null, KEY, Double.POSITIVE_INFINITY, Double.MAX_VALUE));
        assertFalse(floatCast.compare(null, KEY, Double.NaN, 1.0d));
        assertFalse(floatCast.compare(null, KEY, 1e-17d, 5e-17d));
        assertFalse(floatCast.compare(null, KEY, 1.0d, Math.nextUp(1.0d) + 8 * Math.ulp(1.0d)));
        assertFalse(floatCast.compare(null, KEY, 1.0d, "abc"));
    }

    @Test
    void stringReadsText() {
        assertEquals("abc", stringCast.get(null, KEY, "abc", NO_ATTRIBUTES));
        assertEquals("12", stringCast.get(null, KEY, 12L, NO_ATTRIBUTES));
        assertEquals("12.50", stringCast.get(null, KEY, new BigDecimal("12.50"), NO_ATTRIBUTES));
        assertEquals("12", stringCast.set(null, KEY, 12, NO_ATTRIBUTES));
        assertTrue(stringCast.compare(null, KEY, 12L, "12"));
        assertThrows(InvalidCastException.class,
                () -> stringCast.get(null, KEY, new Object(), NO_ATTRIBUTES));
    }

    @Test
    void originalCastVsRaw() {
        String raw = "12.50";
        Object cast = new DecimalCast(2).get(null, "total", raw, NO_ATTRIBUTES);

        assertEquals(new BigDecimal("12.50"), cast);
        assertEquals("12.50", raw);
    }

    @Test
    void castOnUnknownKeyApplies() {
        assertEquals(3L, intCast.set(null, "not_a_column", "3", NO_ATTRIBUTES));
    }

    @Test
    void incrementUsesRaw() {
        assertEquals(6L, intCast.get(null, KEY, 6L, NO_ATTRIBUTES));
    }

    @Test
    void typedGettersSeeCasts() {
        Object value = new DecimalCast(2).get(null, "total", "12.5", NO_ATTRIBUTES);

        assertEquals("12.50", ((BigDecimal) value).toPlainString());
    }

    @Test
    void castNameIsKept() {
        PrimitiveCasts.IntCast alias = new PrimitiveCasts.IntCast("integer");
        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> alias.get(null, KEY, "x", NO_ATTRIBUTES));

        assertEquals("integer", e.getCastName());
        assertSame("integer", alias.name());
    }

    // Review fix 1: a tiny exponent must not expand into a billion-digit division.
    @Test
    void intTinyExponentReadsAsZeroQuickly() {
        String tiny = "1e-999999999";

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(0L, intCast.get(null, KEY, tiny, NO_ATTRIBUTES));
            assertEquals(0L, intCast.set(null, KEY, "-" + tiny, NO_ATTRIBUTES));
            assertTrue(intCast.compare(null, KEY, tiny, "0"));
            assertThrows(InvalidCastException.class, () -> intCast.get(null, KEY, "1e2147483647", NO_ATTRIBUTES));
            assertEquals(0L, intCast.get(null, KEY, new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE), NO_ATTRIBUTES));
        });
    }

    @Test
    void stringReadsBooleansAsPhpDoes() {
        assertEquals("1", stringCast.get(null, KEY, true, NO_ATTRIBUTES));
        assertEquals("", stringCast.get(null, KEY, false, NO_ATTRIBUTES));
        assertEquals("", stringCast.set(null, KEY, Boolean.FALSE, NO_ATTRIBUTES));
    }

    @Test
    void stringReadsWholeDoublesAsIntegerText() {
        assertEquals("1", stringCast.get(null, KEY, 1.0d, NO_ATTRIBUTES));
        assertEquals("-2", stringCast.get(null, KEY, -2.0d, NO_ATTRIBUTES));
        assertEquals("1.5", stringCast.get(null, KEY, 1.5d, NO_ATTRIBUTES));
        assertEquals("1.0E20", stringCast.get(null, KEY, 1e20d, NO_ATTRIBUTES));
        assertEquals("Infinity", stringCast.get(null, KEY, Double.POSITIVE_INFINITY, NO_ATTRIBUTES));
    }

    @Test
    void stringReadsWholeFloatsAsIntegerText() {
        assertEquals("2", stringCast.get(null, KEY, 2.0f, NO_ATTRIBUTES));
        assertEquals("0.5", stringCast.get(null, KEY, 0.5f, NO_ATTRIBUTES));
    }

    @Test
    void stringReadsNegativeZeroWithItsSign() {
        assertEquals("-0", stringCast.get(null, KEY, -0.0d, NO_ATTRIBUTES));
        assertEquals("-0", stringCast.get(null, KEY, -0.0f, NO_ATTRIBUTES));
    }

    @Test
    void stringReadsNotANumberAsJavaText() {
        assertEquals("NaN", stringCast.get(null, KEY, Double.NaN, NO_ATTRIBUTES));
    }

    @Test
    void stringReadsLargeAndTinyFractionsInJavaExponentForm() {
        assertEquals("1.23456785E7", stringCast.get(null, KEY, 12345678.5d, NO_ATTRIBUTES));
        assertEquals("1.0E-4", stringCast.get(null, KEY, 0.0001d, NO_ATTRIBUTES));
    }

    @Test
    void stringComparesBooleanTrueWithStoredOne() {
        assertTrue(stringCast.compare(null, KEY, "1", true));
        assertFalse(stringCast.compare(null, KEY, "1", false));
    }

    // Review fix 4: float reads a Boolean as int does.
    @Test
    void floatAcceptsBooleanAsOneOrZero() {
        assertEquals(1.0d, floatCast.get(null, KEY, Boolean.TRUE, NO_ATTRIBUTES));
        assertEquals(0.0d, floatCast.set(null, KEY, Boolean.FALSE, NO_ATTRIBUTES));
        assertTrue(floatCast.compare(null, KEY, Boolean.TRUE, "1"));
    }
}
