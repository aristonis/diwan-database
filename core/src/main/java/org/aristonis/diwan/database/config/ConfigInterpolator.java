package org.aristonis.diwan.database.config;

import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.exception.DatabaseConfigException;

/**
 * Replaces {@code ${NAME}} references in a configuration value.
 *
 * <p>{@code ${NAME}} takes the value of {@code NAME} from the variable source,
 * {@code ${NAME:-fallback}} uses the fallback when the variable is not set, and {@code $${NAME}} stays as
 * the literal text {@code ${NAME}}. The pass is single: replacement text is not scanned again. Error
 * messages name the config key and the variable, never a value.
 */
public final class ConfigInterpolator {

    private static final String OPEN = "${";
    private static final String ESCAPED_OPEN = "$${";
    private static final String FALLBACK_MARK = ":-";
    private static final char CLOSE = '}';
    private static final String CONNECTION_KEY_PREFIX = "database.connections.";

    private ConfigInterpolator() {
    }

    /**
     * Resolves the references in one value.
     *
     * @param key the config key the value came from, used in error messages, never {@code null}
     * @param raw the value as written, never {@code null}
     * @param vars the source of variable values, never {@code null}
     * @return the value with every reference replaced
     * @throws DatabaseConfigException if a variable is missing without a fallback, a name is empty
     *     or a reference is not closed or holds another reference inside it
     */
    public static String resolve(String key, String raw, ConfigSource vars) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(vars, "vars");
        if (raw.indexOf('$') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int at = 0;
        while (at < raw.length()) {
            if (raw.startsWith(ESCAPED_OPEN, at)) {
                out.append(OPEN);
                at += ESCAPED_OPEN.length();
            } else if (raw.startsWith(OPEN, at)) {
                at = substitute(key, raw, at, vars, out);
            } else {
                out.append(raw.charAt(at));
                at++;
            }
        }
        return out.toString();
    }

    /** Appends the replacement for the reference starting at {@code start}; returns the next index. */
    private static int substitute(String key, String raw, int start, ConfigSource vars, StringBuilder out) {
        int end = raw.indexOf(CLOSE, start);
        if (end < 0) {
            throw failure("Config key [" + key + "] has a ${ reference that is not closed", key);
        }
        String body = raw.substring(start + OPEN.length(), end);
        if (body.contains(OPEN)) {
            throw failure("Config key [" + key + "] has a ${ reference nested inside another; "
                    + "nested references are not supported", key);
        }
        int mark = body.indexOf(FALLBACK_MARK);
        String name = mark < 0 ? body : body.substring(0, mark);
        if (name.isEmpty()) {
            throw failure("Config key [" + key + "] has a ${} reference with no variable name", key);
        }
        Optional<String> value = vars.get(name);
        if (value.isPresent()) {
            out.append(value.get());
        } else if (mark >= 0) {
            out.append(body, mark + FALLBACK_MARK.length(), body.length());
        } else {
            throw failure("Config key [" + key + "] refers to variable [" + name + "], which is not set", key);
        }
        return end + 1;
    }

    private static DatabaseConfigException failure(String message, String key) {
        return new DatabaseConfigException(message, connectionOf(key), key);
    }

    private static String connectionOf(String key) {
        if (!key.startsWith(CONNECTION_KEY_PREFIX)) {
            return null;
        }
        String rest = key.substring(CONNECTION_KEY_PREFIX.length());
        int dot = rest.indexOf('.');
        return dot <= 0 ? null : rest.substring(0, dot);
    }
}
