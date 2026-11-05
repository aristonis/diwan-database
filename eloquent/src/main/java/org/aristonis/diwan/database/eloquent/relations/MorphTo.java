package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;

/**
 * The inverse of a morph-one or morph-many relation: the model this relation is built on, the
 * child, holds a type column and an id column, and together they point at an owner of any model
 * class, such as a comment's post or video. Laravel's {@code MorphTo}.
 *
 * <p>The type column holds a morph alias or a class name, resolved through {@code MorphMap}. The
 * lazy query is {@code where owner.ownerKey = ?} on the resolved class, read with
 * {@code first()}. A child with no type or no id runs no query: it gets the default or
 * {@code null}. For such a child the relation is built over a placeholder query on the child's
 * own class, which is never run.
 *
 * <p>Eager loading groups the children by type and runs one chunked query per resolved class.
 * Every type is resolved before the first query, so an unknown type throws with nothing run.
 * Children are matched by the resolved class and the id's normal form, so Post 5 and Video 5 are
 * kept apart, and an id stored as text matches a numeric key. A constraint given to the eager
 * load runs on every class, so it must only name columns every class has. The eager loads such a
 * constraint sets, and the nested paths below a morph-to, are applied per result class; a path
 * must therefore exist on every class that appears.
 *
 * <p>Single use, not safe to share across threads (see {@link Relation}). The eager key buckets
 * live on the instance for one load.
 *
 * @param <R> the related model class; {@code Model} for a relation that can reach any class
 */
public final class MorphTo<R extends Model> extends Relation<R, MorphTo<R>> {

    private final String morphType;

    private final String foreignKey;

    private final String ownerKey;

    private DefaultModel<R> defaultModel = DefaultModel.none();

    /** The ids to load by type, built by {@code eagerKeys} for the {@code eagerResults} that follows. */
    private Map<String, List<Object>> typeBuckets = Collections.emptyMap();

    /**
     * Creates the relation. Models normally build it through {@code morphTo(...)}.
     *
     * @param query an unconstrained query on the resolved class when the child's type is set, or
     *     a placeholder query on the child's own class when it is not, never {@code null}
     * @param child the model holding the type and id columns, never {@code null}
     * @param type the child's column holding the owner's type, never {@code null}
     * @param id the child's column holding the owner's key, never {@code null}
     * @param ownerKey the owner's column the id holds, or {@code null} for each owner class's
     *     own key column
     * @param relationName the relation's name, used by {@code associate} and {@code dissociate};
     *     {@code null} when the relation is built outside a model's {@code relations()} map
     * @throws NullPointerException if {@code query}, {@code child}, {@code type} or {@code id}
     *     is {@code null}
     */
    public MorphTo(EloquentBuilder<R> query, Model child, String type, String id, String ownerKey,
            String relationName) {
        super(query, child, relationName);
        this.morphType = Objects.requireNonNull(type, "type");
        this.foreignKey = Objects.requireNonNull(id, "id");
        this.ownerKey = ownerKey;
    }

    /**
     * Returns the owner of the child, or the default when there is none. A child with no type or
     * no id runs no query.
     *
     * @return the owner, the default, or {@code null}
     */
    @Override
    public R getResults() {
        if (!hasOwnerReference(getChild())) {
            return defaultFor(getChild());
        }
        return getQuery().first().orElseGet(() -> defaultFor(getChild()));
    }

    /**
     * Returns the model holding the type and id columns; the same as {@link #getParent()}.
     *
     * @return the child model, never {@code null}
     */
    public Model getChild() {
        return getParent();
    }

    /**
     * Returns the child's column holding the owner's type.
     *
     * @return the type column, never {@code null}
     */
    public String getMorphType() {
        return morphType;
    }

    /**
     * Returns the child's column holding the owner's key.
     *
     * @return the id column, never {@code null}
     */
    public String getForeignKeyName() {
        return foreignKey;
    }

    /**
     * Returns the owner column the id holds, when one was given.
     *
     * @return the owner key, or empty when each owner class's own key column is used
     */
    public Optional<String> getOwnerKeyName() {
        return Optional.ofNullable(ownerKey);
    }

    /**
     * Makes the relation return a bare model instead of {@code null} when the child has no
     * owner. The default is never saved.
     *
     * @return this relation
     */
    public MorphTo<R> withDefault() {
        defaultModel = DefaultModel.bare();
        return this;
    }

