package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * A polymorphic has-one or has-many: the related rows hold the parent's key in an id column and
 * the parent's morph class in a type column. Laravel's {@code MorphOneOrMany}, the common part of
 * {@link MorphOne} and {@link MorphMany}.
 *
 * <p>Every lazy, eager and existence query adds {@code related.type = ?} with the parent's
 * {@link Model#getMorphClass()} bound. The morph class is read each time a query is built, not
 * when the relation is created, so a change of the morph map in between is honored. Writes set
 * both the id and the type. Single use, not safe to share across threads (see {@link Relation}).
 *
 * @param <R> the related model class
 * @param <S> the relation's own class
 */
public abstract class MorphOneOrMany<R extends Model, S extends MorphOneOrMany<R, S>>
        extends HasOneOrMany<R, S> {

    private final String morphType;

    private final String morphTypeName;

    MorphOneOrMany(EloquentBuilder<R> query, Model parent, String qualifiedType, String qualifiedId,
            String localKey) {
        super(query, parent, qualifiedId, localKey);
        Objects.requireNonNull(qualifiedType, "qualifiedType");
        this.morphType = query.getModel().qualifyColumn(qualifiedType);
        this.morphTypeName = bare(qualifiedType);
    }

    /**
     * Returns the type column on the related table, qualified, such as
     * {@code comments.commentable_type}.
     *
     * @return the qualified type column, never {@code null}
     */
    public final String getMorphType() {
        return morphType;
    }

    /**
     * Returns the type column on the related table, without the table.
     *
     * @return the bare type column, never {@code null}
     */
    public final String getMorphTypeName() {
        return morphTypeName;
    }

    /**
     * Returns the value the type column holds for this relation's parent: its morph alias, or its
     * class name when the map is not required. It is read on every call.
     *
     * @return the parent's morph class, never {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException if the parent's
     *     class has no alias and the morph map is required
     */
    public final String getMorphClass() {
        return getParent().getMorphClass();
    }

    /**
     * Adds the type condition. The column is qualified through the target's own model, so a
     * self-relation whose related table was aliased puts the condition on the alias.
     *
     * @param target the query to change, never {@code null}
     */
    @Override
    void performJoin(EloquentBuilder<R> target) {
        super.performJoin(target);
        target.where(target.getModel().qualifyColumn(morphTypeName), "=", getMorphClass());
    }

    @Override
    List<String> partitionColumns(EloquentBuilder<R> query) {
        List<String> columns = new ArrayList<>(super.partitionColumns(query));
        columns.add(query.getModel().qualifyColumn(morphTypeName));
        return columns;
    }

    @Override
    void filterSubQuery(EloquentBuilder<R> sub, OneOfManyJoin.Mode mode, List<Object> keys) {
        super.filterSubQuery(sub, mode, keys);
        if (mode != OneOfManyJoin.Mode.EXISTENCE) {
            sub.where(sub.getModel().qualifyColumn(morphTypeName), "=", getMorphClass());
        }
    }

    /**
     * Sets the foreign key and then the type column to the parent's morph class.
     *
     * @param child the related model to fill, never {@code null}
     * @throws IllegalStateException if the parent has no local key value
     */
    @Override
    void setForeignAttributes(Model child) {
        super.setForeignAttributes(child);
        child.setAttribute(morphTypeName, getMorphClass());
    }
}
