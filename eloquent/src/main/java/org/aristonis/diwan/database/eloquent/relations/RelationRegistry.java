package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.EloquentAccess;
import org.aristonis.diwan.database.eloquent.internal.UnregisteredModelException;

/**
 * The checked {@code relations()} map of one model class, cached on the class's
 * {@code ModelQuery} entry.
 *
 * <p>The first use of a class builds every relation of its map once, on a bare copy of the
 * calling model, and checks it: the map's class is the model's class or a superclass of
 * it, each name is non-empty and has no {@code .} or {@code :}, and each factory returns
 * a relation (a factory whose related class is unregistered fails while building). The
 * check also records, for each {@link BelongsTo}, its related class, foreign key and owner key
 * against its name, so an unnamed {@code BelongsTo} can find its name.
 *
 * <p>Deeply immutable and safe to share across threads. The slot is written once by
 * {@code compareAndSet}; a thread that loses the race drops its own copy. A failed check
 * is cached too, except one caused by a related class whose {@code ModelQuery} entry is not
 * registered yet, which is checked again on the next use: every later use throws a new {@link InvalidRelationException} with the same
 * message and the first failure as its cause, and the factories are not run again.
 */
final class RelationRegistry {

    private final Class<?> modelClass;

    private final RelationMap<?> map;

    private final Map<String, List<String>> belongsToNames;

    private final InvalidRelationException failure;

    private RelationRegistry(Class<?> modelClass, RelationMap<?> map, Map<String, List<String>> belongsToNames,
            InvalidRelationException failure) {
        this.modelClass = modelClass;
        this.map = map;
        this.belongsToNames = belongsToNames;
        this.failure = failure;
    }

    /**
     * Returns the checked registry of a model's class, checking it on first use.
     *
     * @param model a model of the class, never {@code null}
     * @return the registry, never {@code null}
     * @throws InvalidRelationException if the class's map failed its check, now or before
     * @throws IllegalStateException if the model's class has no {@code ModelQuery} entry
     */
    static RelationRegistry of(Model model) {
        Objects.requireNonNull(model, "model");
        AtomicReference<Object> slot = Access.eloquent().relationRegistrySlot(model);
        Object current = slot.get();
        if (current == null) {
            slot.compareAndSet(null, check(model));
            current = slot.get();
        }
        return ((RelationRegistry) current).orThrow();
    }

    /**
     * Builds and checks the registry of a model's class, catching the failure into a failed
     * registry.
     *
     * @param model a model of the class, never {@code null}
     * @return the checked or failed registry, never {@code null}
     * @throws InvalidRelationException if a factory names a model class with no {@code ModelQuery}
     *     entry yet; this failure is not cached
     */
    static RelationRegistry check(Model model) {
        try {
            return build(model);
        } catch (UncachedFailure e) {
            // A related class not registered yet is not a map error: the next use checks again.
            throw e.failure;
        } catch (InvalidRelationException e) {
            return failed(model.getClass(), e);
        }
    }

    /**
     * Returns a registry that rethrows a check failure on every use.
     *
     * @param modelClass the model class, never {@code null}
     * @param failure the first failure, never {@code null}
     * @return the failed registry, never {@code null}
     */
    static RelationRegistry failed(Class<?> modelClass, InvalidRelationException failure) {
        return new RelationRegistry(Objects.requireNonNull(modelClass, "modelClass"), RelationMap.empty(),
                Collections.<String, List<String>>emptyMap(), Objects.requireNonNull(failure, "failure"));
    }

    private static RelationRegistry build(Model model) {
        EloquentAccess access = Access.eloquent();
        Class<?> modelClass = model.getClass();
        Model sample = access.newInstanceLike(model);
        RelationMap<?> map = access.relationMap(sample);
        if (!map.modelClass().isAssignableFrom(modelClass)) {
            throw new InvalidRelationException(modelClass, null,
                    "relations() returns the map of [" + map.modelClass().getName() + "]");
        }
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (String name : map.names()) {
            checkName(modelClass, name);
            Relation<?, ?> relation = buildOne(map, name, sample, modelClass);
            if (relation instanceof BelongsTo) {
                BelongsTo<?> belongsTo = (BelongsTo<?>) relation;
                String key = indexKey(belongsTo.getRelated().getClass(), belongsTo.getForeignKeyName(),
                        belongsTo.getOwnerKeyName());
                index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(name);
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : index.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return new RelationRegistry(modelClass, map, Collections.unmodifiableMap(frozen), null);
    }

    private static void checkName(Class<?> modelClass, String name) {
        if (name.isEmpty() || name.indexOf('.') >= 0 || name.indexOf(':') >= 0) {
            throw new InvalidRelationException(modelClass, name,
                    "a relation name must be non-empty and contain no '.' or ':'");
        }
    }

    private static Relation<?, ?> buildOne(RelationMap<?> map, String name, Model sample, Class<?> modelClass) {
        try {
            return map.resolve(name, sample);
        } catch (InvalidRelationException e) {
            throw e;
        } catch (UnregisteredModelException e) {
            // The related class has no entry yet. Its name is safe to show; the failure is
            // not cached, so registering the class later makes the map usable.
            throw new UncachedFailure(new InvalidRelationException(modelClass, name,
                    "the related model [" + e.getModelClassName() + "] has no ModelQuery.of(...) entry", e));
        } catch (RuntimeException e) {
            // A factory that throws fails the check. The message names the exception class only,
            // since a user message may hold a value; the exception stays attached as the cause.
            throw new InvalidRelationException(modelClass, name,
                    "building it failed with " + e.getClass().getName(), e);
        }
    }

    /** Carries a check failure that must not be cached out of {@link #build(Model)}. */
    private static final class UncachedFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient InvalidRelationException failure;

        UncachedFailure(InvalidRelationException failure) {
            super(null, failure, false, false);
            this.failure = failure;
        }
    }

    private static String indexKey(Class<?> related, String foreignKey, String ownerKey) {
        return related.getName() + '\u0000' + foreignKey + '\u0000' + ownerKey;
    }

    private RelationRegistry orThrow() {
        if (failure != null) {
            throw new InvalidRelationException(failure);
        }
        return this;
    }

    /**
     * Returns the model class this registry was checked for.
     *
     * @return the model class, never {@code null}
     */
    Class<?> modelClass() {
        return modelClass;
    }

    /**
     * Returns the relation names in map order.
     *
     * @return the names, unmodifiable, never {@code null}
     */
    List<String> names() {
        return map.names();
    }

    /**
     * Builds a named relation for a model, the name set on it.
     *
     * @param name the relation name, never {@code null}
     * @param model the model the relation is for, never {@code null}
     * @return the relation, unconstrained, never {@code null}
     * @throws RelationNotFoundException if the map has no such name
     */
    Relation<?, ?> resolve(String name, Model model) {
        return map.resolve(name, model);
    }

    /**
     * Returns the map name of the only {@code BelongsTo} with the given related class and keys.
     *
     * @param related the owner class, never {@code null}
     * @param foreignKey the child's foreign key, never {@code null}
     * @param ownerKey the owner key, never {@code null}
     * @return the name, or {@code null} when none or more than one relation matches (ambiguous,
     *     so {@code associate} sets the key only)
     */
    String belongsToName(Class<?> related, String foreignKey, String ownerKey) {
        List<String> names = belongsToNames.get(indexKey(related, foreignKey, ownerKey));
        return names != null && names.size() == 1 ? names.get(0) : null;
    }
}
