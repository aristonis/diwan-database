package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;

/**
 * A relation whose related rows hold the key: the related table has a foreign key column that
 * points at a column of the parent, its local key. Laravel's {@code HasOneOrMany}, the common
 * part of {@link HasOne} and {@link HasMany}.
 *
 * <p>The lazy query is {@code where related.fk = ? and related.fk is not null}, the parent's
 * local key bound. Eager keys come from the parents' local keys; they are inlined when the local
 * key is the parent's integer primary key, else bound and chunked by the grammar. Single use, not
 * safe to share across threads (see {@link Relation}).
 *
 * @param <R> the related model class
 * @param <S> the relation's own class
 */
public abstract class HasOneOrMany<R extends Model, S extends HasOneOrMany<R, S>> extends Relation<R, S> {

    private final String foreignKey;

    private final String foreignKeyName;

    private final String localKey;

    private String inverseName;

    HasOneOrMany(EloquentBuilder<R> query, Model parent, String foreignKey, String localKey) {
        super(query, parent, null);
        Objects.requireNonNull(foreignKey, "foreignKey");
        this.localKey = Objects.requireNonNull(localKey, "localKey");
        this.foreignKey = query.getModel().qualifyColumn(foreignKey);
        this.foreignKeyName = bare(foreignKey);
    }

    /**
     * Returns the foreign key column on the related table, qualified, such as
     * {@code comments.post_id}.
     *
     * @return the qualified foreign key, never {@code null}
     */
    public final String getQualifiedForeignKeyName() {
        return foreignKey;
    }

    /**
     * Returns the foreign key column on the related table, without the table.
     *
     * @return the bare foreign key, never {@code null}
     */
    public final String getForeignKeyName() {
        return foreignKeyName;
    }

    /**
     * Returns the parent's column the foreign key points at.
     *
     * @return the local key column, never {@code null}
     */
    public final String getLocalKeyName() {
        return localKey;
    }

    /**
     * Returns the parent's value of the local key.
     *
     * @return the parent key, or {@code null} when the parent has none, such as before it is saved
     */
    public final Object getParentKey() {
        return getParent().getAttribute(localKey);
    }

    /**
     * Returns a new related model filled with the attributes and holding the parent's key in
     * its foreign key. Nothing is saved.
     *
     * @param attributes the attributes, filled through the related model's fillable list,
     *     never {@code null}
     * @return the new unsaved related model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws IllegalStateException if the parent has no local key value, such as before it is
     *     saved
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     */
    public final R make(Map<String, ?> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        requireParentKey();
        R instance = getRelated().newInstance();
        instance.fill(attributes);
        setForeignAttributes(instance);
        linkInverse(instance, getParent());
        return instance;
    }

    /**
     * Sets the parent's key in the model's foreign key and saves the model. A save vetoed by a
     * hook leaves the model unsaved; check {@link Model#exists()}.
     *
     * @param model the related model, never {@code null}
     * @return the same model
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalStateException if the parent has no local key value, or the model has no
     *     connection
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the statement
     */
    public final R save(R model) {
        Objects.requireNonNull(model, "model");
        requireParentKey();
        setForeignAttributes(model);
        // Linked before the save, as Laravel's setForeignAttributesForCreate, so hooks see the parent.
        linkInverse(model, getParent());
        model.save();
        return model;
    }

    /**
     * Saves each model in order, as {@link #save(Model)} does. No transaction is opened: when one
     * model fails, the models before it stay saved and the exception is thrown.
     *
     * @param models the related models, never {@code null}, no {@code null} element
     * @return the models, in order, never {@code null}
     * @throws NullPointerException if {@code models} or an element is {@code null}
     * @throws IllegalStateException if the parent has no local key value
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final List<R> saveMany(Collection<? extends R> models) {
        Objects.requireNonNull(models, "models");
        requireParentKey();
        List<R> saved = new ArrayList<>(models.size());
        for (R model : models) {
            saved.add(save(model));
        }
        return Collections.unmodifiableList(saved);
    }

    /**
     * Makes a related model as {@link #make(Map)} does and saves it.
     *
     * @param attributes the attributes, filled through the related model's fillable list,
     *     never {@code null}
     * @return the saved related model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws IllegalStateException if the parent has no local key value
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the statement
     */
    public final R create(Map<String, ?> attributes) {
        R instance = make(attributes);
        instance.save();
        return instance;
    }

