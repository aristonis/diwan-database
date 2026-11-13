package org.aristonis.diwan.database.eloquent.factory;

import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The last pass over a merged attribute map: a nested factory value becomes the key of a model it
 * creates or recycles, a model value becomes its key, and every other value stays.
 */
final class FactoryExpansion {

    private FactoryExpansion() {
    }

    /**
     * Expands the nested values of a merged attribute map, in key order.
     *
     * <p>A factory value the settings exclude becomes {@code null} and creates nothing. The
     * exclusion is read from the settings of the factory being expanded only.
     *
     * @param attrs the merged attributes, left unchanged, never {@code null}
     * @param spec the settings of the factory being expanded, never {@code null}
     * @param run the frame of the run, never {@code null}
     * @return a new mutable map with the same keys, never {@code null}
     * @throws IllegalStateException if a factory value is met in a database-free run, or a recycled
     *     model is not saved, or the nesting is too deep
     */
    static Map<String, Object> expand(Map<String, Object> attrs, FactorySpec<?> spec, FactoryRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            out.put(entry.getKey(), expandValue(entry.getKey(), entry.getValue(), spec, run));
        }
        return out;
    }

    private static Object expandValue(String name, Object value, FactorySpec<?> spec, FactoryRun run) {
        if (value instanceof Model) {
            return ((Model) value).getKey();
        }
        if (!(value instanceof Factory)) {
            return value;
        }
        FactorySpec<?> nested = ((Factory<?>) value).bound();
        Class<?> nestedClass = nested.query().getModelClass();
        if (spec.relations().excludes(nestedClass, name)) {
            return null;
        }
        if (run.isDatabaseFree()) {
            throw new IllegalStateException("raw() of [" + spec.query().getModelClass().getName()
                    + "] cannot create a [" + nestedClass.getName() + "]; use make() or create()");
        }
        Model recycled = run.recycled(nestedClass);
        if (recycled != null) {
            return recycled.getKey();
        }
        return createdKey(nested.withConnectionIfAbsent(spec.connection()), run);
    }

    private static <M extends Model> Object createdKey(FactorySpec<M> nested, FactoryRun run) {
        String className = nested.query().getModelClass().getName();
        return FactoryBuild.createOne(nested, run.nested(null, className), 0).getKey();
    }
}
