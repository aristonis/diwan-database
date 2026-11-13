package org.aristonis.diwan.database.eloquent.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * One request to create children under each created model: a has link, or a has-attached link that
 * fills a many-to-many relation.
 */
final class HasLink {

    private final boolean attached;
    private final Factory<? extends Model> factory;
    private final List<? extends Model> existing;
    private final String relation;
    private final Map<String, Object> pivotMap;
    private final Function<Model, Map<String, Object>> pivotFunction;
    private final List<Map<String, Object>> pivotList;

    private HasLink(
            boolean attached,
            Factory<? extends Model> factory,
            List<? extends Model> existing,
            String relation,
            Map<String, Object> pivotMap,
            Function<Model, Map<String, Object>> pivotFunction,
            List<Map<String, Object>> pivotList) {
        this.attached = attached;
        this.factory = factory;
        this.existing = existing;
        this.relation = Objects.requireNonNull(relation, "relation");
        this.pivotMap = pivotMap;
        this.pivotFunction = pivotFunction;
        this.pivotList = pivotList;
    }

    /**
     * Returns a link that creates children of a has, has-one or morph relation.
     *
     * @param factory the factory of the children, never {@code null}
     * @param relation the relation name on the parent, never {@code null}
     * @return the link, never {@code null}
     * @throws NullPointerException if {@code factory} or {@code relation} is {@code null}
     */
    static HasLink has(Factory<? extends Model> factory, String relation) {
        return new HasLink(false, Objects.requireNonNull(factory, "child"), null, relation, null, null, null);
    }

    /**
     * Returns a link that creates related models and attaches them with no pivot values.
     *
     * @param factory the factory of the related models, never {@code null}
     * @param relation the many-to-many relation name on the parent, never {@code null}
     * @return the link, never {@code null}
     * @throws NullPointerException if {@code factory} or {@code relation} is {@code null}
     */
    static HasLink attachedFactory(Factory<? extends Model> factory, String relation) {
        return new HasLink(true, Objects.requireNonNull(factory, "related"), null, relation, null, null, null);
    }

    /**
     * Returns a link that creates related models and attaches each with the same pivot values.
     *
     * @param factory the factory of the related models, never {@code null}
     * @param relation the many-to-many relation name on the parent, never {@code null}
     * @param pivot the pivot values, copied, never {@code null}
     * @return the link, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    static HasLink attachedFactory(Factory<? extends Model> factory, Map<String, Object> pivot, String relation) {
        return new HasLink(true, Objects.requireNonNull(factory, "related"), null, relation,
                frozenMap(Objects.requireNonNull(pivot, "pivot")), null, null);
    }

    /**
     * Returns a link that creates related models and attaches each with pivot values computed from
     * the saved parent.
     *
     * @param factory the factory of the related models, never {@code null}
     * @param pivot the function from the saved parent to the pivot values, never {@code null}
     * @param relation the many-to-many relation name on the parent, never {@code null}
     * @return the link, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    static HasLink attachedFactory(
            Factory<? extends Model> factory, Function<Model, Map<String, Object>> pivot, String relation) {
        return new HasLink(true, Objects.requireNonNull(factory, "related"), null, relation, null,
                Objects.requireNonNull(pivot, "pivot"), null);
    }

    /**
     * Returns a link that creates related models and attaches the n-th with the pivot entry at
     * position n, cycling when there are more models than entries.
     *
     * @param factory the factory of the related models, never {@code null}
     * @param pivots the pivot entries, each copied, never {@code null} and never empty
     * @param relation the many-to-many relation name on the parent, never {@code null}
     * @return the link, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code pivots} is empty
     */
    static HasLink attachedFactory(
            Factory<? extends Model> factory, List<Map<String, Object>> pivots, String relation) {
        Objects.requireNonNull(pivots, "pivots");
        if (pivots.isEmpty()) {
            throw new IllegalArgumentException("hasAttached needs at least one pivot entry");
        }
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> entry : pivots) {
            copies.add(frozenMap(Objects.requireNonNull(entry, "pivot entry")));
        }
        return new HasLink(true, Objects.requireNonNull(factory, "related"), null, relation, null, null,
                Collections.unmodifiableList(copies));
    }

    /**
     * Returns a link that attaches models that already exist, all with the same pivot values.
     *
     * @param existing the saved models to attach, copied, never {@code null}
     * @param pivot the pivot values, copied, never {@code null}
     * @param relation the many-to-many relation name on the parent, never {@code null}
     * @return the link, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    static HasLink attachedExisting(List<? extends Model> existing, Map<String, Object> pivot, String relation) {
        List<Model> copy = new ArrayList<>(Objects.requireNonNull(existing, "existing"));
        return new HasLink(true, null, Collections.unmodifiableList(copy), relation,
                frozenMap(Objects.requireNonNull(pivot, "pivot")), null, null);
    }

    /**
     * Returns whether this link attaches models to a many-to-many relation.
     *
     * @return {@code true} for a has-attached link, {@code false} for a has link
     */
    boolean isAttached() {
        return attached;
    }

    /**
     * Returns the factory of the children or related models.
     *
     * @return the factory, or {@code null} when the link attaches existing models
     */
    Factory<? extends Model> factory() {
        return factory;
    }

    /**
     * Returns the models that already exist and are attached as they are.
     *
     * @return an unmodifiable list, or {@code null} when the link has a factory
     */
    List<? extends Model> existing() {
        return existing;
    }

    /**
     * Returns the relation name on the parent.
     *
     * @return the name, never {@code null}
     */
    String relation() {
        return relation;
    }

    /**
     * Returns the fixed pivot values.
     *
     * @return an unmodifiable map, or {@code null} when the link has none of this form
     */
    Map<String, Object> pivotMap() {
        return pivotMap;
    }

    /**
     * Returns the function that computes pivot values from the saved parent.
     *
     * @return the function, or {@code null} when the link has none of this form
     */
    Function<Model, Map<String, Object>> pivotFunction() {
        return pivotFunction;
    }

    /**
     * Returns the pivot entries taken in turn by position.
     *
     * @return an unmodifiable list of unmodifiable maps, or {@code null} when the link has none of
     *     this form
     */
    List<Map<String, Object>> pivotList() {
        return pivotList;
    }

    private static Map<String, Object> frozenMap(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
