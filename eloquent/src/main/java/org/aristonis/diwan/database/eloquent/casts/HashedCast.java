package org.aristonis.diwan.database.eloquent.casts;

import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The {@code hashed} cast: a string stored as the text the model's {@link AttributeHasher}
 * returns.
 *
 * <p>A write stores an already hashed, current value as given and otherwise stores its hash. A
 * read returns the stored text and never calls the hasher. A null value stores null and a null
 * column reads null, without calling the hasher. Only a {@code String} is accepted on a write.
 *
 * <p>Instances hold no state and are safe to share across models and threads. The value is never
 * put in an exception message.
 */
final class HashedCast implements Cast<Object> {

    private static final String CAST_NAME = "hashed";
    private static final String NOT_CURRENT =
            "Could not verify the hashed value's configuration.";

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        return raw == null ? null : CastHooks.rawText(model, key, raw, CAST_NAME);
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        if (value == null) {
            return null;
        }
        AttributeHasher hasher = CastHooks.requireHook(
                model == null ? null : model.getAttributeHasher(),
                model, key, "attribute hasher", CAST_NAME, "attributeHasher");
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("hashed attribute [" + key + "] of ["
                    + CastHooks.modelName(model) + "] accepts a String, got "
                    + value.getClass().getName());
        }
        String text = (String) value;
        if (!hasher.isHashed(text)) {
            return CastHooks.requireResult(hasher.hash(text), model, key, "Hasher", CAST_NAME);
        }
        if (!hasher.isCurrent(text)) {
            throw new IllegalStateException(NOT_CURRENT);
        }
        return text;
    }
}
