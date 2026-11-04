package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.UnregisteredModelException;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;
import org.aristonis.diwan.database.eloquent.relations.BelongsTo;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.HasOne;
import org.aristonis.diwan.database.eloquent.relations.LazyLoadingViolationException;
import org.aristonis.diwan.database.eloquent.relations.Relation;
import org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException;
import org.aristonis.diwan.database.support.Inflector;

/**
 * The relation layer of {@link Model}: the protected builders a model's typed relation methods
 * call, lookup by name, and the relations loaded on this instance.
 *
 * <p>A model declares each relation twice: as a typed method that calls a builder, and by name in
 * {@link #relations()}, which {@link #relation(String)}, eager loading and existence queries read:
 *
 * <pre>{@code
 * public HasMany<Comment> comments() {
 *     return hasMany(Comment.class);
 * }
 * }</pre>
 *
 * <p>Loaded relations are kept by name in the order they were set. Each value is a model, a
 * {@link ModelCollection} or {@code null}, and a loaded {@code null} counts as loaded.
 * {@link #getAttribute(String)} never falls back to a relation; read one through
 * {@link #getRelationValue(String)}, {@link #related(String)} or {@link #relatedMany(String)},
 * which load it lazily the first time.
 */
abstract class ModelRelations extends ModelSerialization {

    private final Map<String, Object> loadedRelations = new LinkedHashMap<>();

    private boolean isLazyLoadingPrevented;

    ModelRelations() {
    }

    // ---- builders

    /**
     * Builds a one-to-one relation to the model that holds this model's key, with Laravel's
     * default keys: the foreign key is {@code getForeignKey()}, such as {@code user_id}, and the
     * local key is {@code getKeyName()}.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}; it must have a
     *     {@link ModelQuery} entry
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry; the
     *     message names the class
     */
    protected <R extends Model> HasOne<R> hasOne(Class<R> related) {
        return hasOne(related, getForeignKey());
    }

    /**
     * Builds a one-to-one relation to the model that holds this model's key, with the local key
     * {@code getKeyName()}.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param foreignKey the related table's column holding this model's key, bare or qualified,
     *     never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> HasOne<R> hasOne(Class<R> related, String foreignKey) {
        return hasOne(related, foreignKey, getKeyName());
    }

    /**
     * Builds a one-to-one relation to the model that holds this model's key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param foreignKey the related table's column holding the local key, bare or qualified,
     *     never {@code null}
     * @param localKey this model's column the foreign key points at, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> HasOne<R> hasOne(Class<R> related, String foreignKey, String localKey) {
        Objects.requireNonNull(foreignKey, "foreignKey");
        Objects.requireNonNull(localKey, "localKey");
        return new HasOne<>(newRelatedQuery(related), self(), foreignKey, localKey);
    }

    /**
     * Builds a one-to-many relation to the models that hold this model's key, with Laravel's
     * default keys: the foreign key is {@code getForeignKey()} and the local key
     * {@code getKeyName()}.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry; the
     *     message names the class
     */
    protected <R extends Model> HasMany<R> hasMany(Class<R> related) {
        return hasMany(related, getForeignKey());
    }

    /**
     * Builds a one-to-many relation to the models that hold this model's key, with the local key
     * {@code getKeyName()}.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param foreignKey the related table's column holding this model's key, bare or qualified,
     *     never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> HasMany<R> hasMany(Class<R> related, String foreignKey) {
        return hasMany(related, foreignKey, getKeyName());
    }

    /**
     * Builds a one-to-many relation to the models that hold this model's key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param foreignKey the related table's column holding the local key, bare or qualified,
     *     never {@code null}
     * @param localKey this model's column the foreign key points at, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> HasMany<R> hasMany(Class<R> related, String foreignKey, String localKey) {
        Objects.requireNonNull(foreignKey, "foreignKey");
        Objects.requireNonNull(localKey, "localKey");
        return new HasMany<>(newRelatedQuery(related), self(), foreignKey, localKey);
    }

    /**
     * Builds an inverse relation to the model whose key this model holds, with the owner key the
     * related model's {@code getKeyName()}.
     *
     * @apiNote Laravel's one-argument form derives the foreign key from the calling method's name,
     *     which Java cannot read without reflection, so the foreign key is always given.
     * @param <R> the related model class
     * @param related the owner model class, never {@code null}
     * @param foreignKey this model's column holding the owner's key, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry; the
     *     message names the class
     */
    protected <R extends Model> BelongsTo<R> belongsTo(Class<R> related, String foreignKey) {
        return belongsTo(related, foreignKey, null, null);
    }

