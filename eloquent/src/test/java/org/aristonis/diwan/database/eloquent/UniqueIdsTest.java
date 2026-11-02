package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.Test;

class UniqueIdsTest {

    /** Random bits that are all zero or all one. */
    private static Random constant(byte fill) {
        return new Random() {
            private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] bytes) {
                java.util.Arrays.fill(bytes, fill);
            }
        };
    }

    @Test
    void uuid7PutsTheMillisecondsFirstThenVersionAndVariant() {
        UniqueIds ids = new UniqueIds(() -> 1L, constant((byte) 0));

        assertEquals("00000000-0001-7000-8000-000000000000", ids.uuid7());
        UniqueIds ones = new UniqueIds(() -> 0x0123456789ABL, constant((byte) -1));
        assertEquals("01234567-89ab-7fff-bfff-ffffffffffff", ones.uuid7());
    }

    @Test
    void ulidIsLowerCaseCrockfordWithTheTimeFirst() {
        UniqueIds ids = new UniqueIds(() -> 1L, constant((byte) 0));

        assertEquals("00000000010000000000000000", ids.ulid());
        UniqueIds ones = new UniqueIds(() -> 0xFFFFFFFFFFFFL, constant((byte) -1));
        assertEquals("7zzzzzzzzzzzzzzzzzzzzzzzzz", ones.ulid());
    }

    @Test
    void theStandardGeneratorGivesDistinctWellFormedIds() {
        UniqueIds ids = UniqueIds.standard();

        String uuid = ids.uuid7();
        String ulid = ids.ulid();
        assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), uuid);
        assertTrue(ulid.matches("[0-9a-hjkmnp-tv-z]{26}"), ulid);
        assertNotEquals(uuid, ids.uuid7());
        assertNotEquals(ulid, ids.ulid());
    }

    @Test
    void modelClockTruncatesToMicroseconds() {
        Instant fine = Instant.parse("2026-11-02T10:15:30.123456789Z");

        assertEquals(Instant.parse("2026-11-02T10:15:30.123456Z"),
                ModelClock.of(Clock.fixed(fine, ZoneOffset.UTC)).now());
        assertEquals(Instant.parse("2026-11-02T10:15:30.123456Z"), ModelClock.fixed(fine).now());
        assertEquals(0, ModelClock.system().now().getNano() % 1000);
    }
}
