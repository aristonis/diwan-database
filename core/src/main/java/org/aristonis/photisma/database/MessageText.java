package org.aristonis.photisma.database;

import java.util.List;
import java.util.Locale;

/**
 * Shapes text that goes into exception messages: backslashes, control characters and Unicode line
 * separators are escaped so a message stays one log line that SQL or a value cannot forge or color,
 * and long parts are cut on code point boundaries so a message stays readable.
 */
final class MessageText {

    static final int MAX_SQL_LENGTH = 1000;
    static final int MAX_VALUE_LENGTH = 256;
    static final int MAX_NAME_LENGTH = 256;

    private static final String ELLIPSIS = "...";
    private static final char LINE_SEPARATOR = ' ';
    private static final char PARAGRAPH_SEPARATOR = ' ';
    private static final char NEXT_LINE = '\u0085';

    private MessageText() {
    }

    static String sql(String sql) {
        return escape(clip(sql, MAX_SQL_LENGTH));
    }

    /** Shapes a connection name or SQL state. */
    static String name(String name) {
        return escape(clip(name, MAX_NAME_LENGTH));
    }

    /** Renders values like {@link List#toString()}, with each value escaped and cut. */
    static String values(List<?> values) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(value(values.get(i)));
        }
        return text.append(']').toString();
    }

    private static String value(Object value) {
        if (value instanceof byte[]) {
            return "<" + ((byte[]) value).length + " bytes>";
        }
        return escape(clip(printable(value), MAX_VALUE_LENGTH));
    }

    // A value whose toString() throws must not hide the failure being reported.
    private static String printable(Object value) {
        try {
            return String.valueOf(value);
        } catch (RuntimeException failure) {
            return "<unprintable " + value.getClass().getName() + ">";
        }
    }

    /** Cuts after {@code maxLength} code points, never inside a surrogate pair. */
    private static String clip(String text, int maxLength) {
        if (text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxLength)) + ELLIPSIS;
    }

    // The backslash goes first, so an escape already in the text cannot pass for one of ours.
    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            appendEscaped(escaped, text.charAt(i));
        }
        return escaped.toString();
    }

    private static void appendEscaped(StringBuilder escaped, char c) {
        if (c == '\\') {
            escaped.append("\\\\");
        } else if (c == '\r') {
            escaped.append("\\r");
        } else if (c == '\n') {
            escaped.append("\\n");
        } else if (Character.isISOControl(c) || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR
                || c == NEXT_LINE) {
            escaped.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
        } else {
            escaped.append(c);
        }
    }
}
