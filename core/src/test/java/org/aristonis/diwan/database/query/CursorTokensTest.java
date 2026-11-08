package org.aristonis.diwan.database.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorTokensTest {

    private static String token(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everySupportedTypeRoundTrips() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("name\twith\nodd=chars %", "text \t\n% é");
        parameters.put("i", 42L);
        parameters.put("small", 7);
        parameters.put("n", new BigDecimal("12.3400"));
        parameters.put("d", 1.5d);
        parameters.put("b", true);
        parameters.put("z", null);
        parameters.put("t", Instant.parse("2026-01-02T03:04:05.123456789Z"));
        parameters.put("l", LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        parameters.put("o", OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(3)));
        parameters.put("u", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        parameters.put("ts", Timestamp.from(Instant.parse("2026-01-02T03:04:05Z")));
        Cursor cursor = new Cursor(parameters, false);

        String encoded = cursor.encode();
        Cursor decoded = Cursor.decode(encoded);

        assertFalse(encoded.contains("="));
        assertFalse(decoded.pointsToNextItems());
        assertTrue(decoded.pointsToPreviousItems());
        assertEquals(42L, decoded.parameter("i"));
        assertEquals(7L, decoded.parameter("small"));
        assertEquals(new BigDecimal("12.3400"), decoded.parameter("n"));
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), decoded.parameter("ts"));
        assertEquals("text \t\n% é", decoded.parameter("name\twith\nodd=chars %"));
        assertEquals(null, decoded.parameter("z"));
        assertEquals(cursor, decoded);
        assertEquals(cursor.hashCode(), decoded.hashCode());
        assertEquals(encoded, decoded.encode());
    }

    @Test
    void anUnsupportedTypeIsRefusedWhenTheCursorIsBuilt() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("span", Duration.ofMinutes(5));

        assertThrows(IllegalArgumentException.class, () -> new Cursor(parameters, true));
    }

    @Test
    void dateAndTimeRoundTrip() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("day", LocalDate.of(2026, 1, 2));
        parameters.put("at", LocalTime.of(3, 4, 5, 6_000_000));
        parameters.put("sqlDay", java.sql.Date.valueOf("2026-03-04"));
        parameters.put("sqlAt", java.sql.Time.valueOf("23:59:58"));
        Cursor cursor = new Cursor(parameters, true);

        Cursor decoded = Cursor.decode(cursor.encode());

        assertEquals(LocalDate.of(2026, 1, 2), decoded.parameter("day"));
        assertEquals(LocalTime.of(3, 4, 5, 6_000_000), decoded.parameter("at"));
        assertEquals(LocalDate.of(2026, 3, 4), decoded.parameter("sqlDay"));
        assertEquals(LocalTime.of(23, 59, 58), decoded.parameter("sqlAt"));
        assertEquals(cursor, decoded);
        assertEquals(cursor.encode(), decoded.encode());
    }

    @Test
    void oldTokensStillDecode() {
        String oldToken = token("d=prev\nid\ti\t42\nname\ts\tann\nat\tt\t2026-01-02T03:04:05Z"
                + "\nlocal\tl\t2026-01-02T03:04:05\nid2\tu\t123e4567-e89b-12d3-a456-426614174000");

        Cursor decoded = Cursor.decode(oldToken);

        assertTrue(decoded.pointsToPreviousItems());
        assertEquals(42L, decoded.parameter("id"));
        assertEquals("ann", decoded.parameter("name"));
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), decoded.parameter("at"));
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), decoded.parameter("local"));
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), decoded.parameter("id2"));
    }

    @Test
    void badDateTextIsMalformed() {
        String[] bad = {
            token("d=next\nday\ta\t2026-02-30"),
            token("d=next\nday\ta\tyesterday"),
            token("d=next\nday\ta\t2026-01-02T03:04"),
            token("d=next\nday\ta\t"),
            token("d=next\nat\tc\t25:00"),
            token("d=next\nat\tc\t03:04:05:06"),
        };
        for (String token : bad) {
            assertThrows(IllegalArgumentException.class, () -> Cursor.decode(token), token);
        }
    }

    @Test
    void parametersAreACopyAndAMissingOneIsRefused() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("id", 1L);
        Cursor cursor = new Cursor(parameters, true);
        parameters.put("id", 2L);

        assertEquals(1L, cursor.parameter("id"));
        assertThrows(UnsupportedOperationException.class, () -> cursor.parameters().put("x", 1));
        assertThrows(IllegalArgumentException.class, () -> cursor.parameter("missing"));
        assertNotEquals(cursor, new Cursor(cursor.parameters(), false));
        assertTrue(cursor.toString().contains("id"));
    }

    @Test
    void malformedTokensAreRefused() {
        String[] bad = {
            "",
            "not base64 !",
            token(""),
            token("d=sideways\nid\ti\t1"),
            token("id\ti\t1"),
            token("d=next"),
            token("d=next\nid\tq\t1"),
            token("d=next\nid\ti"),
            token("d=next\nid\ti\tone"),
            token("d=next\nid\ti\t1\tx"),
            token("d=next\nid\ti\t1\nid\ti\t2"),
            token("d=next\nid\tb\tyes"),
            token("d=next\nid\tz\tsomething"),
            token("d=next\nid\tt\tyesterday"),
            token("d=next\nid\tu\tnot-a-uuid"),
            token("d=next\nid\ts\t%zz"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xff, (byte) 0xfe}),
            token("d=next\nid\tn\t1E+1001"),
            token("d=next\nid\tn\t1E-1001"),
            token("d=next\nid\tn\t1" + "0".repeat(1002) + "E-1"),
            token("d=next\nid\ti\t+1"),
            token("d=next\nid\ti\t\u0661"),
            token("d=next\nid\td\tNaN"),
            token("d=next\nid\td\tInfinity"),
            token("d=next\nid\td\t-Infinity"),
            token("d=next\nid\td\t0x1p3"),
            token("d=next\nid\td\t1.5d"),
            token("d=next\nid\td\t1.5f"),
            token("d=next\nid\td\t 1.5"),
            token("d=next\nid\td\t+1.5"),
            token("d=next\nid\td\t1e400"),
            token("d=next\nid\ti\t1") + "=",
            noncanonical(token("d=next\nid\ti\t1")),
        };
        for (String token : bad) {
            assertThrows(IllegalArgumentException.class, () -> Cursor.decode(token), token);
        }
        assertThrows(NullPointerException.class, () -> Cursor.decode(null));
    }

    // Flips the unused low bits of the last base64 character, which a lenient decoder ignores.
    private static String noncanonical(String token) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char last = token.charAt(token.length() - 1);
        char changed = alphabet.charAt(alphabet.indexOf(last) ^ 1);
        return token.substring(0, token.length() - 1) + changed;
    }

    @Test
    void canonicalNumbersStillDecode() {
        assertEquals(-12L, Cursor.decode(token("d=next\nid\ti\t-12")).parameter("id"));
        assertEquals(1.5e-3, Cursor.decode(token("d=next\nid\td\t1.5E-3")).parameter("id"));
        assertEquals(-2.0, Cursor.decode(token("d=next\nid\td\t-2.0")).parameter("id"));
        assertEquals(new BigDecimal("1E+999"), Cursor.decode(token("d=next\nid\tn\t1E+999")).parameter("id"));
    }

    @Test
    void randomTokensNeverEscapeAsAnotherException() {
        Random random = new Random(17);
        for (int i = 0; i < 2000; i++) {
            byte[] bytes = new byte[random.nextInt(40)];
            random.nextBytes(bytes);
            String token = random.nextBoolean()
                    ? Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                    : token("d=next\n" + new String(bytes, StandardCharsets.ISO_8859_1));
            try {
                Cursor.decode(token);
            } catch (IllegalArgumentException expected) {
                // refused, as it should be
            }
        }
    }
}
