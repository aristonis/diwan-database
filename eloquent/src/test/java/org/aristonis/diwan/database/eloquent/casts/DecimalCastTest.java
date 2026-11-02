package org.aristonis.diwan.database.eloquent.casts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecimalCastTest {

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();
    private static final String KEY = "total";

    private final DecimalCast cast = new DecimalCast(2);

    @Test
    void compareAfterRounding() {
        assertEquals(new BigDecimal("1.00"), cast.get(null, KEY, "1.004", NO_ATTRIBUTES));
        assertEquals("1.00", cast.set(null, KEY, "1.001", NO_ATTRIBUTES));
        assertTrue(cast.compare(null, KEY, "1.004", "1.001"));
        assertTrue(cast.compare(null, KEY, "1.0", new BigDecimal("1.00")));
        assertFalse(cast.compare(null, KEY, "1.00", "1.01"));
        assertFalse(cast.compare(null, KEY, "1.00", "abc"));
    }

    @Test
    void neverBuildFromDouble() {
        assertEquals("1.01", cast.set(null, KEY, 1.005d, NO_ATTRIBUTES));
        assertEquals(new BigDecimal("1.01"), cast.get(null, KEY, 1.005d, NO_ATTRIBUTES));
    }

    @Test
    void roundsHalfUp() {
        assertEquals("2.35", cast.set(null, KEY, "2.345", NO_ATTRIBUTES));
        assertEquals("-2.35", cast.set(null, KEY, "-2.345", NO_ATTRIBUTES));
        assertEquals("3.00", cast.set(null, KEY, 3L, NO_ATTRIBUTES));
    }

    @Test
    void serializesPlainText() {
        assertEquals("12.50", cast.serialize(null, KEY, new BigDecimal("12.5"), NO_ATTRIBUTES));
        assertEquals("100.00", cast.serialize(null, KEY, new BigDecimal("1E+2"), NO_ATTRIBUTES));
    }

    @Test
    void scaleIsCappedAtThirty() {
        assertEquals(30, DecimalCast.MAX_SCALE);
        assertEquals(30, new DecimalCast(30).scale());
        assertEquals(0, new DecimalCast(0).scale());
        assertThrows(IllegalArgumentException.class, () -> new DecimalCast(31));
        assertThrows(IllegalArgumentException.class, () -> new DecimalCast(-1));
    }

    @Test
    void nameCarriesScale() {
        assertEquals("decimal:2", cast.name());
    }

    @Test
    void badDataThrows() {
        assertThrows(InvalidCastException.class, () -> cast.get(null, KEY, "abc", NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> cast.get(null, KEY, Double.NaN, NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> cast.set(null, KEY, new Object(), NO_ATTRIBUTES));
    }

    @Test
    void nullStaysNull() {
        assertFalse(cast.handlesNull());
        assertNull(cast.get(null, KEY, null, NO_ATTRIBUTES));
        assertNull(cast.set(null, KEY, null, NO_ATTRIBUTES));
        assertTrue(cast.compare(null, KEY, null, null));
        assertFalse(cast.compare(null, KEY, "1", null));
    }

    // Review fix 1: a tiny exponent must not expand into a billion-digit division.
    @Test
    void tinyExponentRoundsToZeroQuickly() {
        String tiny = "1e-999999999";

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(new BigDecimal("0.00"), cast.get(null, KEY, tiny, NO_ATTRIBUTES));
            assertEquals("0.00", cast.set(null, KEY, "-" + tiny, NO_ATTRIBUTES));
            assertTrue(cast.compare(null, KEY, tiny, "0"));
            assertThrows(InvalidCastException.class, () -> cast.get(null, KEY, "1e2147483647", NO_ATTRIBUTES));
            assertEquals(new BigDecimal("0.00"),
                    cast.get(null, KEY, new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE), NO_ATTRIBUTES));
        });
    }

    @Test
    void smallValueJustBelowTheScaleStillRoundsHalfUp() {
        assertEquals(new BigDecimal("0.01"), cast.get(null, KEY, "0.005", NO_ATTRIBUTES));
        assertEquals(new BigDecimal("0.00"), cast.get(null, KEY, "0.0049", NO_ATTRIBUTES));
        assertEquals(new BigDecimal("0.00"), cast.get(null, KEY, "5e-4", NO_ATTRIBUTES));
    }
}