    /**
     * Creates one related model per record, in order, as {@link #create(Map)} does. No
     * transaction is opened: when one record fails, the ones before it stay saved.
     *
     * @param records the attribute maps, never {@code null}, no {@code null} element
     * @return the saved related models, in order, never {@code null}
     * @throws NullPointerException if {@code records} or an element is {@code null}
     * @throws IllegalStateException if the parent has no local key value
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final List<R> createMany(List<? extends Map<String, ?>> records) {
        Objects.requireNonNull(records, "records");
        requireParentKey();
        List<R> created = new ArrayList<>(records.size());
        for (Map<String, ?> record : records) {
            created.add(create(record));
        }
        return Collections.unmodifiableList(created);
    }

    // ---- chaperone

    /**
     * Sets the parent on every child this relation reads or writes, under the inverse relation
     * name guessed from the related model's relations: Laravel's {@code chaperone()}. The child
     * then answers {@code related(inverse)} with the same parent instance, without a query.
     *
     * <p>Candidates, first known one wins: the foreign key before the parent's key name in camel
     * case ({@code user_id} gives {@code user}), the same for the parent's default foreign key,
     * the parent's simple class name in camel case, {@code owner}, and {@code parent} when both
     * sides are the same class. Linking runs on {@code make}, {@code save}, {@code saveMany},
     * {@code create}, {@code createMany}, lazy reads, eager matches (each child gets its own
     * parent) and, for a {@link HasOne}, the default model. It adds no query.
     *
     * @apiNote On Android, R8 renames classes, so the class-name candidates may not match; pass
     *     the name with {@link #chaperone(String)}. Serializing a linked graph terminates: a model
     *     already on the path is written with its attributes only.
     * @return this relation
     * @throws RelationNotFoundException if no candidate is a relation of the related model
     */
    public final S chaperone() {
        inverseName = InverseRelations.guess(this);
        return self();
    }

    /**
     * Sets the parent on every child this relation reads or writes, under the given inverse
     * relation name, as {@link #chaperone()} does.
     *
     * @param inverseRelation the related model's relation that points back at the parent, never
     *     {@code null}
     * @return this relation
     * @throws NullPointerException if {@code inverseRelation} is {@code null}
     * @throws RelationNotFoundException if the related model has no relation of that name
     */
    public final S chaperone(String inverseRelation) {
        Objects.requireNonNull(inverseRelation, "inverseRelation");
        inverseName = InverseRelations.check(this, inverseRelation);
        return self();
    }

    /**
     * Sets the parent on every child under the guessed inverse name; the same as
     * {@link #chaperone()}.
     *
     * @return this relation
     * @throws RelationNotFoundException if no candidate is a relation of the related model
     */
    public final S inverse() {
        return chaperone();
    }

    /**
     * Sets the parent on every child under the given inverse name; the same as
     * {@link #chaperone(String)}.
     *
     * @param inverseRelation the related model's relation that points back at the parent, never
     *     {@code null}
     * @return this relation
     * @throws NullPointerException if {@code inverseRelation} is {@code null}
     * @throws RelationNotFoundException if the related model has no relation of that name
     */
    public final S inverse(String inverseRelation) {
        return chaperone(inverseRelation);
    }

    /**
     * Stops linking the parent on the children of this relation instance. A later
     * {@link #chaperone()} turns it on again.
     *
     * @return this relation
     */
    public final S withoutChaperone() {
        inverseName = null;
        return self();
    }

    /**
     * Stops linking the parent; the same as {@link #withoutChaperone()}.
     *
     * @return this relation
     */
    public final S withoutInverse() {
        return withoutChaperone();
    }

    /**
     * Returns the inverse relation name children are linked under.
     *
     * @return the name, or {@code null} when linking is off
     */
    final String inverseName() {
        return inverseName;
    }

