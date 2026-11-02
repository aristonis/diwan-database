package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.Model;

/**
 * Builds one relation of a model; usually a method reference to the model's relation method,
 * such as {@code Post::comments}.
 *
 * @param <M> the model class the relation starts from
 */
@FunctionalInterface
public interface RelationFactory<M extends Model> {

    /**
     * Builds the relation for a model.
     *
     * @implSpec An implementation returns a new, unconstrained relation on every call and never
     *     {@code null}. It runs once per lazy load, eager load and existence query, from any thread,
     *     so it must not keep state between calls.
     *
     * @param model the parent model, never {@code null}
     * @return the relation, never {@code null}
     */
    Relation<?, ?> create(M model);
}
