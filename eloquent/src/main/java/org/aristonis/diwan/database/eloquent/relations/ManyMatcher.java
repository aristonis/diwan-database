package org.aristonis.diwan.database.eloquent.relations;

import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The eager-load steps of a many-result relation, shared by {@link HasMany} and
 * {@link MorphMany}: every parent starts with an empty collection and takes its group.
 */
final class ManyMatcher {

    private ManyMatcher() {
    }

    /**
     * Sets an empty collection on every parent under the relation name.
     *
     * @param <R> the related model class
     * @param relation the relation that builds the collections, never {@code null}
     * @param parents the parents, never {@code null}
     * @param name the relation name, never {@code null}
     */
    static <R extends Model> void initRelation(HasOneOrMany<R, ?> relation, List<? extends Model> parents,
            String name) {
        for (Model parent : parents) {
            parent.setRelation(name, relation.emptyCollection());
        }
    }

    /**
     * Sets each parent's group as a collection under the relation name and links the inverse.
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
                parent.setRelation(name, relation.collectionOf(matches));
                for (R match : matches) {
                    relation.linkInverse(match, parent);
                }
            }
        }
    }
}
