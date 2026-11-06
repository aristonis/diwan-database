package org.aristonis.diwan.database.eloquent.json;

import org.aristonis.diwan.database.eloquent.exception.JsonException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Compact JSON writer. {@code /} is not escaped; quote, backslash, control characters and U+2028/U+2029
 * always are, and every character from 0x80 up is escaped when {@code escapeNonAscii} is set.
 */
final class JsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char FIRST_NON_CONTROL = 0x20;
    private static final char FIRST_NON_ASCII = 0x80;
    private static final char LINE_SEPARATOR = (char) 0x2028;
    private static final char PARAGRAPH_SEPARATOR = (char) 0x2029;

    private final StringBuilder out = new StringBuilder();
    private final boolean escapeNonAscii;

    private JsonWriter(boolean escapeNonAscii) {
        this.escapeNonAscii = escapeNonAscii;
    }

    static String write(Object value, boolean escapeNonAscii) {
        JsonWriter writer = new JsonWriter(escapeNonAscii);
        writer.value(value, 0);
        return writer.out.toString();
    }

    private void value(Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string((String) value);
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value).booleanValue());
        } else if (value instanceof Number) {
            number((Number) value);
        } else if (value instanceof Map) {
            object((Map<?, ?>) value, depth + 1);
        } else if (value instanceof List) {
            array((List<?>) value, depth + 1);
        } else {
            throw unsupported(value);
        }
    }

    private void object(Map<?, ?> map, int depth) {
        checkDepth(depth);
        out.append('{');
        boolean isFirst = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new JsonException("Object keys must be strings, got "
                        + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
            }
            if (!isFirst) {
                out.append(',');
            }
            isFirst = false;
            string((String) entry.getKey());
            out.append(':');
            value(entry.getValue(), depth);
        }
        out.append('}');
    }

    private void array(List<?> list, int depth) {
        checkDepth(depth);
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            value(list.get(i), depth);
        }
        out.append(']');
    }

    // A cyclic structure recurses until this limit, so it fails as a JsonException long before
    // the stack runs out — no identity set is needed to detect it.
    private static void checkDepth(int depth) {
        if (depth > JsonParser.MAX_DEPTH) {
            throw new JsonException("Nesting deeper than " + JsonParser.MAX_DEPTH + " levels (cyclic structure?)");
        }
    }

    private void number(Number number) {
        if (number instanceof Long || number instanceof Integer || number instanceof Short
                || number instanceof Byte || number instanceof BigInteger) {
            out.append(number.toString());
        } else if (number instanceof BigDecimal) {
            out.append(((BigDecimal) number).toString());
        } else if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("Non-finite " + number.getClass().getName() + " cannot be encoded");
            }
            out.append(number.toString());
        } else {
            throw unsupported(number);
        }
    }

    private void string(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: character(c);
            }
        }
        out.append('"');
    }

    private void character(char c) {
        boolean mustEscape = c < FIRST_NON_CONTROL || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR
                || (escapeNonAscii && c >= FIRST_NON_ASCII);
        if (!mustEscape) {
            out.append(c);
            return;
        }
        out.append('\\').append('u')
                .append(HEX[(c >> 12) & 0xf]).append(HEX[(c >> 8) & 0xf])
                .append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
    }

    private static JsonException unsupported(Object value) {
        return new JsonException("Unsupported type " + value.getClass().getName());
    }
}