    /**
     * Builds an inverse relation to the model whose key this model holds.
     *
     * @param <R> the related model class
     * @param related the owner model class, never {@code null}
     * @param foreignKey this model's column holding the owner's key, never {@code null}
     * @param ownerKey the owner's column the foreign key points at, or {@code null} for the
     *     owner's key column
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code foreignKey} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> BelongsTo<R> belongsTo(Class<R> related, String foreignKey, String ownerKey) {
        return belongsTo(related, foreignKey, ownerKey, null);
    }

    /**
     * Builds an inverse relation to the model whose key this model holds, under an explicit
     * relation name: Laravel's four-argument form.
     *
     * <p>{@code associate} and {@code dissociate} set the loaded relation under this name. Without
     * one, the name comes from {@link #relations()}, and when that map has more than one relation
     * with the same owner class and keys, they set the key only.
     *
     * @param <R> the related model class
     * @param related the owner model class, never {@code null}
     * @param foreignKey this model's column holding the owner's key, never {@code null}
     * @param ownerKey the owner's column the foreign key points at, or {@code null} for the
     *     owner's key column
     * @param relation the relation name, or {@code null} to take it from {@link #relations()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code foreignKey} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> BelongsTo<R> belongsTo(Class<R> related, String foreignKey, String ownerKey,
            String relation) {
        Objects.requireNonNull(foreignKey, "foreignKey");
        EloquentBuilder<R> query = newRelatedQuery(related);
        String owner = ownerKey != null ? ownerKey : query.getModel().getKeyName();
        return new BelongsTo<>(query, self(), foreignKey, owner, relation);
    }

    private <R extends Model> EloquentBuilder<R> newRelatedQuery(Class<R> related) {
        Objects.requireNonNull(related, "related");
        ModelQuery<?> found = ModelQuery.registered(related);
        if (found == null) {
            throw new UnregisteredModelException(related);
        }
        // Safe: the registry keys each entry by its own model class.
        @SuppressWarnings("unchecked")
        ModelQuery<R> entry = (ModelQuery<R>) found;
        R instance = entry.newModel();
        Model parent = self();
        // Laravel's newRelatedInstance: a related model with no connection of its own runs on the
        // parent's. A registered entry is never bound, so its connection name decides.
        if (!instance.isBound() && instance.getConnectionName() == null && parent.isBound()) {
            entry = entry.on(parent.getQueryFactory());
            instance = entry.newModel();
        }
        return new EloquentBuilder<>(entry, instance);
    }

    // ---- lookup and instances

    /**
     * Returns the relation of a name in {@code relations()}, built for this model and
     * unconstrained; its public query members add the lazy constraints on first use.
     *
     * <p>The first call on a model class checks the class's whole map once.
     *
     * @param name the relation name, never {@code null}
     * @return a new relation, its name set, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws RelationNotFoundException if the map has no relation of that name; the message lists
     *     the names it has
     * @throws org.aristonis.diwan.database.eloquent.relations.InvalidRelationException if the
     *     class's map fails its check, now or on an earlier call
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Relation<?, ?> relation(String name) {
        Objects.requireNonNull(name, "name");
        Relation.ensureInitialised();
        return Access.relations().resolve(self(), name);
    }

    /**
     * Returns a new, empty instance of this model's class, bound to the same connection and clock,
     * with no attributes and no relations: Laravel's {@code newInstance()}.
     *
     * @param <M> the model's own class, inferred from the assignment
     * @return the new instance, not in the database, never {@code null}
     * @throws ClassCastException at the call site if {@code M} is not this model's class
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    @SuppressWarnings("unchecked")
    public <M extends Model> M newInstance() {
        return (M) self().newInstanceLike();
    }

    // ---- loaded relation state

    /**
     * Returns a loaded relation, without loading it.
     *
     * @param name the relation name, never {@code null}
     * @return the model, the collection, or {@code null} when {@code null} was loaded
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalStateException if the relation is not loaded
     */
    public Object getRelation(String name) {
        Objects.requireNonNull(name, "name");
        if (!loadedRelations.containsKey(name)) {
            throw new IllegalStateException("Relation [" + name + "] is not loaded on model ["
                    + getClass().getName() + "]");
        }
        return loadedRelations.get(name);
    }

