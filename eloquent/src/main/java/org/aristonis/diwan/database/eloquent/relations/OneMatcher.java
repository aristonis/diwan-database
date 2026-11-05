package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The eager-load steps of a one-result relation, shared by {@link HasOne} and {@link MorphOne}:
 * every parent starts with the default and takes the first row of its group.
 */
final class OneMatcher {

    private OneMatcher() {
    }

    /**
     * Sets the default on every parent under the relation name.
     *
     * @param <R> the related model class
     * @param parents the parents, never {@code null}
     * @param name the relation name, never {@code null}
     * @param defaultFor builds the default of a parent, may return {@code null}
     */
    static <R extends Model> void initRelation(List<? extends Model> parents, String name,
            Function<Model, R> defaultFor) {
        for (Model parent : parents) {
            parent.setRelation(name, defaultFor.apply(parent));
        }
    }

    /**
     * Sets the first row of each parent's group under the relation name and links the inverse.
     * A parent with no row keeps what {@link #initRelation} set.
     *
     * @param <R> the related model class
     * @param relation the relation that owns the keys, never {@code null}
     * @param parents the parents, never {@code null}
     * @param results the eager rows, never {@code null}
     * @param name the relation name, never {@code null}
     */
    static <R extends Model> void match(HasOneOrMany<R, ?> relation, List<? extends Model> parents,
            List<R> results, String name) {
        Map<Object, List<R>> dictionary = relation.dictionary(results);
        for (Model parent : parents) {
            List<R> matches = relation.matchesOf(dictionary, parent);
            if (matches != null) {
                R match = matches.get(0);
                parent.setRelation(name, match);
                relation.linkInverse(match, parent);
            }
        }
    }
}
