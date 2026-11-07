package org.aristonis.diwan.database.query;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes and reads the cursor token: base64url without padding of UTF-8 text whose first line is
 * {@code d=next} or {@code d=prev} and whose every further line is {@code name TAB type TAB value},
 * with the name and a string value percent-encoded so neither can carry a tab or a line break. Type
 * letters: {@code s} string, {@code i} long, {@code n} decimal, {@code d} double, {@code b} boolean,
 * {@code z} null, {@code t} instant, {@code l} local date-time, {@code o} offset date-time,
 * {@code u} UUID, {@code a} local date (ISO {@code yyyy-MM-dd}) and {@code c} local time (ISO
 * {@code HH:mm[:ss[.n]]}). No JSON library is needed, and every malformed token fails the same way.
 */
final class CursorTokens {

    // A cursor holds a handful of short values; anything far longer is not one of ours.
    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final String NEXT = "d=next";
    private static final String PREVIOUS = "d=prev";
    private static final String UTF_8 = "UTF-8";
    private static final String INVALID = "Invalid cursor token";
    private static final int MAX_DECIMAL_SCALE = 1000;
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    private static final Pattern REAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?([eE]-?[0-9]+)?");

    private CursorTokens() {
    }

    /** Brings a value to the type it is kept as, refusing a type the token cannot carry. */
    static Object normalize(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float) {
            return ((Float) value).doubleValue();
        }
        // Through the text, as the JDBC reader does: Android has no java.sql.Date.toLocalDate().
        if (value instanceof java.sql.Date) {
            return LocalDate.parse(value.toString());
        }
        if (value instanceof java.sql.Time) {
            return LocalTime.parse(value.toString());
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toInstant();
        }
        if (value == null || typeOf(value) != 0) {
            return value;
        }
        throw new IllegalArgumentException("A cursor cannot hold a value of type " + value.getClass().getName());
    }

    static String encode(Map<String, Object> parameters, boolean pointsToNextItems) {
        StringBuilder text = new StringBuilder(pointsToNextItems ? NEXT : PREVIOUS);
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            Object value = entry.getValue();
            char type = value == null ? 'z' : typeOf(value);
            String written = value == null ? "" : value instanceof String ? percent((String) value) : value.toString();
            text.append('\n').append(percent(entry.getKey())).append('\t').append(type).append('\t').append(written);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String token) {
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException(INVALID + ": too long");
        }
        String[] lines = text(token).split("\n", -1);
        boolean isNext = NEXT.equals(lines[0]);
        if (!isNext && !PREVIOUS.equals(lines[0])) {
            throw new IllegalArgumentException(INVALID + ": no direction");
        }
        if (lines.length < 2) {
            throw new IllegalArgumentException(INVALID + ": no parameters");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split("\t", -1);
            if (parts.length != 3 || parts[1].length() != 1) {
                throw new IllegalArgumentException(INVALID + ": malformed parameter");
            }
            String name = unpercent(parts[0]);
            if (parameters.containsKey(name)) {
                throw new IllegalArgumentException(INVALID + ": repeated parameter");
            }
            parameters.put(name, value(parts[1].charAt(0), parts[2]));
        }
        return new Cursor(parameters, isNext);
    }

    private static String text(String token) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(INVALID + ": not base64url", e);
        }
        // The decoder ignores padding and the unused low bits of the last character, so one value
        // has many spellings; only the one encode() writes is accepted.
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
            throw new IllegalArgumentException(INVALID + ": not canonical base64url");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(INVALID + ": not UTF-8", e);
        }
    }

    private static Object value(char type, String text) {
        try {
            switch (type) {
                case 's':
                    return unpercent(text);
                case 'i':
                    return integer(text);
                case 'n':
                    return decimal(text);
                case 'd':
                    return real(text);
                case 'b':
                    return bool(text);
                case 'z':
                    if (!text.isEmpty()) {
                        throw new IllegalArgumentException(INVALID + ": null with a value");
                    }
                    return null;
                case 't':
                    return Instant.parse(text);
                case 'l':
                    return LocalDateTime.parse(text);
                case 'o':
                    return OffsetDateTime.parse(text);
                case 'u':
                    return uuid(text);
                case 'a':
                    return LocalDate.parse(text);
                case 'c':
                    return LocalTime.parse(text);
                default:
                    throw new IllegalArgumentException(INVALID + ": unknown type");
            }
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(INVALID + ": bad date or time", e);
        }
    }

    // Long.parseLong also takes a leading '+' and non-ASCII digits; only encode()'s form is accepted.
    private static Long integer(String text) {
        if (!INTEGER.matcher(text).matches()) {
            throw new IllegalArgumentException(INVALID + ": bad integer");
        }
        return Long.parseLong(text);
    }

    // A scale such as 1E-999999999 is cheap to parse and ruinous to compare or print, so both the
    // scale and the digits before the point are bounded.
    private static BigDecimal decimal(String text) {
        BigDecimal value = new BigDecimal(text);
        if (Math.abs((long) value.scale()) > MAX_DECIMAL_SCALE
                || (long) value.precision() - value.scale() > MAX_DECIMAL_SCALE) {
            throw new IllegalArgumentException(INVALID + ": decimal out of range");
        }
        return value;
    }

    // Double.parseDouble also takes hex, a d or f suffix, spaces, NaN and Infinity.
    private static Double real(String text) {
        if (!REAL.matcher(text).matches()) {
            throw new IllegalArgumentException(INVALID + ": bad number");
        }
        double value = Double.parseDouble(text);
        if (Double.isInfinite(value)) {
            throw new IllegalArgumentException(INVALID + ": number out of range");
        }
        return value;
    }

    private static Boolean bool(String text) {
        if ("true".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(INVALID + ": bad boolean");
    }

    // UUID.fromString accepts short groups such as "1-2-3-4-5"; only the canonical form round-trips.
    private static UUID uuid(String text) {
        UUID uuid = UUID.fromString(text);
        if (!uuid.toString().equals(text)) {
            throw new IllegalArgumentException(INVALID + ": bad UUID");
        }
        return uuid;
    }

    private static char typeOf(Object value) {
        if (value instanceof String) {
            return 's';
        }
        if (value instanceof Long) {
            return 'i';
        }
        if (value instanceof BigDecimal) {
            return 'n';
        }
        if (value instanceof Double) {
            return 'd';
        }
        if (value instanceof Boolean) {
            return 'b';
        }
        if (value instanceof Instant) {
            return 't';
        }
        if (value instanceof LocalDateTime) {
            return 'l';
        }
        if (value instanceof OffsetDateTime) {
            return 'o';
        }
        if (value instanceof LocalDate) {
            return 'a';
        }
        if (value instanceof LocalTime) {
            return 'c';
        }
        return value instanceof UUID ? 'u' : 0;
    }

    // The Charset overloads are Java 10 and Android 33; the charset-name forms run on Android 28.
    private static String percent(String text) {
        try {
            return URLEncoder.encode(text, UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }

    private static String unpercent(String text) {
        try {
            return URLDecoder.decode(text, UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }
}
