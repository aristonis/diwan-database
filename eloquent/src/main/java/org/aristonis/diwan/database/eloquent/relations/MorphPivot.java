package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.query.Builder;

/**
 * A row of a morph-to-many join table: a {@link Pivot} that also knows the type column and the
 * type value of the relation that built it, such as {@code taggables.taggable_type} and
 * {@code Post}. Laravel's {@code MorphPivot}.
 *
 * <p>A pivot with no key value writes by its two pivot keys and the type, so saving or deleting
 * it never touches the row of another type that holds the same keys. A pivot with a key value
 * writes by that key alone. The relation sets the type and the class; a pivot built any other way
 * has neither, and then writes as a plain {@link Pivot}.
 *
 * <p>Not safe to share across threads, like every model.
 */
public class MorphPivot extends Pivot {

    // Registers the morph pivot class, so the relation can build it through the registry.
    private static final ModelQuery<MorphPivot> ENTRY = ModelQuery.of(MorphPivot.class, MorphPivot::new);

    private String morphType;
    private String morphClass;

    /**
     * Creates an empty morph pivot. A subclass that is built through a relation registers its own
     * entry with {@code ModelQuery.of(...)}, as any model does.
     */
    public MorphPivot() {
    }

    /**
     * Returns the type column of the join table, without the table, such as
     * {@code taggable_type}.
     *
     * @return the column, or {@code null} when no relation set it
     */
    public String getMorphType() {
        return morphType;
    }

    /**
     * Returns the value the type column holds for this pivot's relation.
     *
     * @return the morph class, or {@code null} when no relation set it
     */
    public String getMorphClass() {
        return morphClass;
    }

    /**
     * Sets the type column, called by the relation that builds this pivot.
     *
     * @param type the bare type column, or {@code null}
     * @return this pivot
     */
    MorphPivot setMorphType(String type) {
        this.morphType = type;
        return this;
    }

    /**
     * Sets the value of the type column, called by the relation that builds this pivot.
     *
     * @param morphClass the morph class, or {@code null}
     * @return this pivot
     */
    MorphPivot setMorphClass(String morphClass) {
        this.morphClass = morphClass;
        return this;
    }

    /**
     * Adds the where clauses that select this pivot's row: those of {@link Pivot}, and the type
     * condition whenever the type column is set, also when the row is found by a key value.
     *
     * @param query the query to change, never {@code null}
     * @return the query, never {@code null}
     */
    @Override
    protected Builder setKeysForSaveQuery(Builder query) {
        Builder keyed = super.setKeysForSaveQuery(query);
        if (morphType != null) {
            return keyed.where(morphType, "=", morphClass);
        }
        return keyed;
    }
}
