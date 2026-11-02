package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
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
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.junit.jupiter.api.Test;

class ModelAttributesTest {

    private static final Instant MOMENT = Instant.parse("2026-11-02T10:15:30Z");

    private static User stored(String key, Object value) {
        User user = new User();
        user.setRawAttributes(row(key, value), true);
        return user;
    }

    private static boolean dirtyAfter(Object original, Object changed) {
        User user = stored("v", original);
        user.setAttribute("v", changed);
        return user.isDirty("v");
    }

    @Test
    void numbersCompareByCanonicalTextAsLaravelDoes() {
        assertFalse(dirtyAfter(1, 1L));
        assertFalse(dirtyAfter(1L, "1"));
        assertFalse(dirtyAfter(1, 1.0));
        assertFalse(dirtyAfter(new BigDecimal("2.50"), 2.5));
        assertFalse(dirtyAfter(BigInteger.TEN, 10));
        assertFalse(dirtyAfter(0.1, new BigDecimal("0.1")));
        assertTrue(dirtyAfter("1.0", 1));
        assertTrue(dirtyAfter(1, 2));
        assertTrue(dirtyAfter("1", "01"));
        assertFalse(dirtyAfter(Double.NaN, Double.NaN));
        assertTrue(dirtyAfter(Double.NaN, Double.POSITIVE_INFINITY));
        assertTrue(dirtyAfter("abc", 1));
    }

    @Test
    void nullOnlyEqualsNull() {
        assertFalse(dirtyAfter(null, null));
        assertTrue(dirtyAfter(null, 0));
        assertTrue(dirtyAfter("", null));
    }

    @Test
    void booleansAreDirtyAgainstStoredNumbersUntilCasts() {
        assertTrue(dirtyAfter(1, true));
        assertFalse(dirtyAfter(true, true));
    }

    @Test
    void momentsAreComparedAsInstantsWhateverTheirStoredForm() {
        long micros = MOMENT.getEpochSecond() * 1_000_000L;
        assertFalse(dirtyAfter(micros, MOMENT));
        assertFalse(dirtyAfter(OffsetDateTime.ofInstant(MOMENT, ZoneOffset.ofHours(2)), MOMENT));
        assertFalse(dirtyAfter(LocalDateTime.of(2026, 11, 2, 10, 15, 30), "2026-11-02 10:15:30"));
        assertFalse(dirtyAfter("2026-11-02 10:15:30", MOMENT));
        assertTrue(dirtyAfter(MOMENT, MOMENT.plusSeconds(1)));
        assertTrue(dirtyAfter("not a date", MOMENT));
        assertTrue(dirtyAfter(true, MOMENT));
    }

    @Test
    void byteArraysCompareByContent() {
        assertFalse(dirtyAfter(new byte[] {1, 2}, new byte[] {1, 2}));
        assertTrue(dirtyAfter(new byte[] {1, 2}, new byte[] {2}));
    }

    @Test
    void aKeyMissingFromTheOriginalIsDirty() {
        User user = stored("name", "Ann");
        user.setAttribute("email", "a@x");

        assertTrue(user.isDirty());
        assertTrue(user.isDirty("email"));
        assertTrue(user.isDirty("name", "email"));
        assertFalse(user.isDirty("name"));
        assertTrue(user.isClean("name"));
        assertFalse(user.isClean());
        assertEquals(row("email", "a@x"), user.getDirty());
    }

    @Test
    void originalsChangesAndPreviousFollowTheSaveCycle() {
        User user = stored("name", "Ann");
        user.setAttribute("name", "Bob");

        assertEquals("Ann", user.getOriginal("name"));
        assertEquals("Ann", user.getRawOriginal("name"));
        assertEquals(row("name", "Ann"), user.getOriginal());
        user.syncChanges();
        user.syncOriginal();

        assertTrue(user.wasChanged());
        assertTrue(user.wasChanged("name"));
        assertFalse(user.wasChanged("email"));
        assertEquals(row("name", "Bob"), user.getChanges());
        assertEquals(row("name", "Ann"), user.getPrevious());
        assertFalse(user.isDirty());
    }

    @Test
    void discardChangesRestoresTheOriginal() {
        User user = stored("name", "Ann");
        user.setAttribute("name", "Bob");
        user.setAttribute("email", "b@x");

        user.discardChanges();

        assertEquals(row("name", "Ann"), user.getAttributes());
        assertTrue(user.getChanges().isEmpty());
        assertTrue(user.getPrevious().isEmpty());
    }

