package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The per-class options of an eager loaded {@link MorphTo}: which relations and relation counts to
 * load on the owners of one model class, and a constraint for that class's query. Laravel's
 * {@code morphWith}, {@code morphWithCount} and {@code constrain} on {@code MorphTo}.
 *
 * <p>A class is given as a model class or as a morph alias, and is resolved through
 * {@link MorphMap} when the option is registered, so an unknown alias throws at once and not when
 * the query runs. The wildcard {@code "*"} is refused. Calling an option twice for one class
 * appends the relations and chains the constraints, the first call running first.
 *
 * <p>Options are kept by class name, so a lookup by the resolved owner class finds them whichever class
 * loader loaded it.
 *
 * <p>Single use while building, not safe to share across threads.
 */
public final class MorphEagerOptions {

    private static final String WILDCARD = "*";

    private final Map<String, List<String>> relations = new LinkedHashMap<>();

    private final Map<String, List<String>> counts = new LinkedHashMap<>();

    private final Map<String, Consumer<EloquentBuilder<?>>> constraints = new LinkedHashMap<>();

    /**
     * Creates an empty set of options. Callers receive one from {@code withMorph(...)}.
     */
    MorphEagerOptions() {
    }

    /**
     * Loads relations on the owners of one class, as {@link EloquentBuilder#with(String...)}.
     *
     * @param classOrAlias a model class or a morph alias, never {@code null}
     * @param relations the relation paths to load on that class's owners, none {@code null}
     * @return this object
     * @throws NullPointerException if an argument or a path is {@code null}
     * @throws IllegalArgumentException if the key is {@code "*"}, not a string or a model class
     * @throws org.aristonis.diwan.database.eloquent.ClassMorphViolationException if the key names
     *     no model class in the morph map
     */
    public MorphEagerOptions morphWith(Object classOrAlias, String... relations) {
        append(this.relations, resolveKey(classOrAlias), relations);
        return this;
    }

    /**
     * Loads the number of related rows of each relation on the owners of one class, as
     * {@link EloquentBuilder#withCount(String...)}.
     *
     * @param classOrAlias a model class or a morph alias, never {@code null}
     * @param relations the relation names to count on that class's owners, none {@code null}
     * @return this object
     * @throws NullPointerException if an argument or a name is {@code null}
     * @throws IllegalArgumentException if the key is {@code "*"}, not a string or a model class
     * @throws org.aristonis.diwan.database.eloquent.ClassMorphViolationException if the key names
     *     no model class in the morph map
     */
    public MorphEagerOptions morphWithCount(Object classOrAlias, String... relations) {
        append(counts, resolveKey(classOrAlias), relations);
        return this;
    }

    /**
     * Constrains the query that loads the owners of one class. It runs after the constraint given
     * to the whole relation.
     *
     * @param classOrAlias a model class or a morph alias, never {@code null}
     * @param constraint the constraint on that class's query, never {@code null}
     * @return this object
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the key is {@code "*"}, not a string or a model class
     * @throws org.aristonis.diwan.database.eloquent.ClassMorphViolationException if the key names
     *     no model class in the morph map
     */
    public MorphEagerOptions constrain(Object classOrAlias, Consumer<EloquentBuilder<?>> constraint) {
        Objects.requireNonNull(constraint, "constraint");
        chain(constraints, resolveKey(classOrAlias), constraint);
        return this;
    }

    /** The relations to load on one class's owners, in call order. */
    List<String> relationsFor(Class<? extends Model> type) {
        return copyOf(relations.get(type.getName()));
    }

    /** The relations to count on one class's owners, in call order. */
    List<String> countsFor(Class<? extends Model> type) {
        return copyOf(counts.get(type.getName()));
    }

    /** The chained constraint for one class, or {@code null} when there is none. */
    Consumer<EloquentBuilder<?>> constraintFor(Class<? extends Model> type) {
        return constraints.get(type.getName());
    }

    /** Adds every option of {@code other} after this object's own, in {@code other}'s order. */
    void mergeFrom(MorphEagerOptions other) {
        for (Map.Entry<String, List<String>> entry : other.relations.entrySet()) {
            appendAll(relations, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, List<String>> entry : other.counts.entrySet()) {
            appendAll(counts, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Consumer<EloquentBuilder<?>>> entry : other.constraints.entrySet()) {
            chain(constraints, entry.getKey(), entry.getValue());
        }
    }

    private static String resolveKey(Object classOrAlias) {
        Objects.requireNonNull(classOrAlias, "classOrAlias");
        if (classOrAlias instanceof Class) {
            Class<?> type = (Class<?>) classOrAlias;
            if (!Model.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("Morph option key [" + type.getName() + "] is not a model class");
            }
            // Throws when the morph map is required and the class has no alias.
            MorphMap.aliasOf(type.asSubclass(Model.class));
            return type.getName();
        }
        if (classOrAlias instanceof String) {
            String alias = (String) classOrAlias;
            if (WILDCARD.equals(alias)) {
                throw new IllegalArgumentException("A morph option needs a class or an alias, not [" + WILDCARD + "]");
            }
            return MorphMap.resolve(alias, loader()).getName();
        }
        throw new IllegalArgumentException("Morph option key must be a model class or an alias, got ["
                + classOrAlias.getClass().getName() + "]");
    }

    // Only a class-name string (map not required) is loaded, and the options are keyed by the loaded class's
    // name, so a different loader at lookup time cannot make an option miss. An alias needs no loader.
    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : MorphEagerOptions.class.getClassLoader();
    }

    private static void append(Map<String, List<String>> target, String key,
            String[] names) {
        Objects.requireNonNull(names, "relations");
        List<String> added = new ArrayList<>(names.length);
        for (String name : names) {
            added.add(Objects.requireNonNull(name, "relation"));
        }
        appendAll(target, key, added);
    }

    private static void appendAll(Map<String, List<String>> target, String key,
            List<String> names) {
        List<String> existing = target.get(key);
        if (existing == null) {
            existing = new ArrayList<>();
            target.put(key, existing);
        }
        existing.addAll(names);
    }

    private static void chain(Map<String, Consumer<EloquentBuilder<?>>> target,
            String key, Consumer<EloquentBuilder<?>> constraint) {
        Consumer<EloquentBuilder<?>> existing = target.get(key);
        target.put(key, existing == null ? constraint : existing.andThen(constraint));
    }

    private static List<String> copyOf(List<String> names) {
        return names == null ? Collections.<String>emptyList() : new ArrayList<>(names);
    }
}
