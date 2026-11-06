package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.TimestampFormat;
import org.junit.jupiter.api.Test;

class DateCastsTest {

    private static final Map<String, Object> NO_ATTRIBUTES = Collections.emptyMap();
    private static final Instant TEN_UTC = Instant.parse("2024-03-01T10:00:00Z");

    /** A model with the default text storage form. */
    static final class TextModel extends Model {
    }

    /** A model that stores moments as {@code Instant}. */
    static final class InstantModel extends Model {
        @Override
        protected TimestampFormat timestampFormat() {
            return TimestampFormat.INSTANT;
        }
    }

    private final Model text = new TextModel();
    private final Model instant = new InstantModel();


    @Test
    void sameWallClockOtherZoneIsDirty() {
        MomentCast cast = new MomentCast(false);
        Object original = cast.set(text, "at", TEN_UTC, NO_ATTRIBUTES);
        Object current = cast.set(text, "at",
                ZonedDateTime.of(2024, 3, 1, 10, 0, 0, 0, ZoneId.of("Asia/Dubai")), NO_ATTRIBUTES);

        assertEquals("2024-03-01 06:00:00", current);
        assertFalse(cast.compare(text, "at", original, current));
    }

    @Test
    void sameInstantOtherOffsetIsClean() {
        MomentCast cast = new MomentCast(false);
        Object original = cast.set(instant, "at", TEN_UTC, NO_ATTRIBUTES);
        Object current = OffsetDateTime.of(2024, 3, 1, 14, 0, 0, 0, ZoneOffset.ofHours(4));

        assertTrue(cast.compare(instant, "at", original, current));
        assertTrue(cast.compare(text, "at", "2024-03-01 10:00:00", current));
    }

    @Test
    void subSecondChangeIsDirtyWithInstantStorage() {
        MomentCast cast = new MomentCast(false);
        Object original = cast.set(instant, "at", TEN_UTC, NO_ATTRIBUTES);
        Object current = cast.set(instant, "at", TEN_UTC.plusNanos(1_000), NO_ATTRIBUTES);

        assertFalse(cast.compare(instant, "at", original, current));
    }

    @Test
    void timeOfDayOnDateIsClean() {
        DateCast cast = new DateCast();

        assertTrue(cast.compare(text, "on", "2024-01-01 00:00:00", "2024-01-01 15:00:00"));
        assertFalse(cast.compare(text, "on", "2024-01-01 00:00:00", "2024-01-02 00:00:00"));
    }

    @Test
    void customFormatStoresStorageForm() {
        MomentCast cast = new MomentCast("yyyy-MM-dd");

        Object stored = cast.set(text, "at", TEN_UTC, NO_ATTRIBUTES);

        assertEquals("2024-03-01 10:00:00", stored);
        assertEquals("2024-03-01", cast.serialize(text, "at", cast.get(text, "at", stored, NO_ATTRIBUTES),
                NO_ATTRIBUTES));
    }

    @Test
    void timestampRoundTripsSeconds() {
        MomentCast cast = new MomentCast(true);
        long seconds = TEN_UTC.getEpochSecond();

        Object stored = cast.set(text, "at", seconds, NO_ATTRIBUTES);

        assertEquals("2024-03-01 10:00:00", stored);
        assertEquals(seconds, cast.get(text, "at", stored, NO_ATTRIBUTES));
        assertEquals(seconds, cast.serialize(text, "at", seconds, NO_ATTRIBUTES));
    }

    // ---- accepted inputs ----