    @Test
    void syncOriginalAttributeSyncsOnlyThatKey() {
        User user = stored("name", "Ann");
        user.setAttribute("name", "Bob");
        user.setAttribute("email", "b@x");

        user.syncOriginalAttribute("name");

        assertFalse(user.isDirty("name"));
        assertTrue(user.isDirty("email"));
        user.syncOriginalAttributes("email");
        assertTrue(user.isClean());
    }

    @Test
    void attributeMapsAreCopiesAndReadOnly() {
        User user = stored("name", "Ann");

        assertThrows(UnsupportedOperationException.class, () -> user.getAttributes().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> user.getOriginal().put("x", 1));
        assertTrue(user.hasAttribute("name"));
        assertFalse(user.hasAttribute("email"));
        assertNull(user.getAttribute("email"));
    }

    @Test
    void setRawAttributesWithoutSyncLeavesEverythingDirty() {
        User user = new User();
        user.setRawAttributes(row("name", "Ann"));

        assertTrue(user.isDirty("name"));
        assertEquals(Collections.emptyMap(), user.getOriginal());
    }

    @Test
    void typedGettersConvertLeniently() {
        User user = new User();
        user.setRawAttributes(row("n", 5, "s", "42", "d", "2.5", "b", 1L, "t", "true", "f", "0", "big", 3.25,
                "none", null, "text", "x"));

        assertEquals("5", user.getString("n"));
        assertEquals(Long.valueOf(42), user.getLong("s"));
        assertEquals(Integer.valueOf(5), user.getInteger("n"));
        assertEquals(Double.valueOf(2.5), user.getDouble("d"));
        assertEquals(new BigDecimal("3.25"), user.getBigDecimal("big"));
        assertEquals(new BigDecimal("42"), user.getBigDecimal("s"));
        assertEquals(Boolean.TRUE, user.getBoolean("b"));
        assertEquals(Boolean.TRUE, user.getBoolean("t"));
        assertEquals(Boolean.FALSE, user.getBoolean("f"));
        assertNull(user.getString("none"));
        assertNull(user.getLong("none"));
        assertNull(user.getInteger("missing"));
        assertNull(user.getDouble("none"));
        assertNull(user.getBigDecimal("none"));
        assertNull(user.getBoolean("none"));
        assertNull(user.getInstant("none"));
        assertThrows(IllegalArgumentException.class, () -> user.getLong("text"));
        assertThrows(IllegalArgumentException.class, () -> user.getBoolean("text"));
        assertThrows(IllegalArgumentException.class, () -> user.getBigDecimal("text"));
        assertThrows(IllegalArgumentException.class, () -> user.getInstant("text"));
        assertThrows(IllegalArgumentException.class, () -> user.getLong("d"));
    }

    @Test
    void getIntegerRefusesValuesOutsideTheIntRange() {
        User user = new User();
        user.setRawAttributes(row("n", Long.MAX_VALUE));

        assertThrows(IllegalArgumentException.class, () -> user.getInteger("n"));
    }

    @Test
    void getInstantReadsEveryStoredForm() {
        long micros = MOMENT.getEpochSecond() * 1_000_000L + 5;
        User user = new User();
        user.setRawAttributes(row("a", MOMENT, "b", micros, "c", "2026-11-02 10:15:30",
                "d", "2026-11-02T10:15:30Z", "e", LocalDateTime.of(2026, 11, 2, 10, 15, 30),
                "f", OffsetDateTime.ofInstant(MOMENT, ZoneOffset.UTC), "g", "2026-11-02T10:15:30"));

        assertEquals(MOMENT, user.getInstant("a"));
        assertEquals(MOMENT.plusNanos(5000), user.getInstant("b"));
        assertEquals(MOMENT, user.getInstant("c"));
        assertEquals(MOMENT, user.getInstant("d"));
        assertEquals(MOMENT, user.getInstant("e"));
        assertEquals(MOMENT, user.getInstant("f"));
        assertEquals(MOMENT, user.getInstant("g"));
    }

    @Test
    void setAttributeReturnsTheModelForChaining() {
        User user = new User();

        assertTrue(user.setAttribute("a", 1).setAttribute("b", 2) == user);
        assertEquals(row("a", 1, "b", 2), user.getAttributes());
    }
}
