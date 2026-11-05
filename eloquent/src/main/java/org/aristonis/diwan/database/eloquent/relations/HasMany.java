package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;

/**
 * A one-to-many relation whose related rows hold the key, such as a post's comments: Laravel's
 * {@code HasMany}.
 *
 * <p>Declare it on the parent with {@code hasMany(Comment.class)}. Single use, not safe to share
 * across threads (see {@link Relation}).
 *
 * @param <R> the related model class
 */
public final class HasMany<R extends Model> extends HasOneOrMany<R, HasMany<R>> {

    /**
     * Creates the relation. Models normally build it through {@code hasMany(...)}.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param parent the model the relation belongs to, never {@code null}
     * @param foreignKey the related table's column pointing at the parent, bare or qualified,
     *     never {@code null}
     * @param localKey the parent's column the foreign key holds, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public HasMany(EloquentBuilder<R> query, Model parent, String foreignKey, String localKey) {
        super(query, parent, foreignKey, localKey);
    }

    /**
     * Returns the related models of the parent. A parent without a local key value runs no
     * query and gets an empty collection.
     *
     * @return the related models in query order, never {@code null}
     */
    @Override
    public ModelCollection<R> getResults() {
        if (getParentKey() == null) {
            return emptyCollection();
        }
        return get();
    }

    /**
     * Returns a one-result relation on the same columns and parent: Laravel's {@code one()}, the
     * way to {@code latestOfMany()} and its kin. It is built on this relation's constrained
     * query, so the key condition and every clause recorded here are kept, each once. The two
     * relations share that query, as in Laravel: a join that {@code latestOfMany()} adds on the
     * returned relation is also seen when this relation is read afterwards.
     *
     * @return a new relation, never {@code null}
     */
    public HasOne<R> one() {
        boolean hadConstraints = hasLazyConstraints();
        HasOne<R> one = new HasOne<>(queryForDerived(), getParent(), getQualifiedForeignKeyName(),
                getLocalKeyName());
        inheritConstraints(one, hadConstraints);
        return one;
    }

    @Override
    HasMany<R> self() {
        return this;
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        ManyMatcher.initRelation(this, parents, name);
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        ManyMatcher.match(this, parents, results, name);
    }
}
