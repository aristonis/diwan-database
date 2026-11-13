package org.aristonis.diwan.database.eloquent.factory;

import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * One request to give a model a parent through a belongs-to or morph-to relation. The parent is a
 * factory that creates it or a saved model. The identity of a link is the key of the per-frame
 * owner cache.
 */
final class ForLink {

    private final Factory<? extends Model> factory;
    private final Model model;
    private final String relation;

    private ForLink(Factory<? extends Model> factory, Model model, String relation) {
        this.factory = factory;
        this.model = model;
        this.relation = Objects.requireNonNull(relation, "relation");
    }

    /**
     * Returns a link whose parent a factory creates.
     *
     * @param factory the factory of the parent, never {@code null}
     * @param relation the relation name on the child, never {@code null}
     * @return the link, never {@code null}
     * @throws NullPointerException if {@code factory} or {@code relation} is {@code null}
     */
    static ForLink of(Factory<? extends Model> factory, String relation) {
        return new ForLink(Objects.requireNonNull(factory, "parent"), null, relation);
    }

    /**
     * Returns a link whose parent is a model that already exists.
     *
     * @param model the parent, never {@code null}
     * @param relation the relation name on the child, never {@code null}
     * @return the link, never {@code null}
     * @throws NullPointerException if {@code model} or {@code relation} is {@code null}
     */
    static ForLink of(Model model, String relation) {
        return new ForLink(null, Objects.requireNonNull(model, "parent"), relation);
    }

    /**
     * Returns the factory that creates the parent.
     *
     * @return the factory, or {@code null} when the parent is a model
     */
    Factory<? extends Model> factory() {
        return factory;
    }

    /**
     * Returns the parent model.
     *
     * @return the model, or {@code null} when a factory creates the parent
     */
    Model model() {
        return model;
    }

    /**
     * Returns the relation name on the child.
     *
     * @return the name, never {@code null}
     */
    String relation() {
        return relation;
    }
}