    /**
     * Makes the relation return a model filled with the attributes, ignoring the fillable list,
     * instead of {@code null}. The default is never saved.
     *
     * @param attributes the attributes, never {@code null}, copied
     * @return this relation
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public MorphTo<R> withDefault(Map<String, ?> attributes) {
        defaultModel = DefaultModel.filled(attributes);
        return this;
    }

    /**
     * Makes the relation return a bare model, passed to the callback with the child, instead of
     * {@code null}. The default is never saved.
     *
     * @param callback receives the new default and the child, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public MorphTo<R> withDefault(BiConsumer<? super R, Model> callback) {
        defaultModel = DefaultModel.configured(callback);
        return this;
    }

    /**
     * Points the child at an owner: the id column takes the owner's key value, the type column
     * takes the owner's morph class, and the owner is set as the loaded relation. Nothing is
     * saved.
     *
     * @param model the owner, never {@code null}
     * @return the child
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalStateException if the owner has no key value, such as before it is saved
     * @throws org.aristonis.diwan.database.eloquent.ClassMorphViolationException if the owner's
     *     class has no alias and the morph map is required
     */
    public Model associate(Model model) {
        Objects.requireNonNull(model, "model");
        Object ownerValue = model.getAttribute(ownerKeyFor(model));
        if (ownerValue == null) {
            throw new IllegalStateException("Cannot associate [" + model.getClass().getName() + "] with ["
                    + getChild().getClass().getName() + "]: the owner has no [" + ownerKeyFor(model)
                    + "] value; save the owner first");
        }
        Model child = getChild();
        child.setAttribute(foreignKey, ownerValue);
        child.setAttribute(morphType, model.getMorphClass());
        String name = getRelationName();
        if (name != null) {
            child.setRelation(name, model);
        }
        return child;
    }

    /**
     * Detaches the child from its owner: the type and id columns become {@code null} and the
     * loaded relation is set to {@code null}. Nothing is saved.
     *
     * @return the child
     */
    public Model dissociate() {
        Model child = getChild();
        child.setAttribute(foreignKey, null);
        child.setAttribute(morphType, null);
        String name = getRelationName();
        if (name != null) {
            child.setRelation(name, null);
        }
        return child;
    }

    /**
     * Sets the owner's update timestamp to now, as {@link Relation#touch()} does. A child with
     * no type or no id has no owner, so nothing runs.
     *
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    @Override
    public void touch() {
        if (!hasOwnerReference(getChild())) {
            return;
        }
        super.touch();
    }

    @Override
    boolean supportsTouch() {
        return true;
    }

    @Override
    boolean isPolymorphic() {
        return true;
    }

    @Override
    MorphTo<R> self() {
        return this;
    }

    @Override
    void addConstraints() {
        if (!hasOwnerReference(getChild())) {
            return;
        }
        baseQuery().where(getRelated().qualifyColumn(ownerKeyFor(getRelated())), "=",
                getChild().getAttribute(foreignKey));
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        for (Model child : parents) {
            child.setRelation(name, defaultFor(child));
        }
    }

    @Override
    List<Object> eagerKeys(List<? extends Model> parents) {
        Map<String, List<Model>> childrenByType = new LinkedHashMap<>();
        for (Model child : parents) {
            String type = typeOf(child);
            if (type == null || child.getAttribute(foreignKey) == null) {
                continue;
            }
            List<Model> group = childrenByType.get(type);
            if (group == null) {
                group = new ArrayList<>();
                childrenByType.put(type, group);
            }
            group.add(child);
        }
        Map<String, List<Object>> buckets = new LinkedHashMap<>();
        List<Object> all = new ArrayList<>();
        for (Map.Entry<String, List<Model>> entry : childrenByType.entrySet()) {
            List<Object> ids = EagerKeys.<Model>collect(entry.getValue(),
                    (Model child) -> child.getAttribute(foreignKey));
            buckets.put(entry.getKey(), ids);
            all.addAll(ids);
        }
        typeBuckets = buckets;
        return all;
    }

    @Override
    List<R> eagerResults(List<Object> keys, Consumer<EloquentBuilder<?>> constraint, EagerContext context) {
        Objects.requireNonNull(constraint, "constraint");
        Objects.requireNonNull(context, "context");
        // Every type is resolved before the first query, so an unknown one throws with nothing run.
        Map<Class<? extends Model>, Map<Object, Object>> idsByClass = resolveBuckets();
        List<Model> all = new ArrayList<>();
        for (Map.Entry<Class<? extends Model>, Map<Object, Object>> entry : idsByClass.entrySet()) {
            // One context: only the first capture counts, which is the first class's.
            List<Object> ids = new ArrayList<>(entry.getValue().values());
            all.addAll(loadClass(entry.getKey(), ids, constraint, context));
        }
        // Safe: R is Model for every relation that reaches more than one class.
        @SuppressWarnings("unchecked")
        List<R> typed = (List<R>) (List<?>) all;
        return typed;
    }

    private Map<Class<? extends Model>, Map<Object, Object>> resolveBuckets() {
        ClassLoader loader = getChild().getClass().getClassLoader();
        Map<Class<? extends Model>, Map<Object, Object>> idsByClass = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : typeBuckets.entrySet()) {
            Class<? extends Model> type = MorphMap.resolve(entry.getKey(), loader);
            Map<Object, Object> ids = idsByClass.get(type);
            if (ids == null) {
                ids = new LinkedHashMap<>();
                idsByClass.put(type, ids);
            }
            for (Object id : entry.getValue()) {
                Object normal = EagerKeys.normalise(id);
                if (!ids.containsKey(normal)) {
                    ids.put(normal, id);
                }
            }
        }
        return idsByClass;
    }

    /** Loads one class's owners through a {@link BelongsTo} on that class, reusing its chunked path. */
    private <M extends Model> List<M> loadClass(Class<M> type, List<Object> ids,
            Consumer<EloquentBuilder<?>> constraint, EagerContext context) {
        M instance = Access.eloquent().newRegistered(type, getChild());
        EloquentBuilder<M> query = Access.eloquent().newQueryOn(instance);
        BelongsTo<M> helper = new BelongsTo<>(query, getChild(), foreignKey, ownerKeyFor(query.getModel()), null);
        return helper.eagerResults(ids, constraint, context);
    }

