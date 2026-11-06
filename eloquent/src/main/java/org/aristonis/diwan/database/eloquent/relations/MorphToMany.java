package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.support.Inflector;

/**
 * A polymorphic many-to-many relation, such as the tags of a post or a video: Laravel's
 * {@code MorphToMany}. One pivot table, such as {@code taggables}, links one model to many parent
 * types through a type column, such as {@code taggable_type}.
 *
 * <p>Declare it on the parent with {@code morphToMany(Tag.class, "taggable")}, or on the related
 * side with {@code morphedByMany(Post.class, "taggable")}. Every lazy, eager and existence query
 * adds {@code pivot.type = ?} with the morph class bound, and the pivot accessor carries the type
 * column. The morph class is read each time a query is built, not when the relation is created,
 * so a change of the morph map in between is honored.
 *
 * <p>The pivot queries that delete or update (detach, updateExistingPivot, touch) are limited to
 * the type, and the pivots are {@link MorphPivot}s, so a {@code using} class must extend
 * {@link MorphPivot}. A new pivot row carries the type column, written right after the foreign
 * pivot key. A write that sets the type column to another value, through an attach attribute, an
 * update, {@code withPivotValue} or a pivot save, is refused with {@link IllegalArgumentException}
 * and nothing is written.
 *
 * <p>The fluent methods return this same instance, typed as {@code MorphToMany}. Single use, not
 * safe to share across threads (see {@link Relation}).
 *
 * @param <R> the related model class
 */
public final class MorphToMany<R extends Model> extends BelongsToMany<R> {

    private static final char WORD_SEPARATOR = '_';

    private final String morphType;

    private final boolean isInverse;

    /**
     * Creates the relation. Models normally build it through {@code morphToMany(...)} or
     * {@code morphedByMany(...)}.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param parent the model the relation belongs to, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null}
     * @param table the pivot table, never {@code null}
     * @param foreignPivotKey the pivot column holding the parent's key, never {@code null}
     * @param relatedPivotKey the pivot column holding the related key, never {@code null}
     * @param parentKey the parent's column the pivot points at, never {@code null}
     * @param relatedKey the related model's column the pivot points at, never {@code null}
     * @param relation the relation name, or {@code null} to take it from the model's map
     * @param inverse whether the type column holds the related model's morph class
     * @throws NullPointerException if an argument other than {@code relation} is {@code null}
     */
    MorphToMany(EloquentBuilder<R> query, Model parent, String name, String table,
            String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey,
            String relation, boolean inverse) {
        super(query, parent, table, foreignPivotKey, relatedPivotKey, parentKey, relatedKey, relation);
        this.morphType = Objects.requireNonNull(name, "name") + "_type";
        this.isInverse = inverse;
    }

    /**
     * Returns the default pivot table of a morph name: the plural of its last word, such as
     * {@code taggables} for {@code taggable} and {@code model_tags} for {@code model_tag}.
     *
     * @param name the morph name, never {@code null}
     * @return the table name, never {@code null}
     */
    static String defaultTable(String name) {
        int cut = name.lastIndexOf(WORD_SEPARATOR);
        if (cut == name.length() - 1) {
            return Inflector.plural(name);
        }
        return name.substring(0, cut + 1) + Inflector.plural(name.substring(cut + 1));
    }

    /**
     * Returns the type column of the pivot table, without the table.
     *
     * @return the column, such as {@code taggable_type}, never {@code null}
     */
    public String getMorphType() {
        return morphType;
    }

    /**
     * Returns the type column of the pivot table, qualified with the pivot table.
     *
     * @return the qualified column, such as {@code taggables.taggable_type}, never {@code null}
     */
    public String getQualifiedMorphTypeName() {
        return getTable() + "." + morphType;
    }

    /**
     * Returns the value the type column holds for this relation: the parent's morph class, or the
     * related model's when the relation is inverse. It is read on every call.
     *
     * @return the morph class, never {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException if the class has
     *     no alias and the morph map is required
     */
    public String getMorphClass() {
        return isInverse ? getRelated().getMorphClass() : getParent().getMorphClass();
    }

    /**
     * Returns whether this is the inverse side, declared with {@code morphedByMany}.
     *
     * @return {@code true} for the inverse side
     */
    public boolean isInverse() {
        return isInverse;
    }

    /**
     * Joins the pivot and then adds the type condition on the pivot, bound.
     *
     * @param target the query to change, never {@code null}
     */
    @Override
    void performJoin(EloquentBuilder<R> target) {
        super.performJoin(target);
        target.where(getQualifiedMorphTypeName(), "=", getMorphClass());
    }

