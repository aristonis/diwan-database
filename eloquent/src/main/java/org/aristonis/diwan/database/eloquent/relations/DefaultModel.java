package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The {@code withDefault} setting of a single relation: Laravel's {@code SupportsDefaultModels}.
 *
 * <p>Immutable; a relation replaces its instance on each {@code withDefault} call. The default
 * model is new on every use, never saved and does not exist.
 *
 * @param <R> the related model class
 */
final class DefaultModel<R extends Model> {

    private static final DefaultModel<Model> NONE = new DefaultModel<>(false, Collections.emptyMap(), null);

    private final boolean isEnabled;

    private final Map<String, Object> attributes;

    private final BiConsumer<? super R, Model> callback;

    private DefaultModel(boolean isEnabled, Map<String, Object> attributes, BiConsumer<? super R, Model> callback) {
        this.isEnabled = isEnabled;
        this.attributes = attributes;
        this.callback = callback;
    }

    /**
     * Returns the setting of a relation without a default: it yields {@code null}.
     *
     * @param <R> the related model class
     * @return the shared disabled setting
     */
    @SuppressWarnings("unchecked")
    static <R extends Model> DefaultModel<R> none() {
        // Safe: the disabled setting holds no callback and never builds an R.
        return (DefaultModel<R>) NONE;
    }

    /**
     * Returns a setting whose default is a bare related model.
     *
     * @param <R> the related model class
     * @return the setting
     */
    static <R extends Model> DefaultModel<R> bare() {
        return new DefaultModel<>(true, Collections.emptyMap(), null);
    }

    /**
     * Returns a setting whose default is force-filled with the attributes.
     *
     * @param <R> the related model class
     * @param attributes the attributes, never {@code null}, copied
     * @return the setting
     */
    static <R extends Model> DefaultModel<R> filled(Map<String, ?> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        return new DefaultModel<>(true, Collections.unmodifiableMap(new LinkedHashMap<>(attributes)), null);
    }

    /**
     * Returns a setting whose default is passed, with the parent, to a callback.
     *
     * @param <R> the related model class
     * @param callback receives the new default and the parent, never {@code null}
     * @return the setting
     */
    static <R extends Model> DefaultModel<R> configured(BiConsumer<? super R, Model> callback) {
        return new DefaultModel<>(true, Collections.emptyMap(), Objects.requireNonNull(callback, "callback"));
    }

    /**
     * Returns the default for a parent, or {@code null} when the relation has none.
     *
     * @param parent the parent the default stands in for, never {@code null}
     * @param newInstance makes the related instance for the parent (HasOne sets the foreign key
     *     there, BelongsTo leaves it bare), never {@code null}
     * @return the new default model, or {@code null}
     */
    R createFor(Model parent, Function<Model, R> newInstance) {
        if (!isEnabled) {
            return null;
        }
        R instance = newInstance.apply(parent);
        if (callback != null) {
            callback.accept(instance, parent);
        } else if (!attributes.isEmpty()) {
            instance.forceFill(attributes);
        }
        return instance;
    }
}
