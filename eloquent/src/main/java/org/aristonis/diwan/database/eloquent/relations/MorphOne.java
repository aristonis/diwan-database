package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * A polymorphic one-to-one relation, such as a post's image: Laravel's {@code MorphOne}.
 *
 * <p>Declare it on the parent with {@code morphOne(Image.class, "imageable")}. It reads the first
 * matching row; when several match, the first in result order wins, lazily and eagerly. Single
 * use, not safe to share across threads (see {@link Relation}).
 *
 * @param <R> the related model class
 */
public final class MorphOne<R extends Model> extends MorphOneOrMany<R, MorphOne<R>> {

    private DefaultModel<R> defaultModel = DefaultModel.none();

    /**
     * Creates the relation. Models normally build it through {@code morphOne(...)}.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param parent the model the relation belongs to, never {@code null}
     * @param qualifiedType the related table's type column, bare or qualified, never {@code null}
     * @param qualifiedId the related table's id column, bare or qualified, never {@code null}
     * @param localKey the parent's column the id column holds, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public MorphOne(EloquentBuilder<R> query, Model parent, String qualifiedType, String qualifiedId,
            String localKey) {
        super(query, parent, qualifiedType, qualifiedId, localKey);
    }

    /**
     * Returns the related model of the parent, or the default when there is none. A parent
     * without a local key value runs no query.
     *
     * @return the related model, the default, or {@code null}
     */
    @Override
    public R getResults() {
        if (getParentKey() == null) {
            return defaultFor(getParent());
        }
        return first().orElseGet(() -> defaultFor(getParent()));
    }

    /**
     * Runs the lazy query and returns the first related model, without the default. With
     * {@link #chaperone()} on, the model has the parent set under the inverse name.
     *
     * @return the first related model, or empty
     */
    public Optional<R> first() {
        Optional<R> found = getQuery().first();
        found.ifPresent(model -> linkInverse(model, getParent()));
        return found;
    }

    /**
     * Makes the relation return a bare related model, with the id and type set to the parent's,
     * instead of {@code null} when no row matches. The default is never saved.
     *
     * @return this relation
     */
    public MorphOne<R> withDefault() {
        defaultModel = DefaultModel.bare();
        return this;
    }

    /**
     * Makes the relation return a related model filled with the attributes, ignoring the
     * fillable list, with the id and type set, instead of {@code null}. The default is never
     * saved.
     *
     * @param attributes the attributes, never {@code null}, copied
     * @return this relation
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public MorphOne<R> withDefault(Map<String, ?> attributes) {
        defaultModel = DefaultModel.filled(attributes);
        return this;
    }

    /**
     * Makes the relation return a related model, with the id and type set and then passed to the
     * callback with the parent, instead of {@code null}. The default is never saved.
     *
     * @param callback receives the new default and the parent, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public MorphOne<R> withDefault(BiConsumer<? super R, Model> callback) {
        defaultModel = DefaultModel.configured(callback);
        return this;
    }

    @Override
    MorphOne<R> self() {
        return this;
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        OneMatcher.initRelation(parents, name, this::defaultFor);
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        OneMatcher.match(this, parents, results, name);
    }

    private R defaultFor(Model parent) {
        return defaultModel.createFor(parent, this::newRelatedFor);
    }

    private R newRelatedFor(Model parent) {
        R instance = getRelated().newInstance();
        instance.setAttribute(getForeignKeyName(), parent.getAttribute(getLocalKeyName()));
        instance.setAttribute(getMorphTypeName(), parent.getMorphClass());
        // Linked before a withDefault callback runs, as Laravel's newRelatedInstanceFor.
        linkInverse(instance, parent);
        return instance;
    }
}
