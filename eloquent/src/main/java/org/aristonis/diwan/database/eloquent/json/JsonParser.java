package org.aristonis.diwan.database.eloquent.json;

import org.aristonis.diwan.database.eloquent.exception.JsonException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 8259 recursive-descent parser.
 *
 * <p>Rejects comments, trailing commas, trailing text, leading zeros, a byte-order mark and lone
 * surrogates; accepts NUL. Integers become {@code Long}, or {@code BigInteger} past the long range;
 * any number with a fraction or exponent becomes {@code BigDecimal}. An earlier design read those as
 * {@code Double}, which rounded {@code 0.1} and large ids on re-encode — that is why no
 * {@code Double} ever comes out of here. Error messages carry a position and reason, never input.
 */
final class JsonParser {

    /** Deepest array/object nesting accepted, matching PHP's {@code json_decode} default. */
    static final int MAX_DEPTH = 512;

    private static final int HEX_DIGITS = 4;
    private static final int HEX_RADIX = 16;
    private static final char FIRST_NON_CONTROL = 0x20;

    private final String text;
    private int pos;

    private JsonParser(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        if (text == null) {
            throw new JsonException("Cannot decode null text");
        }
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    private Object value(int depth) {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object(depth + 1);
            case '[':
                return array(depth + 1);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || isDigit(c)) {
                    return number();
                }
                throw error("unexpected character");
        }
    }

    private Map<String, Object> object(int depth) {
        checkDepth(depth);
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            return map;
        }
        while (true) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw error("expected string key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            // put() on an existing key keeps its first-seen slot and replaces the value: last wins.
            map.put(key, value(depth));
            skipWhitespace();
            if (consume('}')) {
                return map;
            }
            expect(',');
        }
    }

    private List<Object> array(int depth) {
        checkDepth(depth);
        pos++;
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value(depth));
            skipWhitespace();
            if (consume(']')) {
                return list;
            }
            expect(',');
        }
    }

    private void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting deeper than " + MAX_DEPTH + " levels");
        }
    }

    private Object literal(String word, Object result) {
        if (!text.startsWith(word, pos)) {
            throw error("invalid literal");
        }
        pos += word.length();
        return result;
    }

    private Object number() {
        int start = pos;
        consume('-');
        if (consume('0')) {
            if (pos < text.length() && isDigit(text.charAt(pos))) {
                throw error("leading zero");
            }
        } else {
            requireDigits();
        }
        boolean isInteger = true;
        if (consume('.')) {
            isInteger = false;
            requireDigits();
        }
        if (consume('e') || consume('E')) {
            isInteger = false;
            if (!consume('+')) {
                consume('-');
            }
            requireDigits();
        }
        String literal = text.substring(start, pos);
        if (isInteger) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException overflow) {
                return new BigInteger(literal);
            }
        }
        try {
            return new BigDecimal(literal);
        } catch (NumberFormatException | ArithmeticException outOfRange) {
            throw error("number out of range", start);
        }
    }

    private void requireDigits() {
        if (pos >= text.length() || !isDigit(text.charAt(pos))) {
            throw error("expected digit");
        }
        while (pos < text.length() && isDigit(text.charAt(pos))) {
            pos++;
        }
    }

    private String string() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos);
            if (c == '"') {
                pos++;
                return out.toString();
            }
            if (c < FIRST_NON_CONTROL) {
                throw error("unescaped control character in string");
            }
            if (c == '\\') {
                pos++;
                c = escape();
            } else {
                pos++;
            }
            appendChecked(out, c);
        }
    }

    // Raw and escaped surrogates go through the same check, so a lone half is rejected either way.
    private void appendChecked(StringBuilder out, char c) {
        if (Character.isHighSurrogate(c)) {
            char low = nextLowSurrogate();
            out.append(c).append(low);
        } else if (Character.isLowSurrogate(c)) {
            throw error("lone low surrogate");
        } else {
            out.append(c);
        }
    }

    private char nextLowSurrogate() {
        char low;
        if (text.startsWith("\\u", pos)) {
            pos += 2;
            low = hex();
        } else if (pos < text.length()) {
            low = text.charAt(pos++);
        } else {
            throw error("unterminated string");
        }
        if (!Character.isLowSurrogate(low)) {
            throw error("lone high surrogate");
        }
        return low;
    }

    private char escape() {
        if (pos >= text.length()) {
            throw error("unterminated escape");
        }
        char c = text.charAt(pos++);
        switch (c) {
            case '"': return '"';
            case '\\': return '\\';
            case '/': return '/';
            case 'b': return '\b';
            case 'f': return '\f';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'u': return hex();
            default: throw error("invalid escape", pos - 1);
        }
    }

    private char hex() {
        if (pos + HEX_DIGITS > text.length()) {
            throw error("truncated unicode escape");
        }
        int code = 0;
        for (int i = 0; i < HEX_DIGITS; i++) {
            int digit = Character.digit(text.charAt(pos), HEX_RADIX);
            if (digit < 0) {
                throw error("invalid hex digit in unicode escape");
            }
            code = code * HEX_RADIX + digit;
            pos++;
        }
        return (char) code;
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private boolean consume(char expected) {
        if (pos < text.length() && text.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private JsonException error(String reason) {
        return error(reason, pos);
    }

    private JsonException error(String reason, int at) {
        return new JsonException("Invalid JSON at position " + at + ": " + reason);
    }
}
