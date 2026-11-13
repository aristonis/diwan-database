package org.aristonis.diwan.database.eloquent.factory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * Builds the attribute rows of a factory: the definition, then each state layer, with lazy values
 * resolved.
 */
final class FactoryRows {

    private static final int DAYS_BEFORE_NOW = 1;

    private FactoryRows() {
    }

    /**
     * Builds the attributes of the model at one position.
     *
     * @param spec the settings of the factory
     * @param index the position of the model, from zero
     * @param <M> the model type
     * @return a new mutable map of attributes, never {@code null}
     */
    static <M extends Model> Map<String, Object> row(FactorySpec<M> spec, int index) {
        String modelClass = spec.query().getModelClass().getName();
        Map<String, Object> attrs = new LinkedHashMap<>();
        putLayer(attrs, Objects.requireNonNull(spec.definition().get(), "definition"), modelClass);
        for (FactorySpec.State state : spec.states()) {
            Map<String, Object> layer = state.apply(spec, snapshot(attrs), index);
            putLayer(attrs, Objects.requireNonNull(layer, "state"), modelClass);
        }
        return attrs;
    }

    /**
     * Builds the attributes of the models at positions zero up to the count, none below one.
     *
     * @param spec the settings of the factory
     * @param count the number of models, below 1 for none
     * @param <M> the model type
     * @return a new mutable list of new mutable maps, empty when {@code count} is below 1
     */
    static <M extends Model> List<Map<String, Object>> rows(FactorySpec<M> spec, int count) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(row(spec, i));
        }
        return out;
    }

    /**
     * Returns the layer that stamps the deleted-at column, checked when a row is built so a later
     * connection is honoured.
     *
     * @param given the moment of deletion, or {@code null} for one day before now
     * @return the layer, never {@code null}
     */
    static FactorySpec.State trashedState(Instant given) {
        return (spec, soFar, index) -> {
            Model probe = spec.effectiveQuery().newModel();
            if (!probe.isSoftDeletable()) {
                throw new IllegalStateException("Model [" + probe.getClass().getName()
                        + "] does not use soft deletes, so trashed() has no deleted-at column");
            }
            Instant moment = given != null ? given : probe.freshTimestamp().minus(DAYS_BEFORE_NOW, ChronoUnit.DAYS);
            return Collections.singletonMap(probe.getDeletedAtColumn(), probe.fromDateTime(moment));
        };
    }

    private static void putLayer(Map<String, Object> attrs, Map<String, Object> layer, String modelClass) {
        for (Map.Entry<String, Object> entry : layer.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "attribute name");
            attrs.put(name, resolve(entry.getValue(), attrs, name, modelClass));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(Object value, Map<String, Object> attrs, String name, String modelClass) {
        try {
            if (value instanceof Supplier) {
                return ((Supplier<Object>) value).get();
            }
            if (value instanceof Function) {
                return ((Function<Map<String, Object>, Object>) value).apply(snapshot(attrs));
            }
            return value;
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Attribute [" + name + "] of model [" + modelClass
                    + "] could not be resolved: the value function did not accept the attribute map", e);
        }
    }

    private static Map<String, Object> snapshot(Map<String, Object> attrs) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
    }
}
