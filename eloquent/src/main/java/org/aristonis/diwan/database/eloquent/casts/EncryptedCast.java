package org.aristonis.diwan.database.eloquent.casts;

import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The {@code encrypted} casts: a text, array, json, collection or object value stored as the
 * text the model's {@link AttributeEncrypter} returns.
 *
 * <p>The cast wraps an inner cast. A read decrypts the stored text and hands it to the inner
 * cast; a write lets the inner cast encode the value, then encrypts the result. Dirty checks
 * decrypt both stored values and compare them through the inner cast, unless the encrypter
 * reports previous keys, in which case a changed ciphertext is always dirty. A null column reads
 * null and a null value stores null, without calling the encrypter.
 *
 * <p>Instances hold no state beyond the inner cast and are safe to share across models and
 * threads. Decrypted text is never put in an exception message.
 */
final class EncryptedCast implements Cast<Object>, CastComparator, CastSerializer<Object> {

    private static final String CAST_NAME = "encrypted";

    private final Cast<Object> inner;
    private final CastComparator comparator;
    private final CastSerializer<Object> serializer;

    EncryptedCast() {
        this(new PrimitiveCasts.StringCast("string"));
    }

    /**
     * Creates the cast around an inner cast already validated by {@code Casts.encrypted}.
     *
     * @param inner the string, json, collection or object cast, which also compares and serializes
     */
    @SuppressWarnings("unchecked") // validated by Casts.encrypted
    EncryptedCast(Cast<?> inner) {
        this.inner = (Cast<Object>) inner;
        this.comparator = (CastComparator) inner;
        this.serializer = (CastSerializer<Object>) inner;
    }

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        if (raw == null) {
            return null;
        }
        String plain = encrypterOf(model, key)
                .decrypt(CastHooks.rawText(model, key, raw, CAST_NAME));
        return inner.get(model, key, plain, attributes);
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        if (value == null) {
            return null;
        }
        AttributeEncrypter encrypter = encrypterOf(model, key);
        Object stored = inner.set(model, key, value, attributes);
        if (stored == null) {
            return null;
        }
        return CastHooks.requireResult(encrypter.encrypt((String) stored), model, key,
                "Encrypter", CAST_NAME);
    }

    @Override
    public boolean compare(Model model, String key, Object originalRaw, Object currentRaw) {
        if (originalRaw == null || currentRaw == null) {
            return originalRaw == currentRaw;
        }
        AttributeEncrypter encrypter = encrypterOf(model, key);
        if (encrypter.hasPreviousKeys()) {
            return false;
        }
        return comparator.compare(model, key,
                encrypter.decrypt(CastHooks.rawText(model, key, originalRaw, CAST_NAME)),
                encrypter.decrypt(CastHooks.rawText(model, key, currentRaw, CAST_NAME)));
    }

    @Override
    public Object serialize(Model model, String key, Object value, Map<String, Object> attributes) {
        return serializer.serialize(model, key, value, attributes);
    }

    private static AttributeEncrypter encrypterOf(Model model, String key) {
        return CastHooks.requireHook(model == null ? null : model.getAttributeEncrypter(),
                model, key, "attribute encrypter", CAST_NAME, "attributeEncrypter");
    }
}