    @Test
    void datetimeAcceptsEveryMomentType() {
        MomentCast cast = new MomentCast(false);
        Object[] inputs = {
            TEN_UTC,
            OffsetDateTime.of(2024, 3, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2)),
            ZonedDateTime.of(2024, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC),
            LocalDateTime.of(2024, 3, 1, 10, 0),
            Date.from(TEN_UTC),
            "2024-03-01 10:00:00",
            "2024-03-01T14:00:00+04:00",
        };
        for (Object input : inputs) {
            assertEquals("2024-03-01 10:00:00", cast.set(text, "at", input, NO_ATTRIBUTES), input.getClass().getName());
        }
        assertEquals("2024-03-01 00:00:00", cast.set(text, "at", LocalDate.of(2024, 3, 1), NO_ATTRIBUTES));
    }

    @Test
    void datetimeSetLongIsAmbiguous() {
        MomentCast cast = new MomentCast(false);

        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> cast.set(text, "at", 1_700_000_000L, NO_ATTRIBUTES));
        assertEquals("datetime", e.getCastName());
        assertEquals("at", e.getKey());
        assertEquals(TextModel.class, e.getModelClass());
    }

    @Test
    void rawLongIsMicroseconds() {
        MomentCast datetime = new MomentCast(false);
        MomentCast timestamp = new MomentCast(true);
        long micros = TEN_UTC.getEpochSecond() * 1_000_000L + 5;

        assertEquals(TEN_UTC.plusNanos(5_000), datetime.get(text, "at", micros, NO_ATTRIBUTES));
        assertEquals(TEN_UTC.getEpochSecond(), timestamp.get(text, "at", micros, NO_ATTRIBUTES));
        assertEquals(LocalDate.of(2024, 3, 1), new DateCast().get(text, "on", micros, NO_ATTRIBUTES));
    }

    @Test
    void instantStorageTruncatesToMicros() {
        MomentCast cast = new MomentCast(false);

        Object stored = cast.set(instant, "at", TEN_UTC.plusNanos(1_999), NO_ATTRIBUTES);

        assertEquals(TEN_UTC.plusNanos(1_000), stored);
    }

    @Test
    void datetimeReadsStorageForms() {
        MomentCast cast = new MomentCast(false);

        assertEquals(TEN_UTC, cast.get(text, "at", "2024-03-01 10:00:00", NO_ATTRIBUTES));
        assertEquals(TEN_UTC, cast.get(instant, "at", TEN_UTC, NO_ATTRIBUTES));
        assertEquals(TEN_UTC, cast.get(text, "at", LocalDateTime.of(2024, 3, 1, 10, 0), NO_ATTRIBUTES));
    }

    @Test
    void badStoredDataFails() {
        MomentCast cast = new MomentCast(false);

        assertThrows(InvalidCastException.class, () -> cast.get(text, "at", "not a date", NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> cast.get(text, "at", 1.5d, NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> new DateCast().get(text, "on", "nope", NO_ATTRIBUTES));
        assertThrows(InvalidCastException.class, () -> cast.set(text, "at", new Object(), NO_ATTRIBUTES));
    }

    @Test
    void nullsCompare() {
        MomentCast cast = new MomentCast(false);

        assertTrue(cast.compare(text, "at", null, null));
        assertFalse(cast.compare(text, "at", null, "2024-03-01 10:00:00"));
        assertFalse(new DateCast().compare(text, "on", "2024-03-01", null));
    }

    @Test
    void datetimeSerializesWithMicros() {
        MomentCast cast = new MomentCast(false);

        assertEquals("2024-03-01T10:00:00.000005Z",
                cast.serialize(text, "at", TEN_UTC.plusNanos(5_000), NO_ATTRIBUTES));
        assertNull(cast.serialize(text, "at", null, NO_ATTRIBUTES));
    }

    // ---- date ----

    @Test
    void dateWritesIsoDateInEveryStorageForm() {
        DateCast cast = new DateCast();

        assertEquals("2024-03-01", cast.set(text, "on", LocalDate.of(2024, 3, 1), NO_ATTRIBUTES));
        assertEquals("2024-03-01", cast.set(instant, "on", TEN_UTC, NO_ATTRIBUTES));
        assertEquals("2024-03-01", cast.set(text, "on", "2024-03-01 23:59:59", NO_ATTRIBUTES));
        assertEquals("2024-03-01", cast.set(text, "on", LocalDateTime.of(2024, 3, 1, 15, 0), NO_ATTRIBUTES));
    }

    @Test
    void dateSetLongFails() {
        assertThrows(InvalidCastException.class, () -> new DateCast().set(text, "on", 20240301L, NO_ATTRIBUTES));
    }

    @Test
    void dateReadsTextAndDates() {
        DateCast cast = new DateCast();

        assertEquals(LocalDate.of(2024, 3, 1), cast.get(text, "on", "2024-03-01", NO_ATTRIBUTES));
        assertEquals(LocalDate.of(2024, 3, 1), cast.get(text, "on", "2024-03-01 15:00:00", NO_ATTRIBUTES));
        assertEquals(LocalDate.of(2024, 3, 1), cast.get(text, "on", LocalDate.of(2024, 3, 1), NO_ATTRIBUTES));
    }

    @Test
    void dateSerializesIsoOrCustomFormat() {
        LocalDate day = LocalDate.of(2024, 3, 1);

        assertEquals("2024-03-01", new DateCast().serialize(text, "on", day, NO_ATTRIBUTES));
        assertEquals("01/03/2024", new DateCast("dd/MM/uuuu").serialize(text, "on", day, NO_ATTRIBUTES));
    }

    @Test
    void customFormatIsJavaPattern() {
        // "Y-m-d" is a PHP pattern; in Java "m" is minute-of-hour, so the output differs.
        MomentCast cast = new MomentCast("HH:mm");

        assertEquals("10:00", cast.serialize(text, "at", TEN_UTC, NO_ATTRIBUTES));
        assertThrows(IllegalArgumentException.class, () -> new MomentCast("{{"));
    }
}