    /**
     * Returns whether a relation is loaded; a loaded {@code null} counts, so it is not loaded
     * again.
     *
     * @param name the relation name, never {@code null}
     * @return whether the relation is loaded
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public boolean relationLoaded(String name) {
        return loadedRelations.containsKey(Objects.requireNonNull(name, "name"));
    }

    /**
     * Sets a loaded relation, replacing any value under that name.
     *
     * @param name the relation name, never {@code null}
     * @param value a {@link Model}, a {@link ModelCollection} or {@code null}
     * @return this model
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the value is of another type; the message names the
     *     type, never the value
     */
    public Model setRelation(String name, Object value) {
        Objects.requireNonNull(name, "name");
        checkRelationValue(name, value);
        loadedRelations.put(name, value);
        return self();
    }

    /**
     * Forgets a loaded relation; nothing happens when it is not loaded.
     *
     * @param name the relation name, never {@code null}
     * @return this model
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Model unsetRelation(String name) {
        loadedRelations.remove(Objects.requireNonNull(name, "name"));
        return self();
    }

    /**
     * Returns the loaded relations by name.
     *
     * @return an unmodifiable view in the order the relations were set, never {@code null}; it
     *     follows later changes to this model
     */
    public Map<String, Object> getRelations() {
        return Collections.unmodifiableMap(loadedRelations);
    }

    /**
     * Replaces every loaded relation. Each value is checked before any is set, so a bad one
     * leaves the relations as they were.
     *
     * @param relations the relations by name, never {@code null}; copied, in its iteration order
     * @return this model
     * @throws NullPointerException if {@code relations} or one of its names is {@code null}
     * @throws IllegalArgumentException if a value is not a {@link Model}, a
     *     {@link ModelCollection} or {@code null}
     */
    public Model setRelations(Map<String, ?> relations) {
        Objects.requireNonNull(relations, "relations");
        for (Map.Entry<String, ?> entry : relations.entrySet()) {
            checkRelationValue(Objects.requireNonNull(entry.getKey(), "relation name"), entry.getValue());
        }
        loadedRelations.clear();
        loadedRelations.putAll(relations);
        return self();
    }

    /**
     * Returns a copy of this model without its loaded relations: same class, connection,
     * attributes, originals, changes and existence. The attribute values are shared, not copied,
     * and changes to the hidden, visible and appended lists of this instance are not carried.
     *
     * @return the copy, never {@code null}
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model withoutRelations() {
        Model source = self();
        Model copy = source.newInstanceLike();
        copy.restoreAttributes(source.snapshotAttributes());
        if (source.exists()) {
            copy.markExisting();
        }
        return copy;
    }

    /**
     * Returns a relation's value: the loaded one, else the result of its lazy query, which is then
     * kept as loaded.
     *
     * @param name the relation name, never {@code null}
     * @return a model, a collection, or {@code null} for a single relation with no match and no
     *     default
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws RelationNotFoundException if the relation is not loaded and {@link #relations()}
     *     has no relation of that name
     * @throws org.aristonis.diwan.database.eloquent.relations.InvalidRelationException if the
     *     class's map fails its check
     * @throws LazyLoadingViolationException if the relation is not loaded, this model came in a
     *     result of more than one row and {@link ModelQuery#preventsLazyLoading()} is on; no query
     *     has run
     */
    public Object getRelationValue(String name) {
        Objects.requireNonNull(name, "name");
        if (loadedRelations.containsKey(name)) {
            return loadedRelations.get(name);
        }
        Relation<?, ?> relation = relation(name);
        Model model = self();
        if (isLazyLoadingPrevented && model.exists() && !model.wasRecentlyCreated()
                && ModelQuery.preventsLazyLoading()) {
            throw new LazyLoadingViolationException(getClass(), name);
        }
        Object value = relation.getResults();
        setRelation(name, value);
        return value;
    }

    /**
     * Returns a single relation's model, loading it lazily as {@code getRelationValue()}.
     *
     * @param <R> the related model class, inferred from the assignment
     * @param name the relation name, never {@code null}
     * @return the model, or {@code null} when there is none
     * @throws ClassCastException if the relation holds a collection (the message names the
     *     relation), or at the call site if {@code R} is not the related class
     * @throws RelationNotFoundException as {@link #getRelationValue(String)}
     */
    @SuppressWarnings("unchecked")
    public <R extends Model> R related(String name) {
        Object value = getRelationValue(name);
        if (value != null && !(value instanceof Model)) {
            throw new ClassCastException("Relation [" + name + "] on model [" + getClass().getName()
                    + "] holds a collection; read it with relatedMany");
        }
        return (R) value;
    }

