package org.aristonis.diwan.database.eloquent.json;

/**
 * Converts between JSON text and plain Java values.
 *
 * <p>The value model is {@code Map<String, ?>} for objects, {@code List} for arrays,
 * {@code String}, {@code Boolean}, {@code null} and numbers.
 *
 * @implSpec Implementations must be safe to share across threads, must throw
 *     {@link JsonException} for every failure, and must never put the input text or a value into an
 *     exception message.
 */
public interface JsonCodec {

    /**
     * Encodes a value as compact JSON with every non-ASCII character escaped as a six-character unicode escape.
     *
     * @param value the value; null encodes as {@code null}
     * @return the JSON text
     * @throws JsonException when the value, or anything nested in it, is unsupported, not finite,
     *     or nested deeper than the codec allows (which is how a cyclic structure fails)
     */
    String encode(Object value);

    /**
     * Decodes JSON text into plain Java values.
     *
     * <p>Objects decode to a mutable {@code LinkedHashMap} in first-seen key order; for a
     * duplicate key the last value wins. Arrays decode to a mutable {@code List}.
     *
     * @param text the JSON text
     * @return the decoded value, null for JSON {@code null}
     * @throws JsonException when the text is null or is not valid JSON
     */
    Object decode(String text);

    /**
     * Encodes a value like {@link #encode(Object)} but keeps non-ASCII characters as they are,
     * matching Laravel's {@code json:unicode} cast.
     *
     * <p>Quotes, backslashes, control characters, U+2028 and U+2029 are still escaped.
     *
     * @implSpec The default delegates to {@link #encode(Object)}, so a codec that does not
     *     override it escapes everything.
     * @param value the value; null encodes as {@code null}
     * @return the JSON text
     * @throws JsonException when the value cannot be encoded
     */
    default String encodeUnescaped(Object value) {
        return encode(value);
    }

    /**
     * Returns the built-in strict codec.
     *
     * @return the shared standard codec, safe across threads
     */
    static JsonCodec standard() {
        return StandardJsonCodec.INSTANCE;
    }
}
