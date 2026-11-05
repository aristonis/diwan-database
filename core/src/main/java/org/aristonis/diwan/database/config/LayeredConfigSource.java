package org.aristonis.diwan.database.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A configuration source that asks several sources in priority order.
 *
 * <p>The first source that has a key wins; later ones are not asked. The child segments under a
 * prefix are the union of every layer's. Instances are immutable and safe to share when the layers
 * are.
 */
public final class LayeredConfigSource implements ConfigSource {

    /** The layers, highest priority first. */
    private final List<ConfigSource> layers;

    private LayeredConfigSource(List<ConfigSource> layers) {
        this.layers = layers;
    }

    /**
     * Creates a layered source.
     *
     * @param firstWins the layers, highest priority first, never {@code null} and without
     *     {@code null} elements; an empty list gives a source with no values
     * @return a source over a copy of the list of layers
     * @throws NullPointerException if the array or a layer is {@code null}
     */
    public static LayeredConfigSource of(ConfigSource... firstWins) {
        Objects.requireNonNull(firstWins, "firstWins");
        List<ConfigSource> copy = new ArrayList<>(firstWins.length);
        for (ConfigSource layer : firstWins) {
            copy.add(Objects.requireNonNull(layer, "layer"));
        }
        return new LayeredConfigSource(Collections.unmodifiableList(copy));
    }

    /**
     * Returns the value from the first layer that has the key.
     *
     * @param key the full key, never {@code null}
     * @return the value, or empty when no layer has it
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        for (ConfigSource layer : layers) {
            Optional<String> value = layer.get(key);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the union of every layer's names one level below a prefix.
     *
     * @param prefix the dot-separated prefix, empty for the top level, never {@code null}
     * @return the direct child segments in natural string order, unmodifiable
     * @throws NullPointerException if {@code prefix} is {@code null}
     */
    @Override
    public Set<String> keys(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        Set<String> union = new TreeSet<>();
        for (ConfigSource layer : layers) {
            union.addAll(layer.keys(prefix));
        }
        return Collections.unmodifiableSet(union);
    }

    /**
     * Describes the source without its values.
     *
     * @return the class name and the number of layers
     */
    @Override
    public String toString() {
        return "LayeredConfigSource[layers=" + layers.size() + "]";
    }
}