    /**
     * Returns a many relation's models, loading them lazily as {@code getRelationValue()}.
     *
     * @param <R> the related model class, inferred from the assignment
     * @param name the relation name, never {@code null}
     * @return the models, never {@code null} unless {@code null} was set as the relation
     * @throws ClassCastException if the relation holds a single model (the message names the
     *     relation), or at the call site if {@code R} is not the related class
     * @throws RelationNotFoundException as {@link #getRelationValue(String)}
     */
    @SuppressWarnings("unchecked")
    public <R extends Model> ModelCollection<R> relatedMany(String name) {
        Object value = getRelationValue(name);
        if (value != null && !(value instanceof ModelCollection)) {
            throw new ClassCastException("Relation [" + name + "] on model [" + getClass().getName()
                    + "] holds a single model; read it with related");
        }
        return (ModelCollection<R>) value;
    }

    // ---- eager loading onto this model

    /**
     * Eager loads relations onto this model, replacing any loaded value: Laravel's
     * {@code $model->load(...)}. Paths are as {@link EloquentBuilder#with(String...)}.
     *
     * @param relations the relation paths, none {@code null}
     * @return this model
     * @throws NullPointerException if a path is {@code null}
     * @throws IllegalArgumentException if a {@code name:columns} path lists an empty column
     * @throws RelationNotFoundException if a path names no relation; checked before any query
     */
    public Model load(String... relations) {
        return eagerLoadSelf(WithParser.parse(relations), false);
    }

    /**
     * Eager loads one relation onto this model, letting a callback constrain its query.
     *
     * @param relation the relation path, never {@code null}
     * @param constraint the constraint on the relation's query, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument is {@code null}
     * @throws RelationNotFoundException if the path names no relation
     */
    public Model load(String relation, Consumer<EloquentBuilder<?>> constraint) {
        return load(Collections.singletonMap(Objects.requireNonNull(relation, "relation"),
                Objects.requireNonNull(constraint, "constraint")));
    }

    /**
     * Eager loads relations onto this model, each with a callback constraining its query.
     *
     * @param relations the constraints by relation path, no key or value {@code null}
     * @return this model
     * @throws NullPointerException if the map, a path or a constraint is {@code null}
     * @throws RelationNotFoundException if a path names no relation
     */
    public Model load(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        return eagerLoadSelf(WithParser.parse(relations), false);
    }

    /**
     * Eager loads the relations not loaded yet on this model and, level by level, on the models
     * already loaded under it. A loaded {@code null} counts as loaded and is not read again.
     *
     * @param relations the relation paths, none {@code null}
     * @return this model
     * @throws NullPointerException if a path is {@code null}
     * @throws RelationNotFoundException if a path names no relation
     */
    public Model loadMissing(String... relations) {
        return eagerLoadSelf(WithParser.parse(relations), true);
    }

    private Model eagerLoadSelf(Map<String, Consumer<EloquentBuilder<?>>> relations, boolean missingOnly) {
        EloquentRelationQueries.eagerLoad(Collections.singletonList(self()), relations, missingOnly);
        return self();
    }

    // ---- package-private: serialisation hook

    @Override
    final void appendRelations(Map<String, Object> result, VisitPath path) {
        boolean isSnake = snakeRelationKeys();
        for (Map.Entry<String, Object> entry : loadedRelations.entrySet()) {
            String name = entry.getKey();
            if (isShown(name)) {
                result.put(isSnake ? Inflector.snake(name) : name, relationToPlain(entry.getValue(), path));
            }
        }
    }

    private static Object relationToPlain(Object value, VisitPath path) {
        if (value instanceof Model) {
            return ((Model) value).toMap(path);
        }
        if (value instanceof ModelCollection) {
            ModelCollection<?> models = (ModelCollection<?>) value;
            List<Map<String, Object>> maps = new ArrayList<>(models.size());
            for (Model model : models) {
                maps.add(model.toMap(path));
            }
            return maps;
        }
        return null;
    }

    // ---- package-private: lazy-loading mark and refresh

    final void markLazyLoadingPrevented() {
        isLazyLoadingPrevented = true;
    }

    final boolean isLazyLoadingPrevented() {
        return isLazyLoadingPrevented;
    }

    final Model reloadLoadedRelations() {
        if (loadedRelations.isEmpty()) {
            return self();
        }
        // Laravel's refresh(): load(the loaded names), which replaces each loaded value.
        return load(loadedRelations.keySet().toArray(new String[0]));
    }

    private void checkRelationValue(String name, Object value) {
        if (value == null || value instanceof Model || value instanceof ModelCollection) {
            return;
        }
        throw new IllegalArgumentException("Relation [" + name + "] on model [" + getClass().getName()
                + "] must be a Model, a ModelCollection or null, not " + value.getClass().getName());
    }
}
