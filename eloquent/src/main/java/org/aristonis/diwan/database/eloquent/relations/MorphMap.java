package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.eloquent.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The global map between morph aliases and model classes, a port of Laravel's
 * {@code Relation::morphMap}, {@code enforceMorphMap} and {@code requireMorphMap}.
 *
 * <p>A polymorphic relation stores a type string beside the id. With a map, the string is an alias
 * such as {@code posts}; without one it is the class name. The map is a strict two-way map: one
 * alias per class and one class per alias, so {@link #getMorphAlias(Class)} is never ambiguous.
 * Aliases compare as exact strings, so {@code "0"} is a valid alias. A {@code null} or empty alias
 * is refused.
 *
 * <p>The map is required by default: a class with no alias fails with
 * {@link ClassMorphViolationException} instead of writing its class name. {@link
 * #requireMorphMap(boolean)} with {@code false} lets a class without an alias use its class name.
 * In that mode a stored class name is loaded through the model's class loader without
 * initialising it, and refused unless the class is a {@link Model}. This is the library's only
 * reflective class lookup.
 *
 * <p>The state is one immutable snapshot behind a volatile field. Reads take one volatile read and
 * see a consistent map; writes are synchronised and replace the snapshot. The map is shared by
 * every database manager in the JVM.
 *
 * <p>The map holds strong {@link Class} references and cannot unregister an entry. In a
 * redeploying container the old class loader stays pinned, and registering a reloaded class under
 * the same alias is refused because the alias is still bound to the old class. A map per database
 * manager is planned.
 */
public final class MorphMap {

    /** One immutable state of the map. */
    private static final class Snapshot {

        /** Alias to class, in insertion order. */
        final Map<String, Class<? extends Model>> aliases;

        /** Class to alias. */
        final Map<Class<? extends Model>, String> classes;

        /** Whether a class with no alias is refused. */
        final boolean required;

        Snapshot(Map<String, Class<? extends Model>> aliases, Map<Class<? extends Model>, String> classes,
                boolean required) {
            this.aliases = aliases;
            this.classes = classes;
            this.required = required;
        }
    }

    private static final Snapshot INITIAL = new Snapshot(Collections.emptyMap(), Collections.emptyMap(), true);

    private static volatile Snapshot snapshot = INITIAL;

    private static final Object LOCK = new Object();

    private MorphMap() {
    }

    /**
     * Adds aliases to the map; an alias already bound to the same class is left alone.
     *
     * <p>The call is all or nothing: when one entry is refused, none is added.
     *
     * @param map the aliases and their classes, never {@code null}, with no {@code null} key, empty
     *     key or {@code null} class
     * @throws NullPointerException if {@code map}, a class or an alias is {@code null}
     * @throws IllegalArgumentException if an alias is empty, an alias is already bound to another
     *     class, or a class is already under another alias
     */
    public static void morphMap(Map<String, Class<? extends Model>> map) {
        Objects.requireNonNull(map, "map");
        Map<String, Class<? extends Model>> given = new LinkedHashMap<>(map);
        synchronized (LOCK) {
            snapshot = merge(snapshot, given, snapshot.required);
        }
    }

    /**
     * Adds aliases to the map and requires every class to have one.
     *
     * @param map the aliases and their classes, never {@code null}
     * @throws NullPointerException if {@code map}, a class or an alias is {@code null}
     * @throws IllegalArgumentException if an alias is empty, an alias is already bound to another
     *     class, or a class is already under another alias
     */
    public static void enforceMorphMap(Map<String, Class<? extends Model>> map) {
        Objects.requireNonNull(map, "map");
        Map<String, Class<? extends Model>> given = new LinkedHashMap<>(map);
        synchronized (LOCK) {
            snapshot = merge(snapshot, given, true);
        }
    }

    /**
     * Sets whether a class with no alias is refused.
     *
     * @param required {@code true} to refuse a class with no alias, {@code false} to let it use its
     *     class name
     */
    public static void requireMorphMap(boolean required) {
        synchronized (LOCK) {
            Snapshot current = snapshot;
            snapshot = new Snapshot(current.aliases, current.classes, required);
        }
    }

    /**
     * Tells whether a class with no alias is refused.
     *
     * @return {@code true} when the map is required, which is the default
     */
    public static boolean requiresMorphMap() {
        return snapshot.required;
    }

    /**
     * Returns the class an alias names.
     *
     * @param alias the alias, may be {@code null}
     * @return the class, or empty when the alias is not in the map
     */
    public static Optional<Class<? extends Model>> getMorphedModel(String alias) {
        return alias == null ? Optional.empty() : Optional.ofNullable(snapshot.aliases.get(alias));
    }

    /**
     * Returns the alias of a class.
     *
     * @param type the class, may be {@code null}
     * @return the alias, or empty when the class is not in the map
     */
    public static Optional<String> getMorphAlias(Class<? extends Model> type) {
        return type == null ? Optional.empty() : Optional.ofNullable(snapshot.classes.get(type));
    }

    /**
     * Returns a snapshot of the map.
     *
     * @return the aliases and their classes in insertion order, unmodifiable and never
     *     {@code null}; later changes to the map do not show in it
     */
    public static Map<String, Class<? extends Model>> morphMap() {
        return snapshot.aliases;
    }

    /**
     * Returns the type string a model class writes: its alias, or, when the map is not required,
     * its class name.
     *
     * <p>Called by a model's {@code getMorphClass()} through the internal bridge.
     *
     * @param type the model class, never {@code null}
     * @return the alias or the class name, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws ClassMorphViolationException if the class has no alias and the map is required; the
     *     exception names the class
     */
    static String aliasOf(Class<? extends Model> type) {
        Objects.requireNonNull(type, "type");
        Snapshot current = snapshot;
        String alias = current.classes.get(type);
        if (alias != null) {
            return alias;
        }
        if (current.required) {
            throw new ClassMorphViolationException(type.getName());
        }
        return type.getName();
    }

    /**
     * Returns the model class a stored type string names.
     *
     * <p>An alias in the map wins. Otherwise, when the map is required, the string is refused.
     * When it is not required, the string is read as a class name and loaded through the given
     * loader without initialising it; the class must be a {@link Model} or the string is refused
     * before any class code runs.
     *
     * <p>Called by the model layer through the internal bridge.
     *
     * @param type the stored type string, never {@code null}
     * @param loader the class loader to load a class name through, or {@code null} for the bootstrap
     *     loader, as {@code Class.forName} reads it
     * @return the model class, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws ClassMorphViolationException if the string names no alias and the map is required,
     *     names no loadable class, or names a class that is not a {@link Model}
     */
    static Class<? extends Model> resolve(String type, ClassLoader loader) {
        Objects.requireNonNull(type, "type");
        Snapshot current = snapshot;
        Class<? extends Model> mapped = current.aliases.get(type);
        if (mapped != null) {
            return mapped;
        }
        if (current.required) {
            throw new ClassMorphViolationException(type);
        }
        Class<?> loaded;
        try {
            loaded = Class.forName(type, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new ClassMorphViolationException(type);
        }
        if (!Model.class.isAssignableFrom(loaded)) {
            throw new ClassMorphViolationException(type);
        }
        return loaded.asSubclass(Model.class);
    }

    /**
     * Returns both maps of the current snapshot together, for tests that check they agree.
     *
     * @return the alias-to-class map at index 0 and the class-to-alias map at index 1, both
     *     from one snapshot
     */
    static Object[] snapshotMapsForTests() {
        Snapshot current = snapshot;
        return new Object[] {current.aliases, current.classes};
    }

    /** Restores the initial state: empty map, required. Used between tests. */
    static void resetForTests() {
        synchronized (LOCK) {
            snapshot = INITIAL;
        }
    }

    private static Snapshot merge(Snapshot base, Map<String, Class<? extends Model>> given, boolean required) {
        Map<String, Class<? extends Model>> aliases = new LinkedHashMap<>(base.aliases);
        Map<Class<? extends Model>, String> classes = new LinkedHashMap<>(base.classes);
        for (Map.Entry<String, Class<? extends Model>> entry : given.entrySet()) {
            String alias = Objects.requireNonNull(entry.getKey(), "alias");
            Class<? extends Model> type = Objects.requireNonNull(entry.getValue(), "class");
            if (alias.isEmpty()) {
                throw new IllegalArgumentException("A morph alias must not be empty.");
            }
            Class<? extends Model> boundClass = aliases.get(alias);
            if (boundClass != null && !boundClass.equals(type)) {
                throw new IllegalArgumentException("Morph alias [" + alias + "] is already bound to ["
                        + boundClass.getName() + "].");
            }
            String boundAlias = classes.get(type);
            if (boundAlias != null && !boundAlias.equals(alias)) {
                throw new IllegalArgumentException("Class [" + type.getName() + "] is already under morph alias ["
                        + boundAlias + "].");
            }
            aliases.put(alias, type);
            classes.put(type, alias);
        }
        return new Snapshot(Collections.unmodifiableMap(aliases), Collections.unmodifiableMap(classes), required);
    }
}
