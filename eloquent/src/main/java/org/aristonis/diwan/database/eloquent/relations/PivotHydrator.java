package org.aristonis.diwan.database.eloquent.relations;

import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;

/**
 * The per-row hook of a belongsToMany read: moves each {@code pivot_*} attribute of a related
 * model into a {@link Pivot}, links the pivot to its parent and to the related model, and stores
 * the pivot on the related model under the relation's accessor name.
 *
 * <p>One hydrator serves one read. The constructor snapshots the relation's key names, accessor
 * and chaperone names, so a setting changed on the relation after the read does not reach the
 * rows already being hydrated. The pivot itself is built by the relation, which owns the pivot
 * class and table. Not thread-safe: a read is single-threaded and the hydrator holds no shared
 * state.
 */
final class PivotHydrator {

    private static final String PIVOT_PREFIX = "pivot_";

    private final BelongsToMany<?> relation;
    private final Model parent;
    private final String foreignKey;
    private final String relatedKey;
    private final String accessor;
    private final String declaringName;
    private final String relatedName;

    /**
     * Creates the hydrator of one read, snapshotting the relation's pivot settings.
     *
     * @param relation the relation whose read this hydrator serves, never {@code null}
     * @param parent the model the read was made from, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    PivotHydrator(BelongsToMany<?> relation, Model parent) {
        this.relation = Objects.requireNonNull(relation, "relation");
        this.parent = Objects.requireNonNull(parent, "parent");
        this.foreignKey = relation.getForeignPivotKeyName();
        this.relatedKey = relation.getRelatedPivotKeyName();
        this.accessor = relation.getPivotAccessor();
        this.declaringName = relation.declaringChaperone();
        this.relatedName = relation.relatedChaperone();
    }

    /**
     * Moves the model's {@code pivot_*} attributes into a new pivot and stores the pivot on the
     * model.
     *
     * <p>Every {@code pivot_*} attribute is moved, including one that belongs to the related table
     * itself. A model without any gets an empty pivot. A loaded relation of the accessor name is
     * replaced. No query runs and no cast applies.
     *
     * @param model the related model as the read produced it, never {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     */
    void hydrate(Model model) {
        Objects.requireNonNull(model, "model");
        Map<String, Object> values = Access.eloquent().takeAttributes(model, PIVOT_PREFIX);
        Pivot pivot = relation.newPivot(values, true);
        pivot.setPivotKeys(foreignKey, relatedKey);
        pivot.setRelatedModel(model);
        linkChaperones(pivot, parent, model);
        model.setRelation(accessor, pivot);
    }

    /**
     * Points a pivot's links at the parent that a later match assigns, and its parent reference
     * with it.
     *
     * <p>The eager match builds pivots before it knows the real parent, so the links set at
     * hydration may name a different instance.
     *
     * @param pivot the pivot to re-point, never {@code null}
     * @param parent the parent the pivot now belongs to, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    void relinkParent(Pivot pivot, Model parent) {
        Objects.requireNonNull(pivot, "pivot");
        Objects.requireNonNull(parent, "parent");
        if (declaringName != null) {
            pivot.setRelation(declaringName, parent);
        }
        pivot.setPivotParent(parent);
    }

    /**
     * Returns the pivot stored on a related model under the accessor name.
     *
     * @param related the related model, never {@code null}
     * @return the pivot, or {@code null} when the accessor is not loaded or holds another value
     * @throws NullPointerException if {@code related} is {@code null}
     */
    Pivot pivotOf(Model related) {
        Objects.requireNonNull(related, "related");
        if (!related.relationLoaded(accessor)) {
            return null;
        }
        Object value = related.getRelation(accessor);
        return value instanceof Pivot ? (Pivot) value : null;
    }

    /**
     * Returns the accessor name the pivot is stored under, as snapshotted at construction.
     *
     * @return the accessor name, never {@code null}
     */
    String accessor() {
        return accessor;
    }

    private void linkChaperones(Pivot pivot, Model owner, Model related) {
        if (declaringName != null) {
            pivot.setRelation(declaringName, owner);
        }
        if (relatedName != null) {
            pivot.setRelation(relatedName, related);
        }
    }
}