    @Override
    void prepareEagerQuery(EloquentBuilder<R> eagerQuery, List<Object> chunk) {
        whereEagerKeys(eagerQuery, getRelated().qualifyColumn(ownerKeyFor(getRelated())), chunk);
    }

    @Override
    boolean inlinesEagerKeys() {
        return isIntegerKey(getRelated(), ownerKeyFor(getRelated()));
    }

    @Override
    String eagerMatchColumn() {
        return getRelated().qualifyColumn(ownerKeyFor(getRelated()));
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        Map<Class<?>, Map<Object, Model>> dictionary = new HashMap<>();
        for (R result : results) {
            Object key = EagerKeys.normalise(result.getAttribute(ownerKeyFor(result)));
            if (key == null) {
                continue;
            }
            Map<Object, Model> owners = dictionary.get(result.getClass());
            if (owners == null) {
                owners = new HashMap<>();
                dictionary.put(result.getClass(), owners);
            }
            owners.put(key, result);
        }
        ClassLoader loader = getChild().getClass().getClassLoader();
        Map<String, Class<? extends Model>> resolved = new HashMap<>();
        for (Model child : parents) {
            String type = typeOf(child);
            Object key = EagerKeys.normalise(child.getAttribute(foreignKey));
            if (type == null || key == null) {
                continue;
            }
            Class<? extends Model> ownerClass = resolved.get(type);
            if (ownerClass == null) {
                ownerClass = MorphMap.resolve(type, loader);
                resolved.put(type, ownerClass);
            }
            Map<Object, Model> owners = dictionary.get(ownerClass);
            Model owner = owners == null ? null : owners.get(key);
            if (owner != null) {
                child.setRelation(name, owner);
            }
        }
    }

    @Override
    EloquentBuilder<R> existenceQuery(EloquentBuilder<?> parentQuery, AliasCounter aliases) {
        throw new UnsupportedOperationException("A morphTo relation [" + getRelationName()
                + "] has no single owner table to test for existence; use whereHasMorph instead");
    }

    private boolean hasOwnerReference(Model child) {
        return typeOf(child) != null && child.getAttribute(foreignKey) != null;
    }

    /** The child's type as text, or {@code null} when it is unset or empty. */
    private String typeOf(Model child) {
        Object value = child.getAttribute(morphType);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isEmpty() ? null : text;
    }

    private String ownerKeyFor(Model owner) {
        return ownerKey != null ? ownerKey : owner.getKeyName();
    }

    private R defaultFor(Model child) {
        return defaultModel.createFor(child, ignored -> getRelated().newInstance());
    }
}
