package org.aristonis.diwan.database.eloquent.casts;

import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * A cast string waiting to be parsed, made by {@link Casts#of(String)}.
 *
 * <p>The model resolves it through {@link Casts#resolve(Cast)} when it first reads its casts, so a
 * bad string fails there with the model and key named. Calling it directly is a bug and throws.
 */
final class SpecCast implements Cast<Object> {

    private final String spec;

    /**
     * Creates the placeholder.
     *
     * @param spec the cast string, kept verbatim
     * @throws NullPointerException if {@code spec} is null
     */
    SpecCast(String spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    /**
     * Returns the cast string.
     *
     * @return the cast string as written
     */
    String spec() {
        return spec;
    }

    @Override
    public Object get(Model model, String key, Object raw, Map<String, Object> attributes) {
        throw unresolved();
    }

    @Override
    public Object set(Model model, String key, Object value, Map<String, Object> attributes) {
        throw unresolved();
    }

    @Override
    public String toString() {
        return "SpecCast[" + spec + "]";
    }

    private IllegalStateException unresolved() {
        return new IllegalStateException("Cast [" + spec + "] is unresolved; call Casts.resolve first");
    }
}
