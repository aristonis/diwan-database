package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.InvalidRelationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;

/**
 * The inverse of a has-one or has-many relation: the model this relation is built on, the
 * child, holds the foreign key, and it points at a column of the related owner, such as a post's
 * author. Laravel's {@code BelongsTo}.
 *
 * <p>The lazy query is {@code where owner.ownerKey = ?} with the child's foreign key bound, read
 * with {@code first()}. A child whose foreign key is {@code null} runs no query. Eager keys
 * are the children's foreign keys, inlined when the owner key is the owner's integer primary
 * key. Every child pointing at the same owner gets the same owner instance. Single use, not safe
 * to share across threads (see {@link Relation}).
 *
 * <p>Only {@link #getResults()} skips the query for a {@code null} foreign key. {@link #getQuery()}
 * and {@link #get()} on such a child build and run {@code where owner.ownerKey is null}, as
 * Laravel does; that query matches no row when the owner key is a primary key.
 *
 * @param <R> the related (owner) model class
 */
public final class BelongsTo<R extends Model> extends Relation<R, BelongsTo<R>> {

    private final String foreignKey;

    private final String ownerKey;

    private DefaultModel<R> defaultModel = DefaultModel.none();

    /**
     * Creates the relation. Models normally build it through {@code belongsTo(...)}.
     *
     * @param query an unconstrained query on the owner model, never {@code null}
     * @param child the model holding the foreign key, never {@code null}
     * @param foreignKey the child's column pointing at the owner, never {@code null}
     * @param ownerKey the owner's column the foreign key holds, never {@code null}
     * @param relationName the relation's name, used by {@code associate} and {@code dissociate};
     *     {@code null} to take it from the model's {@code relations()} map
     * @throws NullPointerException if {@code query}, {@code child}, {@code foreignKey} or
     *     {@code ownerKey} is {@code null}
     */
    public BelongsTo(EloquentBuilder<R> query, Model child, String foreignKey, String ownerKey,
            String relationName) {
        super(query, child, relationName);
        this.foreignKey = Objects.requireNonNull(foreignKey, "foreignKey");
        this.ownerKey = Objects.requireNonNull(ownerKey, "ownerKey");
    }

    /**
     * Returns the owner of the child, or the default when there is none. A child whose foreign
     * key is {@code null} runs no query.
     *
     * @return the owner, the default, or {@code null}
     */
    @Override
    public R getResults() {
        if (getChild().getAttribute(foreignKey) == null) {
            return defaultFor(getChild());
        }
        return getQuery().first().orElseGet(() -> defaultFor(getChild()));
    }

    /**
     * Returns the model holding the foreign key; the same as {@link #getParent()}.
     *
     * @return the child model, never {@code null}
     */
    public Model getChild() {
        return getParent();
    }

    /**
     * Returns the child's column pointing at the owner.
     *
     * @return the foreign key, never {@code null}
     */
    public String getForeignKeyName() {
        return foreignKey;
    }

    /**
     * Returns the child's foreign key qualified by the child's table, such as
     * {@code posts.author_id}.
     *
     * @return the qualified foreign key, never {@code null}
     */
    public String getQualifiedForeignKeyName() {
        return getChild().qualifyColumn(foreignKey);
    }

    /**
     * Returns the owner's column the foreign key holds.
     *
     * @return the owner key, never {@code null}
     */
    public String getOwnerKeyName() {
        return ownerKey;
    }

    /**
     * Returns the owner key qualified by the owner's table, such as {@code users.id}.
     *
     * @return the qualified owner key, never {@code null}
     */
    public String getQualifiedOwnerKeyName() {
        return getRelated().qualifyColumn(ownerKey);
    }

    /**
     * Makes the relation return a bare owner model instead of {@code null} when the child has no
     * owner. The default is never saved.
     *
     * @return this relation
     */
    public BelongsTo<R> withDefault() {
        defaultModel = DefaultModel.bare();
        return this;
    }

    /**
     * Makes the relation return an owner model filled with the attributes, ignoring the fillable
     * list, instead of {@code null}. The default is never saved.
     *
     * @param attributes the attributes, never {@code null}, copied
     * @return this relation
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public BelongsTo<R> withDefault(Map<String, ?> attributes) {
        defaultModel = DefaultModel.filled(attributes);
        return this;
    }

    /**
     * Makes the relation return a bare owner model, passed to the callback with the child,
     * instead of {@code null}. The default is never saved.
     *
     * @param callback receives the new default and the child, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public BelongsTo<R> withDefault(BiConsumer<? super R, Model> callback) {
        defaultModel = DefaultModel.configured(callback);
        return this;
    }

    /**
     * Points the child at an owner: the child's foreign key takes the owner's key value and the
     * owner is set as the loaded relation. Nothing is saved.
     *
     * <p>The relation name is the one given to the 4-arg {@code belongsTo} or by the model's
     * {@code relations()} map; failing that, the only map entry with this owner class, foreign
     * key and owner key. When there is none, or more than one, only the key is set.
     *
     * @param model the owner, never {@code null}
     * @return the child
     * @throws InvalidRelationException if the name is looked up and the child's
     *     {@code relations()} map fails its check
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalStateException if the owner has no owner key value, such as before it is
     *     saved
     */
    public Model associate(R model) {
        Objects.requireNonNull(model, "model");
        Object ownerValue = model.getAttribute(ownerKey);
        if (ownerValue == null) {
            throw new IllegalStateException("Cannot associate [" + model.getClass().getName() + "] with ["
                    + getChild().getClass().getName() + "]: the owner has no [" + ownerKey
                    + "] value; save the owner first");
        }
        Model child = getChild();
        child.setAttribute(foreignKey, ownerValue);
        String name = writeName();
        if (name != null) {
            child.setRelation(name, model);
        }
        return child;
    }

