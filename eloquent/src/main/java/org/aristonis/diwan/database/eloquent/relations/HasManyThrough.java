package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;

/**
 * A has-many-through relation: the far parent has many related rows through an intermediate
 * model, such as a country with many posts through its users. Laravel's {@code HasManyThrough}.
 *
 * <p>The lazy query joins the through table and filters on its first key:
 * {@code select posts.*, users.country_id as laravel_through_key from posts inner join users on
 * users.id = posts.user_id where users.country_id = ?}. A related row reachable through two
 * through rows is returned twice. Single use, not safe to share across threads (see
 * {@link HasOneOrManyThrough}).
 *
 * @param <R> the related model class
 */
public final class HasManyThrough<R extends Model> extends HasOneOrManyThrough<R, HasManyThrough<R>> {

    /**
     * Builds the relation over the related query and the six keys.
     *
     * @param query the related model's query, never {@code null}
     * @param farParent the model the relation is read from, never {@code null}
     * @param throughParent an instance of the through model, never {@code null}
     * @param firstKey the through table's column that holds the far parent's key, never {@code null}
     * @param secondKey the related table's column that holds the through key, never {@code null}
     * @param localKey the far parent's column the first key points at, never {@code null}
     * @param secondLocalKey the through table's column the second key points at, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public HasManyThrough(EloquentBuilder<R> query, Model farParent, Model throughParent, String firstKey,
            String secondKey, String localKey, String secondLocalKey) {
        super(query, farParent, throughParent, firstKey, secondKey, localKey, secondLocalKey);
    }

    /**
     * Returns the related rows of the far parent.
     *
     * @return the related models, empty without a query when the parent has no local key value
     */
    @Override
    public ModelCollection<R> getResults() {
        if (parentKey() == null) {
            return emptyCollection();
        }
        return get();
    }

    /**
     * Returns a one-result relation over the same path and far parent: Laravel's {@code one()}, the
     * way to {@code latestOfMany()} and its kin. It is built on this relation's constrained
     * query, so the through join, the key condition and every clause recorded here are kept,
     * each once. Trashed through parents stay kept when {@link #withTrashedParents()} was called.
     * The two relations share that query, as in Laravel: a join that {@code latestOfMany()} adds
     * on the returned relation is also seen when this relation is read afterwards.
     *
     * @return a new relation, never {@code null}
     */
    public HasOneThrough<R> one() {
        boolean hadConstraints = hasLazyConstraints();
        HasOneThrough<R> one = new HasOneThrough<>(queryForDerived(), getParent(), throughParent(),
                getFirstKeyName(), getForeignKeyName(), getLocalKeyName(), getSecondLocalKeyName());
        inheritConstraints(one, hadConstraints);
        if (keepsTrashedParents()) {
            one.withTrashedParents();
        }
        return one;
    }

    @Override
    HasManyThrough<R> self() {
        return this;
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        clearThroughKeys();
        for (Model parent : parents) {
            parent.setRelation(name, emptyCollection());
        }
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        try {
            Map<Object, List<R>> dictionary = dictionary(results);
            for (Model parent : parents) {
                List<R> matches = matchesOf(dictionary, parent);
                if (matches != null) {
                    parent.setRelation(name, collectionOf(matches));
                }
            }
        } finally {
            clearThroughKeys();
        }
    }
}
