package org.aristonis.diwan.database.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MoneyAmountTest {

    @Test
    void everyStoredFormReadsAsTheSameAmount() {
        MoneyAmount fromMinor = MoneyAmount.read(1010L, 2);
        MoneyAmount fromText = MoneyAmount.read("10.10", 2);
        MoneyAmount fromNumeric = MoneyAmount.read(new BigDecimal("10.1"), 2);

        assertEquals(fromMinor, fromText);
        assertEquals(fromMinor, fromNumeric);
        assertEquals(new BigDecimal("10.10"), fromMinor.amount());
        assertEquals(1010L, fromText.toMinor());
        assertEquals("10.10", fromNumeric.toText());
        assertEquals("10.10", fromNumeric.toString());
        assertEquals(fromMinor.hashCode(), fromText.hashCode());
        assertNotEquals(fromMinor, MoneyAmount.read(1010L, 3));
    }

    @Test
    void wrongFormsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.read(10.1, 2));
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.read("10.1", 2));
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.of(new BigDecimal("1.005"), 2));
        assertThrows(NullPointerException.class, () -> MoneyAmount.read(null, 2));
    }

    @Test
    void instantsRoundTripThroughMicroseconds() {
        Instant moment = Instant.parse("2026-11-01T10:15:30.123456Z");

        assertEquals(moment, Instants.read(Instants.toMicros(moment)));
        assertEquals(moment, Instants.read(OffsetDateTime.ofInstant(moment, ZoneOffset.ofHours(3))));
        assertEquals(moment, Instants.read(moment));
        assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), Instants.read(-1L));
        assertThrows(IllegalArgumentException.class, () -> Instants.toMicros(Instant.parse("2026-11-01T00:00:00.0000001Z")));
        assertThrows(IllegalArgumentException.class, () -> Instants.toMicros(Instant.MAX));
        assertThrows(IllegalArgumentException.class, () -> Instants.read("2026"));
    }
}