    /**
     * Sets the parent on the child under the inverse name; does nothing when linking is off or
     * the child is {@code null}.
     *
     * @param child the related model, or {@code null}
     * @param parent the parent to link, the same instance, never {@code null}
     */
    final void linkInverse(Model child, Model parent) {
        if (inverseName != null && child != null) {
            child.setRelation(inverseName, parent);
        }
    }

    @Override
    final void linkResults(List<R> results) {
        if (inverseName == null) {
            return;
        }
        Model parent = getParent();
        for (R result : results) {
            linkInverse(result, parent);
        }
    }

    /**
     * Sets the attributes that tie a child to the parent: the foreign key holds the parent's
     * local key. A polymorphic relation adds its type. Laravel's
     * {@code setForeignAttributesForCreate}.
     *
     * @param child the related model to fill, never {@code null}
     * @throws IllegalStateException if the parent has no local key value
     */
    void setForeignAttributes(Model child) {
        child.setAttribute(foreignKeyName, requireParentKey());
    }

    private Object requireParentKey() {
        Object parentKey = getParentKey();
        if (parentKey == null) {
            // Writing with a null key would save orphans; Laravel lets it through.
            String name = getRelationName() == null ? foreignKeyName : getRelationName();
            throw new IllegalStateException("Cannot write through relation [" + name + "] of ["
                    + getParent().getClass().getName() + "]: the parent has no [" + localKey
                    + "] value; save the parent first");
        }
        return parentKey;
    }

    /**
     * Returns {@code true}: a has-one or has-many may be touched; {@link #touch()} updates every
     * child row whose foreign key holds the parent's local key.
     *
     * @return {@code true}
     */
    @Override
    final boolean supportsTouch() {
        return true;
    }

    @Override
    final void addConstraints() {
        performJoin(baseQuery());
        baseQuery().where(foreignKey, "=", getParentKey());
        baseQuery().whereNotNull(foreignKey);
    }

    @Override
    final List<Object> eagerKeys(List<? extends Model> parents) {
        return EagerKeys.<Model>collect(parents, (Model parent) -> parent.getAttribute(localKey));
    }

    @Override
    final void prepareEagerQuery(EloquentBuilder<R> eagerQuery, List<Object> chunk) {
        performJoin(eagerQuery);
        whereEagerKeys(eagerQuery, foreignKey, chunk);
    }

    @Override
    final boolean inlinesEagerKeys() {
        return isIntegerKey(getParent(), localKey);
    }

    @Override
    final String eagerMatchColumn() {
        return foreignKey;
    }

    @Override
    final EloquentBuilder<R> existenceQuery(EloquentBuilder<?> parentQuery, AliasCounter aliases) {
        Objects.requireNonNull(parentQuery, "parentQuery");
        Objects.requireNonNull(aliases, "aliases");
        String parentKey = parentKeyAgainst(parentQuery, localKey);
        if (RelationExistence.isSelfRelation(baseQuery(), parentQuery)) {
            String alias = aliasRelatedTable(aliases);
            performJoin(baseQuery());
            baseQuery().whereColumn(parentKey, "=", alias + "." + foreignKeyName);
            return existenceBaseQuery();
        }
        performJoin(baseQuery());
        baseQuery().whereColumn(parentKey, "=", foreignKey);
        return existenceBaseQuery();
    }

    /**
     * Groups the eager results by their normalised foreign key, keeping result order in each
     * group.
     *
     * @param results the related models, never {@code null}
     * @return the groups, never {@code null}
     */
    final Map<Object, List<R>> dictionary(List<R> results) {
        Map<Object, List<R>> dictionary = new HashMap<>();
        for (R result : results) {
            Object key = EagerKeys.normalise(result.getAttribute(foreignKeyName));
            if (key != null) {
                dictionary.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
            }
        }
        return dictionary;
    }

    /**
     * Returns the matched group of a parent.
     *
     * @param dictionary the groups by key, never {@code null}
     * @param parent the parent, never {@code null}
     * @return the parent's related models, or {@code null} when none matched
     */
    final List<R> matchesOf(Map<Object, List<R>> dictionary, Model parent) {
        Object key = EagerKeys.normalise(parent.getAttribute(localKey));
        return key == null ? null : dictionary.get(key);
    }
}
