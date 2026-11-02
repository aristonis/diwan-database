package org.aristonis.diwan.database.eloquent.casts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CastValuesTest {

    private static final String FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final Instant NOON = Instant.parse("2026-01-02T12:00:00Z");

    @Test
    void decimalReadsNumbersAndTextExactly() {
        assertEquals(new BigDecimal("0.1"), CastValues.decimal(0.1d));
        assertEquals(new BigDecimal("0.1"), CastValues.decimal(0.1f));
        assertEquals(new BigDecimal("7"), CastValues.decimal(7));
        assertEquals(new BigDecimal("12345678901234567890"),
                CastValues.decimal(new BigInteger("12345678901234567890")));
        assertEquals(new BigDecimal("1.50"), CastValues.decimal("1.50"));
    }

    @Test
    void decimalIsNullForNaNInfinityAndNonNumericText() {
        assertNull(CastValues.decimal(Double.NaN));
        assertNull(CastValues.decimal(Float.POSITIVE_INFINITY));
        assertNull(CastValues.decimal("abc"));
    }

    @Test
    void toLongReadsWholeNumbers() {
        assertEquals(5L, CastValues.toLong(5));
        assertEquals(5L, CastValues.toLong("5"));
        assertEquals(5L, CastValues.toLong(5.0d));
        assertEquals(5L, CastValues.toLong(new BigDecimal("5.00")));
        assertEquals(Long.MAX_VALUE, CastValues.toLong(BigInteger.valueOf(Long.MAX_VALUE)));
    }

    @Test
    void toLongRejectsFractionsOverflowAndOtherTypes() {
        assertThrows(IllegalArgumentException.class, () -> CastValues.toLong(1.5d));
        assertThrows(IllegalArgumentException.class, () -> CastValues.toLong("x"));
        assertThrows(IllegalArgumentException.class, () -> CastValues.toLong("99999999999999999999"));
        assertThrows(IllegalArgumentException.class,
                () -> CastValues.toLong(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class, () -> CastValues.toLong(Boolean.TRUE));
    }

    @Test
    void toLongMessageNamesOnlyTheType() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CastValues.toLong("secret"));

        assertEquals("Not a whole number: a String", e.getMessage());
    }

    @Test
    void toBigDecimalReadsNumbersAndRejectsTheRest() {
        assertEquals(new BigDecimal("2.5"), CastValues.toBigDecimal("2.5"));
        assertEquals(new BigDecimal("3"), CastValues.toBigDecimal(3L));
        assertThrows(IllegalArgumentException.class, () -> CastValues.toBigDecimal(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CastValues.toBigDecimal("x"));
        assertThrows(IllegalArgumentException.class, () -> CastValues.toBigDecimal(Boolean.TRUE));
    }

    @Test
    void toBooleanReadsPhpFlags() {
        assertTrue(CastValues.toBoolean(Boolean.TRUE));
        assertTrue(CastValues.toBoolean(2));
        assertTrue(CastValues.toBoolean("1"));
        assertTrue(CastValues.toBoolean("TRUE"));
        assertFalse(CastValues.toBoolean(0.0d));
        assertFalse(CastValues.toBoolean("0"));
        assertFalse(CastValues.toBoolean("False"));
        assertFalse(CastValues.toBoolean(""));
    }

    @Test
    void toBooleanRejectsOtherText() {
        assertThrows(IllegalArgumentException.class, () -> CastValues.toBoolean("yes"));
    }

    @Test
    void toInstantReadsEveryStoredForm() {
        assertEquals(NOON, CastValues.toInstant(LocalDateTime.of(2026, 1, 2, 12, 0), FORMAT));
        assertEquals(NOON, CastValues.toInstant(NOON, FORMAT));
        assertEquals(NOON,
                CastValues.toInstant(OffsetDateTime.of(2026, 1, 2, 14, 0, 0, 0, ZoneOffset.ofHours(2)), FORMAT));
        assertEquals(Instant.EPOCH.plusNanos(1_000L), CastValues.toInstant(1L, FORMAT));
        assertEquals(NOON, CastValues.toInstant("2026-01-02 12:00:00", FORMAT));
    }

    @Test
    void toInstantRejectsOtherTypes() {
        assertThrows(IllegalArgumentException.class, () -> CastValues.toInstant(1.5d, FORMAT));
    }

    @Test
    void parseInstantFallsBackToIsoForms() {
        assertEquals(NOON, CastValues.parseInstant("2026-01-02T14:00:00+02:00", FORMAT));
        assertEquals(NOON, CastValues.parseInstant("2026-01-02T12:00:00", FORMAT));
        assertEquals(NOON, CastValues.parseInstant("2026-01-02T12:00:00", "not a [pattern"));
    }

    @Test
    void parseInstantRejectsText() {
        assertThrows(IllegalArgumentException.class, () -> CastValues.parseInstant("tomorrow", FORMAT));
    }
}
