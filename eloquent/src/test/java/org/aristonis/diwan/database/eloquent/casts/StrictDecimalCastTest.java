package org.aristonis.diwan.database.eloquent.casts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.junit.jupiter.api.Test;

class StrictDecimalCastTest {

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();
    private static final String KEY = "total";

    private static String set(Cast<Object> cast, Object value) {
        return (String) cast.set(null, KEY, value, NO_ATTRIBUTES);
    }

    @Test
    void refusesExtraDecimalsByDefault() {
        Cast<Object> cast = Casts.decimal(12, 2);

        InvalidCastException e = assertThrows(InvalidCastException.class, () -> set(cast, "10.555"));

        assertTrue(e.getMessage().contains("[total]"));
        assertTrue(e.getMessage().contains("decimal:12,2"));
        assertTrue(e.getMessage().contains("more decimals than scale 2"));
        assertFalse(e.getMessage().contains("10.555"));
    }

    @Test
    void integerOverflowAlwaysRefused() {
        for (Cast<Object> cast : java.util.List.of(Casts.decimal(12, 2), Casts.decimal(12, 2, RoundingMode.HALF_UP))) {
            InvalidCastException e = assertThrows(InvalidCastException.class,
                    () -> set(cast, "12345678901.00"));
            assertTrue(e.getMessage().contains("more than 10 integer digits"));
            assertFalse(e.getMessage().contains("12345678901"));
        }
    }

    @Test
    void roundingModes() {
        assertEquals("10.56", set(Casts.decimal(12, 2, RoundingMode.HALF_UP), "10.555"));
        assertEquals("10.56", set(Casts.decimal(12, 2, RoundingMode.HALF_EVEN), "10.565"));
        assertEquals("10.55", set(Casts.decimal(12, 2, RoundingMode.DOWN), "10.559"));
        assertEquals("10.56", set(Casts.decimal(12, 2, RoundingMode.UP), "10.551"));
        assertEquals("-10.56", set(Casts.decimal(12, 2, RoundingMode.FLOOR), "-10.551"));
        assertEquals("-10.55", set(Casts.decimal(12, 2, RoundingMode.CEILING), "-10.559"));
    }

    @Test
    void roundingCarryOverflow() {
        Cast<Object> cast = Casts.decimal(12, 2, RoundingMode.HALF_UP);

        assertEquals("9999999999.99", set(cast, "9999999999.99"));
        InvalidCastException e = assertThrows(InvalidCastException.class, () -> set(cast, "9999999999.995"));
        assertTrue(e.getMessage().contains("more than 10 integer digits"));
    }

    @Test
    void padsAndScaleZero() {
        Cast<Object> money = Casts.decimal(12, 2);
        Cast<Object> whole = Casts.decimal(5, 0);

        assertEquals(new BigDecimal("10.50"), money.get(null, KEY, "10.5", NO_ATTRIBUTES));
        assertEquals("10.50", set(money, new BigDecimal("10.5")));
        assertEquals("10", set(whole, 10));
        assertThrows(InvalidCastException.class, () -> set(whole, "10.1"));
    }

    @Test
    void zeroFitsWhenPrecisionEqualsScale() {
        Cast<Object> fraction = Casts.decimal(2, 2);

        assertEquals("0.00", set(fraction, 0));
        assertEquals("0.00", set(fraction, "0.00"));
        assertEquals("0.00", set(fraction, new BigDecimal("0E+5")));
        assertEquals("0.99", set(fraction, "0.99"));
        assertThrows(InvalidCastException.class, () -> set(fraction, "1.00"));
    }

    @Test
    void badDeclaration() {
        assertThrows(IllegalArgumentException.class, () -> Casts.decimal(2, 3));
        assertThrows(IllegalArgumentException.class, () -> Casts.decimal(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Casts.decimal(1001, 2));
        assertThrows(IllegalArgumentException.class, () -> Casts.decimal(40, 31));
        assertThrows(IllegalArgumentException.class, () -> Casts.decimal(12, -1));
        assertThrows(IllegalArgumentException.class, () -> Casts.decimal(12, 2, null));
    }

    @Test
    void specStrings() {
        Cast<?> plain = CastSpecParser.parse("decimal:12,2");
        Cast<?> even = CastSpecParser.parse("decimal:12,2,HALF_EVEN");
        Cast<?> lower = CastSpecParser.parse("decimal:12, 2, half_even");
        Cast<?> legacy = CastSpecParser.parse("decimal:2");

        assertEquals("decimal:12,2", ((StrictDecimalCast) plain).name());
        assertEquals("decimal:12,2,HALF_EVEN", ((StrictDecimalCast) even).name());
        assertEquals("decimal:12,2,HALF_EVEN", ((StrictDecimalCast) lower).name());
        assertTrue(legacy instanceof DecimalCast);
        assertEquals("2.35", ((DecimalCast) legacy).set(null, KEY, "2.345", NO_ATTRIBUTES));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal:12,2,SIDEWAYS"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal:1,2,3,4"));
        assertThrows(IllegalArgumentException.class, () -> CastSpecParser.parse("decimal:a,b"));
    }

    @Test
    void refusesHugeExponentFast() {
        Cast<Object> cast = Casts.decimal(12, 2, RoundingMode.HALF_UP);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThrows(InvalidCastException.class, () -> set(cast, "1E999999999"));
            assertEquals("0.00", set(cast, "1E-999999999"));
        });
    }

    @Test
    void parsesModeInTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));

            Cast<?> cast = CastSpecParser.parse("decimal:12,2,half_up");

            assertEquals("decimal:12,2,HALF_UP", ((StrictDecimalCast) cast).name());
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void comparesByValue() {
        Cast<Object> cast = Casts.decimal(12, 2);
        CastComparator comparator = (CastComparator) cast;

        assertTrue(comparator.compare(null, KEY, "1.5", new BigDecimal("1.50")));
        assertFalse(comparator.compare(null, KEY, "1.50", "1.555"));
    }
}
