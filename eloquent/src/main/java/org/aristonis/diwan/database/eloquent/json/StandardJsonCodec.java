package org.aristonis.diwan.database.eloquent.json;

/** The built-in codec: {@link JsonParser} for reading, {@link JsonWriter} for writing. Stateless. */
final class StandardJsonCodec implements JsonCodec {

    static final StandardJsonCodec INSTANCE = new StandardJsonCodec();

    private StandardJsonCodec() {
    }

    @Override
    public String encode(Object value) {
        return JsonWriter.write(value, true);
    }

    @Override
    public Object decode(String text) {
        return JsonParser.parse(text);
    }

    @Override
    public String encodeUnescaped(Object value) {
        return JsonWriter.write(value, false);
    }
}