    /**
     * Returns the pivot select entries with the type column added, so the pivot accessor carries it.
     *
     * @return the entries, never {@code null}
     */
    @Override
    String[] aliasedPivotColumns() {
        String[] base = super.aliasedPivotColumns();
        String entry = getQualifiedMorphTypeName() + " as pivot_" + morphType;
        for (String existing : base) {
            if (existing.equals(entry)) {
                return base;
            }
        }
        // After the two keys, before the extra columns, as Laravel orders them.
        String[] entries = new String[base.length + 1];
        System.arraycopy(base, 0, entries, 0, 2);
        entries[2] = entry;
        System.arraycopy(base, 2, entries, 3, base.length - 2);
        return entries;
    }

    /**
     * Limits a pivot query to the rows of this relation's type.
     *
     * @param query the pivot query to change, never {@code null}
     */
    @Override
    void scopePivotQuery(Builder query) {
        query.where(getQualifiedMorphTypeName(), "=", getMorphClass());
    }

    /**
     * Returns the columns every new row carries after the related key, with the type column right
     * after the foreign pivot key, so an insert carries the type.
     *
     * @return a new map in column order
     * @throws IllegalStateException if the parent has no key value
     */
    @Override
    Map<String, Object> recordTail() {
        Map<String, Object> base = super.recordTail();
        Map<String, Object> tail = new LinkedHashMap<>();
        String foreign = getForeignPivotKeyName();
        tail.put(foreign, base.get(foreign));
        tail.put(morphType, getMorphClass());
        for (Map.Entry<String, Object> entry : base.entrySet()) {
            if (!entry.getKey().equals(foreign)) {
                tail.put(entry.getKey(), entry.getValue());
            }
        }
        return tail;
    }

    /**
     * Refuses a write that sets the type column to a value other than this relation's morph class,
     * so an attach or an update never stores or moves a row under another type.
     *
     * @param attributes the columns and values being written, never {@code null}
     * @throws IllegalArgumentException if the type column holds another value; the message names the
     *     column, the value and the expected type
     */
    @Override
    void checkPivotAttributes(Map<String, ?> attributes) {
        for (Map.Entry<String, ?> entry : attributes.entrySet()) {
            if (morphType.equalsIgnoreCase(entry.getKey())) {
                requireOwnType(entry.getKey(), entry.getValue());
            }
        }
    }

    private void requireOwnType(String column, Object value) {
        String expected = getMorphClass();
        // A differently cased name is refused even with the right value: the row would carry the
        // column twice, once from the relation and once from the attributes.
        if (!morphType.equals(column) || !Objects.equals(expected, value)) {
            throw new IllegalArgumentException("Cannot set pivot column [" + column + "] to [" + value
                    + "] on morph-to-many relation [" + (getRelationName() != null ? getRelationName() : getTable())
                    + "]; the type of the relation is [" + expected + "]");
        }
    }

    /**
     * Builds a {@link MorphPivot} for the relation, with the type column and the morph class set.
     * The type column is also an attribute, unless the attributes give it.
     *
     * @param attributes the pivot's columns, never {@code null}; copied
     * @param exists whether the row is in the database
     * @return a new pivot, never {@code null}
     * @throws IllegalStateException if the {@code using} class does not extend {@link MorphPivot}
     */
    @Override
    public Pivot newPivot(Map<String, ?> attributes, boolean exists) {
        Class<? extends Pivot> using = usingClass();
        if (using != null && !MorphPivot.class.isAssignableFrom(using)) {
            throw new IllegalStateException("The pivot class [" + using.getName()
                    + "] of a morph-to-many relation must extend MorphPivot");
        }
        String morphClass = getMorphClass();
        Map<String, Object> merged = new LinkedHashMap<>(pivotValues());
        merged.put(morphType, morphClass);
        merged.putAll(attributes);
        Class<? extends MorphPivot> type = using == null ? MorphPivot.class : using.asSubclass(MorphPivot.class);
        MorphPivot pivot = Pivot.fromAttributes(type.cast(freshPivotOf(type)), getParent(), merged,
                getTable(), exists);
        return pivot.setMorphType(morphType).setMorphClass(morphClass);
    }

    @Override
    public MorphToMany<R> withPivot(String... columns) {
        super.withPivot(columns);
        return this;
    }

