package org.aristonis.diwan.database.eloquent.casts;

import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;

/**
 * Checks shared by the casts that call a model-supplied hook: the missing-hook error, the
 * null-result error and the raw column text rule.
 *
 * <p>No message built here holds an attribute value.
 */
final class CastHooks {

    private CastHooks() {
    }

    /**
     * Returns the model's class name for a message.
     *
     * @param model the model, may be null
     * @return the class name, or {@code unknown model} for a null model
     */
    static String modelName(Model model) {
        return model == null ? "unknown model" : model.getClass().getName();
    }

    /**
     * Returns a hook the model supplied, failing when it has none.
     *
     * @param <H> the hook type
     * @param hook the hook the model returned, may be null
     * @param model the model, may be null
     * @param key the attribute name
     * @param what the hook's name in the message, such as {@code attribute encrypter}
     * @param castName the cast name in the message
     * @param method the model method to override, without parentheses
     * @return {@code hook}, never null
     * @throws IllegalStateException if {@code hook} is null
     */
    static <H> H requireHook(H hook, Model model, String key, String what, String castName,
            String method) {
        if (hook == null) {
            throw new IllegalStateException("Model [" + modelName(model) + "] has no " + what
                    + " for " + castName + " attribute [" + key + "]; override " + method + "()");
        }
        return hook;
    }

    /**
     * Returns the result of a hook call, failing when it is null.
     *
     * @param <R> the result type
     * @param result the value the hook returned, may be null
     * @param model the model, may be null
     * @param key the attribute name
     * @param who the hook's role at the start of the message, such as {@code Encrypter}
     * @param castName the cast name in the message
     * @return {@code result}, never null
     * @throws IllegalStateException if {@code result} is null
     */
    static <R> R requireResult(R result, Model model, String key, String who, String castName) {
        if (result == null) {
            throw new IllegalStateException(who + " for [" + modelName(model)
                    + "] returned null for " + castName + " attribute [" + key + "]");
        }
        return result;
    }

    /**
     * Reads the text of a raw column value through {@code JsonCasts}.
     *
     * @param model the model, may be null
     * @param key the attribute name
     * @param raw the raw column value, never null
     * @param castName the cast name carried by the failure
     * @return the column text
     * @throws InvalidCastException if the raw value is of an unsupported type
     */
    static String rawText(Model model, String key, Object raw, String castName) {
        try {
            return JsonCasts.textOf(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidCastException(JsonCasts.modelClass(model), key, castName, e);
        }
    }
}
