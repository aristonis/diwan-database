package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;

/**
 * A polymorphic one-to-many relation, such as a post's comments: Laravel's {@code MorphMany}.
 *
 * <p>Declare it on the parent with {@code morphMany(Comment.class, "commentable")}. Single use,
 * not safe to share across threads (see {@link Relation}).
 *
 * @param <R> the related model class
 */
public final class MorphMany<R extends Model> extends MorphOneOrMany<R, MorphMany<R>> {

    /**
     * Creates the relation. Models normally build it through {@code morphMany(...)}.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param parent the model the relation belongs to, never {@code null}
     * @param qualifiedType the related table's type column, bare or qualified, never {@code null}
     * @param qualifiedId the related table's id column, bare or qualified, never {@code null}
     * @param localKey the parent's column the id column holds, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public MorphMany(EloquentBuilder<R> query, Model parent, String qualifiedType, String qualifiedId,
            String localKey) {
        super(query, parent, qualifiedType, qualifiedId, localKey);
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
     * Returns a one-result relation on the same columns and parent: Laravel's {@code one()}. It
     * is built on this relation's constrained query, so the type and id conditions and every
     * clause recorded here are kept, each once. The two relations share that query, as in
     * Laravel: a join that {@code latestOfMany()} adds on the returned relation is also seen when
     * this relation is read afterwards.
     *
     * @return a new relation, never {@code null}
     */
    public MorphOne<R> one() {
        boolean hadConstraints = hasLazyConstraints();
        MorphOne<R> one = new MorphOne<>(queryForDerived(), getParent(), getMorphType(),
                getQualifiedForeignKeyName(), getLocalKeyName());
        inheritConstraints(one, hadConstraints);
        return one;
    }

    @Override
    MorphMany<R> self() {
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
