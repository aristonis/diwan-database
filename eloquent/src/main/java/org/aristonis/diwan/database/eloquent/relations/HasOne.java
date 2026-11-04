package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * A one-to-one relation whose related row holds the key, such as a user's profile: Laravel's
 * {@code HasOne}.
 *
 * <p>Declare it on the parent with {@code hasOne(Profile.class)}; the relation reads the first
 * matching row. When several rows match, the first in result order wins, lazily and eagerly
 *. Single use, not safe to share across threads (see {@link Relation}).
 *
 * @param <R> the related model class
 */
public final class HasOne<R extends Model> extends HasOneOrMany<R, HasOne<R>> {

    private DefaultModel<R> defaultModel = DefaultModel.none();

    /**
     * Creates the relation. Models normally build it through {@code hasOne(...)}.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param parent the model the relation belongs to, never {@code null}
     * @param foreignKey the related table's column pointing at the parent, bare or qualified,
     *     never {@code null}
     * @param localKey the parent's column the foreign key holds, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public HasOne(EloquentBuilder<R> query, Model parent, String foreignKey, String localKey) {
        super(query, parent, foreignKey, localKey);
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
     * Makes the relation return a bare related model, with the foreign key set to the parent's
     * key, instead of {@code null} when no row matches. The default is never saved.
     *
     * @return this relation
     */
    public HasOne<R> withDefault() {
        defaultModel = DefaultModel.bare();
        return this;
    }

    /**
     * Makes the relation return a related model filled with the attributes, ignoring the
     * fillable list, with the foreign key set, instead of {@code null}. The default is never
     * saved.
     *
     * @param attributes the attributes, never {@code null}, copied
     * @return this relation
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public HasOne<R> withDefault(Map<String, ?> attributes) {
        defaultModel = DefaultModel.filled(attributes);
        return this;
    }

    /**
     * Makes the relation return a related model, with the foreign key set and then passed to the
     * callback with the parent, instead of {@code null}. The default is never saved.
     *
     * @param callback receives the new default and the parent, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public HasOne<R> withDefault(BiConsumer<? super R, Model> callback) {
        defaultModel = DefaultModel.configured(callback);
        return this;
    }

    @Override
    HasOne<R> self() {
        return this;
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        for (Model parent : parents) {
            parent.setRelation(name, defaultFor(parent));
        }
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        Map<Object, List<R>> dictionary = dictionary(results);
        for (Model parent : parents) {
            List<R> matches = matchesOf(dictionary, parent);
            if (matches != null) {
                R match = matches.get(0);
                parent.setRelation(name, match);
                linkInverse(match, parent);
            }
        }
    }

    private R defaultFor(Model parent) {
        return defaultModel.createFor(parent, this::newRelatedFor);
    }

    private R newRelatedFor(Model parent) {
        R instance = getRelated().newInstance();
        instance.setAttribute(getForeignKeyName(), parent.getAttribute(getLocalKeyName()));
        // Linked before a withDefault callback runs, as Laravel's newRelatedInstanceFor.
        linkInverse(instance, parent);
        return instance;
    }
}
