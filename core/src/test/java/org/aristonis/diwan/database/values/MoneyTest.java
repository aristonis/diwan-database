package org.aristonis.diwan.database.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void minorUnitsAreExactBothWays() {
        assertEquals(1010L, Money.toMinor(new BigDecimal("10.10"), 2));
        assertEquals(1010L, Money.toMinor(new BigDecimal("10.1"), 2));
        assertEquals(-5L, Money.toMinor(new BigDecimal("-0.05"), 2));
        assertEquals(new BigDecimal("10.10"), Money.fromMinor(1010, 2));
        assertEquals(new BigDecimal("12"), Money.fromMinor(12, 0));
    }

    @Test
    void minorUnitsNeverRound() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Money.toMinor(new BigDecimal("10.105"), 2));
        assertFalse(error.getMessage().contains("10.105"));
        assertThrows(IllegalArgumentException.class, () -> Money.toMinor(new BigDecimal("1E+30"), 2));
    }

    @Test
    void textHasOneSpellingPerNumber() {
        assertEquals("1000.000000", Money.toText(new BigDecimal("1E+3"), 6));
        assertEquals("1000.000000", Money.toText(new BigDecimal("1000.000"), 6));
        assertEquals("0.000000", Money.toText(new BigDecimal("-0.0"), 6));
        assertEquals("-12.500000", Money.toText(new BigDecimal("-12.5"), 6));
        assertEquals(new BigDecimal("1000.000000"), Money.fromText("1000.000000", 6));
    }

    @Test
    void nonCanonicalTextIsRefusedWithoutTheValue() {
        for (String bad : new String[] {"1000.00", "01.000000", "1e3", "+1.000000", "-0.000000", ".500000", "1,5",
            "١٢.000000", ""}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Money.fromText(bad, 6), bad);
            assertTrue(bad.isEmpty() || !error.getMessage().contains(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Money.toText(new BigDecimal("1.0000001"), 6));
        assertThrows(IllegalArgumentException.class, () -> Money.toMinor(new BigDecimal("1"), -1));
        assertThrows(IllegalArgumentException.class, () -> Money.toText(new BigDecimal("1E+1000000000"), 2));
    }
}
