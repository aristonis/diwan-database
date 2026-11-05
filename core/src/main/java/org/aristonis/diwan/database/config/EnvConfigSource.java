package org.aristonis.diwan.database.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A configuration source over environment variables.
 *
 * <p>A lookup tries the key exactly as given, then in the usual environment form: upper case with
 * every dot replaced by an underscore, so {@code database.default} also finds
 * {@code DATABASE_DEFAULT}. {@link #keys(String)} lists variable names as they are written and
 * splits them on dots only; most variable names have none, so it is mainly useful for the empty
 * prefix.
 *
 * <p>Instances are immutable and safe to share between threads. {@link #system()} takes a snapshot
 * when it is called.
 */
public final class EnvConfigSource implements ConfigSource {

    /** The environment snapshot. */
    private final MapConfigSource variables;

    private EnvConfigSource(MapConfigSource variables) {
        this.variables = variables;
    }

    /**
     * Creates a source over a snapshot of the process environment.
     *
     * @return a source over a copy of {@link System#getenv()}
     */
    public static EnvConfigSource system() {
        return of(System.getenv());
    }

    /**
     * Creates a source over the given variables, which is how tests replace the environment.
     *
     * @param variables the variable names and values, never {@code null}
     * @return a source over a copy of the variables
     * @throws NullPointerException if {@code variables}, a name or a value is {@code null}
     */
    public static EnvConfigSource of(Map<String, String> variables) {
        return new EnvConfigSource(MapConfigSource.of(new HashMap<>(variables)));
    }

    /**
     * Returns the value of a variable, looked up by the exact key and then in environment form.
     *
     * @param key the key or variable name, never {@code null}
     * @return the value, or empty when neither form is set
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        Optional<String> exact = variables.get(key);
        if (exact.isPresent()) {
            return exact;
        }
        return variables.get(key.toUpperCase(Locale.ROOT).replace('.', '_'));
    }

    /**
     * Returns the variable-name segments one level below a prefix.
     *
     * @param prefix the dot-separated prefix, empty for the top level, never {@code null}
     * @return the direct child segments in natural string order, unmodifiable
     * @throws NullPointerException if {@code prefix} is {@code null}
     */
    @Override
    public Set<String> keys(String prefix) {
        return variables.keys(prefix);
    }

    /**
     * Describes the source without its values.
     *
     * @return the class name and the number of variables
     */
    @Override
    public String toString() {
        return "EnvConfigSource" + variables.toString().substring("MapConfigSource".length());
    }
}
