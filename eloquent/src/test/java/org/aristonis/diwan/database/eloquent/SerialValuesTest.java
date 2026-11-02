package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.BackedValue;
import org.junit.jupiter.api.Test;

class SerialValuesTest {

    private static final Instant MOMENT = Instant.parse("2026-11-02T10:15:30.123456789Z");
    private static final String MOMENT_TEXT = "2026-11-02T10:15:30.123456Z";

    enum Plain { ACTIVE }

    enum Backed implements BackedValue {
        PAID;

        @Override
        public Object value() {
            return "p";
        }
    }

    @Test
    void instantBecomesUtcMicros() {
        assertEquals(MOMENT_TEXT, SerialValues.toSerializable(MOMENT));
    }

    @Test
    void wholeSecondInstantKeepsSixZeroDigits() {
        assertEquals("2026-11-02T10:15:30.000000Z",
                SerialValues.toSerializable(Instant.parse("2026-11-02T10:15:30Z")));
    }

    @Test
    void offsetDateTimeIsConvertedToUtc() {
        OffsetDateTime value = MOMENT.atOffset(ZoneOffset.ofHours(3));

        assertEquals(MOMENT_TEXT, SerialValues.toSerializable(value));
    }

    @Test
    void zonedDateTimeIsConvertedToUtc() {
        ZonedDateTime value = MOMENT.atZone(ZoneId.of("Asia/Damascus"));

        assertEquals(MOMENT_TEXT, SerialValues.toSerializable(value));
    }

    @Test
    void localDateTimeIsReadAsUtc() {
        LocalDateTime value = LocalDateTime.ofInstant(MOMENT, ZoneOffset.UTC);

        assertEquals(MOMENT_TEXT, SerialValues.toSerializable(value));
    }

    @Test
    void localDateBecomesIsoDate() {
        assertEquals("2026-11-02", SerialValues.toSerializable(LocalDate.of(2026, 11, 2)));
    }

    @Test
    void utilDateBecomesUtcMillisPaddedToMicros() {
        Date value = new Date(Instant.parse("2026-11-02T10:15:30.123Z").toEpochMilli());

        assertEquals("2026-11-02T10:15:30.123000Z", SerialValues.toSerializable(value));
    }

    @Test
    void sqlTimestampKeepsMicros() {
        assertEquals(MOMENT_TEXT, SerialValues.toSerializable(Timestamp.from(MOMENT)));
    }

    @Test
    void sqlDateBecomesIsoDate() {
        java.sql.Date value = java.sql.Date.valueOf(LocalDate.of(2026, 11, 2));

        assertEquals("2026-11-02", SerialValues.toSerializable(value));
    }

    @Test
    void plainEnumBecomesItsName() {
        assertEquals("ACTIVE", SerialValues.toSerializable(Plain.ACTIVE));
    }

    @Test
    void backedEnumBecomesItsStoredValue() {
        assertEquals("p", SerialValues.toSerializable(Backed.PAID));
    }

    @Test
    void mapIsCopiedInOrderWithStringKeysAndConvertedValues() {
        Map<Object, Object> source = new LinkedHashMap<>();
        source.put(2, LocalDate.of(2026, 11, 2));
        source.put("a", Plain.ACTIVE);

        Object result = SerialValues.toSerializable(source);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("2", "2026-11-02");
        expected.put("a", "ACTIVE");
        assertEquals(expected, result);
        assertEquals(Arrays.asList("2", "a"), new ArrayList<>(((Map<?, ?>) result).keySet()));
        assertTrue(result instanceof LinkedHashMap);
    }

    @Test
    void listIsCopiedWithConvertedElements() {
        List<Object> source = new ArrayList<>(Arrays.asList(MOMENT, 1L, null));

        Object result = SerialValues.toSerializable(source);

        assertEquals(Arrays.asList(MOMENT_TEXT, 1L, null), result);
        assertNotSame(source, result);
    }

    @Test
    void otherCollectionBecomesList() {
        Object result = SerialValues.toSerializable(new LinkedHashSet<>(Arrays.asList("x", Backed.PAID)));

        assertEquals(Arrays.asList("x", "p"), result);
        assertTrue(result instanceof ArrayList);
    }

    @Test
    void nestedStructuresAreConvertedRecursively() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("at", MOMENT);
        List<Object> source = new ArrayList<>();
        source.add(inner);

        Object result = SerialValues.toSerializable(source);

        Map<String, Object> expectedInner = new LinkedHashMap<>();
        expectedInner.put("at", MOMENT_TEXT);
        assertEquals(Arrays.asList(expectedInner), result);
    }

    @Test
    void inputIsNeverMutated() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("at", MOMENT);
        List<Object> list = new ArrayList<>(Arrays.asList(LocalDate.of(2026, 11, 2), inner));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("list", list);

        Object result = SerialValues.toSerializable(source);

        assertSame(list, source.get("list"));
        assertEquals(LocalDate.of(2026, 11, 2), list.get(0));
        assertSame(inner, list.get(1));
        assertEquals(MOMENT, inner.get("at"));
        assertNotSame(list, ((Map<?, ?>) result).get("list"));
    }

    @Test
    void byteArrayIsReturnedAsIs() {
        byte[] bytes = {1, 2};

        assertSame(bytes, SerialValues.toSerializable(bytes));
    }

    @Test
    void otherValuesAreReturnedUnchanged() {
        BigDecimal decimal = new BigDecimal("12.50");

        assertSame(decimal, SerialValues.toSerializable(decimal));
        assertEquals("text", SerialValues.toSerializable("text"));
        assertEquals(Boolean.TRUE, SerialValues.toSerializable(Boolean.TRUE));
        assertNull(SerialValues.toSerializable(null));
    }
}
