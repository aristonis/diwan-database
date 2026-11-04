package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * A one-to-one relation that reaches the related row through an intermediate model, such as a
 * mechanic's owner through the car: Laravel's {@code HasOneThrough}.
 *
 * <p>Declare it on the far parent with {@code hasOneThrough(Owner.class, Car.class)}. The
 * relation joins the through table and reads the first matching row. When several rows match,
 * the first in result order wins, lazily and eagerly. Single use: one instance serves one
 * lazy read or one eager load, and it is not safe to share across threads (see
 * {@link Relation}).
 *
 * <p>A far parent without a local key value runs no query and yields the default, or
 * {@code null}.
 *
 * @param <R> the related model class
 */
public final class HasOneThrough<R extends Model> extends HasOneOrManyThrough<R, HasOneThrough<R>> {

    private DefaultModel<R> defaultModel = DefaultModel.none();

    /**
     * Creates the relation. Models normally build it through {@code hasOneThrough(...)}.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param farParent the model the relation belongs to, never {@code null}
     * @param throughParent a new instance of the through model, never {@code null}
     * @param firstKey the through table's column pointing at the far parent, bare or qualified,
     *     never {@code null}
     * @param secondKey the related table's column pointing at the through model, bare or
     *     qualified, never {@code null}
     * @param localKey the far parent's column the first key holds, never {@code null}
     * @param secondLocalKey the through model's column the second key holds, never
     *     {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public HasOneThrough(
            EloquentBuilder<R> query,
            Model farParent,
            Model throughParent,
            String firstKey,
            String secondKey,
            String localKey,
            String secondLocalKey) {
        super(query, farParent, throughParent, firstKey, secondKey, localKey, secondLocalKey);
    }

    /**
     * Returns the related model of the far parent, or the default when there is none. A far
     * parent without a local key value runs no query.
     *
     * @return the related model, the default, or {@code null}
     */
    @Override
    public R getResults() {
        if (getParent().getAttribute(getLocalKeyName()) == null) {
            return defaultFor(getParent());
        }
        return first().orElseGet(() -> defaultFor(getParent()));
    }

    /**
     * Makes the relation return a bare related model instead of {@code null} when no row
     * matches. The default has no key set and is never saved.
     *
     * @return this relation
     */
    public HasOneThrough<R> withDefault() {
        defaultModel = DefaultModel.bare();
        return this;
    }

    /**
     * Makes the relation return a related model filled with the attributes, ignoring the
     * fillable list, instead of {@code null}. The default has no key set and is never saved.
     *
     * @param attributes the attributes, never {@code null}, copied
     * @return this relation
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public HasOneThrough<R> withDefault(Map<String, ?> attributes) {
        defaultModel = DefaultModel.filled(attributes);
        return this;
    }

    /**
     * Makes the relation return a new related model, passed to the callback with the far
     * parent, instead of {@code null}. The default has no key set and is never saved.
     *
     * @param callback receives the new default and the far parent, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public HasOneThrough<R> withDefault(BiConsumer<? super R, Model> callback) {
        defaultModel = DefaultModel.configured(callback);
        return this;
    }

    @Override
    HasOneThrough<R> self() {
        return this;
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        clearThroughKeys();
        for (Model parent : parents) {
            parent.setRelation(name, defaultFor(parent));
        }
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        try {
            Map<Object, List<R>> dictionary = dictionary(results);
            for (Model parent : parents) {
                List<R> matches = matchesOf(dictionary, parent);
                if (matches != null) {
                    parent.setRelation(name, matches.get(0));
                }
            }
        } finally {
            clearThroughKeys();
        }
    }

    private R defaultFor(Model parent) {
        return defaultModel.createFor(parent, ignored -> getRelated().newInstance());
    }
}
