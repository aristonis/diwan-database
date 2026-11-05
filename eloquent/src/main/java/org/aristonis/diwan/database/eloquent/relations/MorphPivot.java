package org.aristonis.diwan.database.eloquent.relations;

import java.util.Map;
import java.util.Objects;
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
 * <p>A write that sets the type column to a value other than the pivot's own type is refused, so a
 * row never moves to another type, whatever a hook or listener does. Writing a row whose type
 * column holds the pivot's own type is fine. The check applies only when a morph relation built the
 * pivot; one built any other way has no morph type and is unchecked.
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
     * Refuses a write that sets the type column to a value other than this pivot's morph class.
     * It runs after every saving, creating and updating hook and listener, just before the
     * statement is built, so neither can change the type past it, and a subclass cannot skip it.
     * A column named like the type column in another letter case is refused too, even with the
     * right value, since the row would carry the column twice. A pivot not built by a morph
     * relation, with no morph type set, is not checked.
     *
     * @throws IllegalArgumentException if the type column holds another value; the message names the
     *     column, the value and the expected type
     */
    @Override
    protected final void beforeWrite() {
        requireOwnType();
    }

    private void requireOwnType() {
        if (morphType == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : getAttributes().entrySet()) {
            String column = entry.getKey();
            if (morphType.equalsIgnoreCase(column)
                    && (!morphType.equals(column) || !Objects.equals(morphClass, entry.getValue()))) {
                throw new IllegalArgumentException("Cannot save a pivot of class [" + getClass().getName()
                        + "] with [" + column + "] set to [" + entry.getValue() + "]; the type of its"
                        + " relation is [" + morphClass + "]");
            }
        }
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