    /**
     * Points the child at an owner by its key: the child's foreign key takes the value and the
     * loaded relation, if any, is unset, since the owner is not known. Nothing is saved.
     *
     * @param id the owner key value; {@code null} clears the foreign key
     * @return the child
     * @throws InvalidRelationException if the name is looked up and the child's
     *     {@code relations()} map fails its check
     * @throws IllegalArgumentException if {@code id} is a model; pass it typed as the owner
     *     class to use {@link #associate(Model)}
     */
    public Model associate(Object id) {
        if (id instanceof Model) {
            throw new IllegalArgumentException("associate(Object) takes a key, got a model ["
                    + id.getClass().getName() + "]; pass it as the owner class");
        }
        Model child = getChild();
        child.setAttribute(foreignKey, id);
        String name = writeName();
        if (name != null) {
            child.unsetRelation(name);
        }
        return child;
    }

    /**
     * Detaches the child from its owner: the foreign key becomes {@code null} and the loaded
     * relation is set to {@code null}. Nothing is saved.
     *
     * @return the child
     * @throws InvalidRelationException if the name is looked up and the child's
     *     {@code relations()} map fails its check
     */
    public Model dissociate() {
        Model child = getChild();
        child.setAttribute(foreignKey, null);
        String name = writeName();
        if (name != null) {
            child.setRelation(name, null);
        }
        return child;
    }

    private String writeName() {
        String name = getRelationName();
        if (name != null) {
            return name;
        }
        // The registry index, null when unknown or ambiguous (key only then).
        return RelationRegistry.of(getChild()).belongsToName(getRelated().getClass(), foreignKey, ownerKey);
    }

    /**
     * Sets the owner's update timestamp to now, as {@link Relation#touch()} does. A child whose
     * foreign key is {@code null} has no owner, so nothing runs.
     *
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the update
     */
    @Override
    public void touch() {
        if (getChild().getAttribute(foreignKey) == null) {
            return;
        }
        super.touch();
    }

    @Override
    boolean supportsTouch() {
        return true;
    }

    @Override
    BelongsTo<R> self() {
        return this;
    }

    @Override
    void addConstraints() {
        baseQuery().where(getQualifiedOwnerKeyName(), "=", getChild().getAttribute(foreignKey));
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        for (Model child : parents) {
            child.setRelation(name, defaultFor(child));
        }
    }

    @Override
    List<Object> eagerKeys(List<? extends Model> parents) {
        return EagerKeys.<Model>collect(parents, (Model child) -> child.getAttribute(foreignKey));
    }

    @Override
    void prepareEagerQuery(EloquentBuilder<R> eagerQuery, List<Object> chunk) {
        whereEagerKeys(eagerQuery, getQualifiedOwnerKeyName(), chunk);
    }

    @Override
    boolean inlinesEagerKeys() {
        return isIntegerKey(getRelated(), ownerKey);
    }

    @Override
    String eagerMatchColumn() {
        return getQualifiedOwnerKeyName();
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        Map<Object, R> dictionary = new HashMap<>();
        for (R result : results) {
            Object key = EagerKeys.normalise(result.getAttribute(ownerKey));
            if (key != null) {
                dictionary.put(key, result);
            }
        }
        for (Model child : parents) {
            Object key = EagerKeys.normalise(child.getAttribute(foreignKey));
            R owner = key == null ? null : dictionary.get(key);
            if (owner != null) {
                child.setRelation(name, owner);
            }
        }
    }

    @Override
    EloquentBuilder<R> existenceQuery(EloquentBuilder<?> parentQuery, AliasCounter aliases) {
        Objects.requireNonNull(parentQuery, "parentQuery");
        Objects.requireNonNull(aliases, "aliases");
        String childKey = parentKeyAgainst(parentQuery, foreignKey);
        if (RelationExistence.isSelfRelation(baseQuery(), parentQuery)) {
            String alias = aliasRelatedTable(aliases);
            baseQuery().whereColumn(alias + "." + ownerKey, "=", childKey);
            return existenceBaseQuery();
        }
        baseQuery().whereColumn(childKey, "=", getQualifiedOwnerKeyName());
        return existenceBaseQuery();
    }

    private R defaultFor(Model child) {
        return defaultModel.createFor(child, ignored -> getRelated().newInstance());
    }
}
