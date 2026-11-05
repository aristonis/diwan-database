package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;

/**
 * Eager loads relations onto parent models: Laravel's {@code Builder::eagerLoadRelations},
 * {@code eagerLoadRelation} and {@code relationsNestedUnder}, plus {@code loadMissing}.
 *
 * <p>Every dot path is checked before the first query, so an unknown name throws with nothing
 * run. Each top-level relation is built on a fresh instance of the parent class, set
 * to its empty value on every parent, and loaded through the relation's own
 * {@code eagerResults}, which owns chunking, the limit refusal and the match column.
 * Nested paths run once over the results of all chunks, grouped by result class, and the eager
 * loads a constraint set with {@code with(...)} join them.
 *
 * <p>Stateless; safe to use from any thread. The models it is given are not.
 */
final class EagerLoader {

    /** The constraint of a path that was named without one. */
    static final Consumer<EloquentBuilder<?>> NO_OP = query -> {
        // Nothing to add: the relation's own eager filter is the whole query.
    };

    private EagerLoader() {
    }

    /**
     * Eager loads the given relations onto parents of one class.
     *
     * @param parents the parent models, never {@code null}; empty does nothing
     * @param eager the constraints by dot path, never {@code null}; empty does nothing
     * @throws IllegalArgumentException if the parents are not all of one class
     * @throws RelationNotFoundException if a path names an unknown relation; no query has run
     * @throws IllegalStateException if a constraint sets a limit or an offset
     */
    static void load(List<? extends Model> parents, Map<String, Consumer<EloquentBuilder<?>>> eager) {
        Objects.requireNonNull(parents, "parents");
        Objects.requireNonNull(eager, "eager");
        if (parents.isEmpty() || eager.isEmpty()) {
            return;
        }
        Model sample = requireOneClass(parents);
        validate(sample, eager.keySet());
        for (String name : topLevelNames(eager.keySet())) {
            loadOne(parents, sample, name, constraintOf(eager, name), nestedUnder(eager, name));
        }
    }

    /**
     * Eager loads, level by level, the relations not loaded yet. A relation loaded as
     * {@code null} or empty counts as loaded and is not queried again.
     *
     * @param parents the parent models, never {@code null}; empty does nothing
     * @param eager the constraints by dot path, never {@code null}; empty does nothing
     * @throws IllegalArgumentException if the parents are not all of one class
     * @throws RelationNotFoundException if a path names an unknown relation; no query has run
     * @throws IllegalStateException if a constraint sets a limit or an offset
     */
    static void loadMissing(List<? extends Model> parents, Map<String, Consumer<EloquentBuilder<?>>> eager) {
        Objects.requireNonNull(parents, "parents");
        Objects.requireNonNull(eager, "eager");
        if (parents.isEmpty() || eager.isEmpty()) {
            return;
        }
        Model sample = requireOneClass(parents);
        validate(sample, eager.keySet());
        for (String name : topLevelNames(eager.keySet())) {
            List<Model> missing = new ArrayList<>();
            for (Model parent : parents) {
                if (!parent.relationLoaded(name)) {
                    missing.add(parent);
                }
            }
            if (!missing.isEmpty()) {
                load(missing, Collections.singletonMap(name, constraintOf(eager, name)));
            }
            Map<String, Consumer<EloquentBuilder<?>>> nested = nestedUnder(eager, name);
            if (!nested.isEmpty()) {
                for (List<Model> group : byClass(loadedValues(parents, name))) {
                    loadMissing(group, nested);
                }
            }
        }
    }

    private static void loadOne(List<? extends Model> parents, Model sample, String name,
            Consumer<EloquentBuilder<?>> constraint, Map<String, Consumer<EloquentBuilder<?>>> nested) {
        Relation<?, ?> relation = RelationRegistry.of(sample)
                .resolve(name, Access.eloquent().newInstanceLike(sample));
        loadWith(relation, parents, name, constraint, nested);
    }

    private static <R extends Model> void loadWith(Relation<R, ?> relation, List<? extends Model> parents,
            String name, Consumer<EloquentBuilder<?>> constraint, Map<String, Consumer<EloquentBuilder<?>>> nested) {
        relation.initRelation(parents, name);
        List<Object> keys = relation.eagerKeys(parents);
        if (keys.isEmpty()) {
            // No parent has a key: the empty values stand and no deeper level runs.
            return;
        }
        EagerContext context = new EagerContext();
        List<R> results = relation.eagerResults(keys, constraint, context);
        Map<String, Consumer<EloquentBuilder<?>>> deeper = merge(nested, context.eagerLoads());
        if (!deeper.isEmpty() && !results.isEmpty()) {
            // Once over every chunk's results, not per chunk.
            for (List<Model> group : byClass(results)) {
                load(group, deeper);
            }
        }
        relation.match(parents, results, name);
    }