    @Override
    public MorphToMany<R> withTimestamps() {
        super.withTimestamps();
        return this;
    }

    @Override
    public MorphToMany<R> withTimestamps(String createdAt, String updatedAt) {
        super.withTimestamps(createdAt, updatedAt);
        return this;
    }

    @Override
    public MorphToMany<R> as(String accessor) {
        super.as(accessor);
        return this;
    }

    @Override
    public MorphToMany<R> using(Class<? extends Pivot> pivotClass) {
        Objects.requireNonNull(pivotClass, "pivotClass");
        if (!MorphPivot.class.isAssignableFrom(pivotClass)) {
            throw new IllegalStateException("The pivot class [" + pivotClass.getName()
                    + "] of a morph-to-many relation must extend MorphPivot");
        }
        super.using(pivotClass);
        return this;
    }

    @Override
    public MorphToMany<R> wherePivot(String column, Object value) {
        super.wherePivot(column, value);
        return this;
    }

    @Override
    public MorphToMany<R> wherePivot(String column, String operator, Object value) {
        super.wherePivot(column, operator, value);
        return this;
    }

    @Override
    public MorphToMany<R> orWherePivot(String column, Object value) {
        super.orWherePivot(column, value);
        return this;
    }

    @Override
    public MorphToMany<R> orWherePivot(String column, String operator, Object value) {
        super.orWherePivot(column, operator, value);
        return this;
    }

    @Override
    public MorphToMany<R> wherePivotIn(String column, Collection<?> values) {
        super.wherePivotIn(column, values);
        return this;
    }

    @Override
    public MorphToMany<R> wherePivotNotIn(String column, Collection<?> values) {
        super.wherePivotNotIn(column, values);
        return this;
    }

    @Override
    public MorphToMany<R> orWherePivotIn(String column, Collection<?> values) {
        super.orWherePivotIn(column, values);
        return this;
    }

    @Override
    public MorphToMany<R> orWherePivotNotIn(String column, Collection<?> values) {
        super.orWherePivotNotIn(column, values);
        return this;
    }

    @Override
    public MorphToMany<R> wherePivotBetween(String column, List<?> values) {
        super.wherePivotBetween(column, values);
        return this;
    }

    @Override
    public MorphToMany<R> wherePivotNotBetween(String column, List<?> values) {
        super.wherePivotNotBetween(column, values);
        return this;
    }

    @Override
    public MorphToMany<R> orWherePivotBetween(String column, List<?> values) {
        super.orWherePivotBetween(column, values);
        return this;
    }

    @Override
    public MorphToMany<R> orWherePivotNotBetween(String column, List<?> values) {
        super.orWherePivotNotBetween(column, values);
        return this;
    }

    @Override
    public MorphToMany<R> wherePivotNull(String column) {
        super.wherePivotNull(column);
        return this;
    }

    @Override
    public MorphToMany<R> wherePivotNotNull(String column) {
        super.wherePivotNotNull(column);
        return this;
    }

    @Override
    public MorphToMany<R> orWherePivotNull(String column) {
        super.orWherePivotNull(column);
        return this;
    }

    @Override
    public MorphToMany<R> orWherePivotNotNull(String column) {
        super.orWherePivotNotNull(column);
        return this;
    }

    @Override
    public MorphToMany<R> orderByPivot(String column) {
        super.orderByPivot(column);
        return this;
    }

    @Override
    public MorphToMany<R> orderByPivot(String column, String direction) {
        super.orderByPivot(column, direction);
        return this;
    }

    @Override
    public MorphToMany<R> orderByPivotDesc(String column) {
        super.orderByPivotDesc(column);
        return this;
    }

    @Override
    public MorphToMany<R> withPivotValue(String column, Object value) {
        requireOwnType(column, value);
        super.withPivotValue(column, value);
        return this;
    }

    @Override
    public MorphToMany<R> withPivotValue(Map<String, ?> values) {
        super.withPivotValue(values);
        return this;
    }

    @Override
    public MorphToMany<R> chaperone() {
        super.chaperone();
        return this;
    }

    @Override
    public MorphToMany<R> chaperone(String declaringInverse) {
        super.chaperone(declaringInverse);
        return this;
    }

    @Override
    public MorphToMany<R> chaperone(String declaringInverse, String relatedInverse) {
        super.chaperone(declaringInverse, relatedInverse);
        return this;
    }

    @Override
    public MorphToMany<R> withoutChaperone() {
        super.withoutChaperone();
        return this;
    }
}