    /**
     * Walks every dot path through the relation map of each class it reaches, stopping at a
     * polymorphic relation whose class is only known per row.
     */
    private static void validate(Model sample, Collection<String> paths) {
        for (String path : paths) {
            Model current = sample;
            for (String segment : path.split("\\.", -1)) {
                Relation<?, ?> relation = RelationRegistry.of(current).resolve(segment, current);
                if (relation.isPolymorphic()) {
                    break;
                }
                current = relation.getRelated();
            }
        }
    }

    private static Set<String> topLevelNames(Collection<String> paths) {
        // "a.b" alone still loads "a"; the parser normally adds it with no constraint.
        Set<String> names = new LinkedHashSet<>();
        for (String path : paths) {
            int dot = path.indexOf('.');
            names.add(dot < 0 ? path : path.substring(0, dot));
        }
        return names;
    }

    private static Consumer<EloquentBuilder<?>> constraintOf(Map<String, Consumer<EloquentBuilder<?>>> eager,
            String name) {
        Consumer<EloquentBuilder<?>> constraint = eager.get(name);
        return constraint == null ? NO_OP : constraint;
    }

    /** Laravel's {@code relationsNestedUnder}: the paths below {@code name}, prefix removed. */
    private static Map<String, Consumer<EloquentBuilder<?>>> nestedUnder(
            Map<String, Consumer<EloquentBuilder<?>>> eager, String name) {
        String prefix = name + ".";
        Map<String, Consumer<EloquentBuilder<?>>> nested = new LinkedHashMap<>();
        for (Map.Entry<String, Consumer<EloquentBuilder<?>>> entry : eager.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                nested.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return nested;
    }

    /** Joins the constraint's eager loads to the nested map; a name in both runs both, in order. */
    private static Map<String, Consumer<EloquentBuilder<?>>> merge(Map<String, Consumer<EloquentBuilder<?>>> nested,
            Map<String, Consumer<EloquentBuilder<?>>> captured) {
        if (captured.isEmpty()) {
            return nested;
        }
        Map<String, Consumer<EloquentBuilder<?>>> merged = new LinkedHashMap<>(nested);
        for (Map.Entry<String, Consumer<EloquentBuilder<?>>> entry : captured.entrySet()) {
            Consumer<EloquentBuilder<?>> existing = merged.get(entry.getKey());
            merged.put(entry.getKey(), existing == null ? entry.getValue()
                    : MorphEagerConstraint.combine(existing, entry.getValue()));
        }
        return merged;
    }

    private static Model requireOneClass(List<? extends Model> parents) {
        Model sample = Objects.requireNonNull(parents.get(0), "parent");
        Class<?> type = sample.getClass();
        for (Model parent : parents) {
            Objects.requireNonNull(parent, "parent");
            if (parent.getClass() != type) {
                throw new IllegalArgumentException("Cannot eager load onto models of different classes: ["
                        + type.getName() + "] and [" + parent.getClass().getName() + "]");
            }
        }
        return sample;
    }

    private static List<List<Model>> byClass(List<? extends Model> models) {
        Map<Class<?>, List<Model>> groups = new LinkedHashMap<>();
        for (Model model : models) {
            List<Model> group = groups.get(model.getClass());
            if (group == null) {
                group = new ArrayList<>();
                groups.put(model.getClass(), group);
            }
            group.add(model);
        }
        return new ArrayList<>(groups.values());
    }

    /** The non-null loaded values of a relation, collections flattened. */
    private static List<Model> loadedValues(List<? extends Model> parents, String name) {
        List<Model> values = new ArrayList<>();
        for (Model parent : parents) {
            if (!parent.relationLoaded(name)) {
                continue;
            }
            Object value = parent.getRelation(name);
            if (value instanceof Model) {
                values.add((Model) value);
            } else if (value instanceof Collection) {
                for (Object item : (Collection<?>) value) {
                    values.add((Model) item);
                }
            }
        }
        return values;
    }
}
