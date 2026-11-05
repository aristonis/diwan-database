package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;

/**
 * The configuration half of a belongsToMany relation: the pivot table and its keys, the extra pivot
 * columns, the accessor, the pivot class, the timestamp columns, the chaperone names and the
 * conditions on the pivot table.
 *
 * <p>Every configuration method returns the relation, so calls chain in any order. A pivot condition
 * is recorded in call order and applied to the lazy query, the eager query and the existence subquery
 * of {@code has} and {@code whereHas}. In an existence subquery the conditions form one group after
 * the parent's constraint, so an {@code or} among them cannot widen it. {@code orderByPivot} is not a
 * condition: existence subqueries and aggregates drop it.
 *
 * <p>A closure given to {@code has} or {@code whereHas} receives the related model's builder, which
 * has no {@code wherePivot} method. Declare pivot conditions on the relation itself.
 *
 * <p>{@link #getQuery()} returns the relation's own constrained builder. A clause added straight to it
 * is not recorded, so {@link #pivotClauses()} does not list it. A relation is single-threaded, like
 * every model, and must not be shared across threads.
 *
 * @param <R> the related model class
 */
abstract class PivotClauses<R extends Model> extends Relation<R, BelongsToMany<R>> {

    /** The accessor a pivot is stored under when none is given, as in Laravel. */
    private static final String DEFAULT_ACCESSOR = "pivot";

    private static final String ASCENDING = "asc";

    private static final String DESCENDING = "desc";

    private static final String EQUALS = "=";

    private final String table;

    private final String foreignPivotKey;

    private final String relatedPivotKey;

    private final String parentKey;

    private final String relatedKey;

    private final List<String> pivotColumns = new ArrayList<>();

    private final List<PivotClause> pivotClauses = new ArrayList<>();

    private final Map<String, Object> pivotValues = new LinkedHashMap<>();

    private String pivotAccessor = DEFAULT_ACCESSOR;

    private String createdAt;

    private String updatedAt;

    private Class<? extends Pivot> pivotClass;

    private String declaringChaperone;

    private String relatedChaperone;

    /** Set while an existence subquery replays the recorded wheres; see {@link #existenceWheres()}. */
    private boolean isDeferringPivotWheres;

    /**
     * Builds the configuration of a relation over the given pivot table and keys.
     *
     * @param query the related model's query, never {@code null}
     * @param parent the model the relation is read from, never {@code null}
     * @param table the pivot table, never {@code null}
     * @param foreignPivotKey the pivot column that holds the parent's key, never {@code null}
     * @param relatedPivotKey the pivot column that holds the related key, never {@code null}
     * @param parentKey the parent's column the pivot points at, never {@code null}
     * @param relatedKey the related model's column the pivot points at, never {@code null}
     * @param relation the relation's name, or {@code null} when none is known
     * @throws NullPointerException if a non-null argument is {@code null}
     */
    PivotClauses(EloquentBuilder<R> query, Model parent, String table, String foreignPivotKey,
            String relatedPivotKey, String parentKey, String relatedKey, String relation) {
        super(query, parent, relation);
        this.table = Objects.requireNonNull(table, "table");
        this.foreignPivotKey = Objects.requireNonNull(foreignPivotKey, "foreignPivotKey");
        this.relatedPivotKey = Objects.requireNonNull(relatedPivotKey, "relatedPivotKey");
        this.parentKey = Objects.requireNonNull(parentKey, "parentKey");
        this.relatedKey = Objects.requireNonNull(relatedKey, "relatedKey");
    }

    // ---- getters

    /**
     * Returns the pivot table's name, such as {@code role_user}.
     *
     * @return the table, never {@code null}
     */
    public final String getTable() {
        return table;
    }

    /**
     * Returns the pivot column that holds the parent's key, without the table.
     *
     * @return the column, never {@code null}
     */
    public final String getForeignPivotKeyName() {
        return foreignPivotKey;
    }

    /**
     * Returns the pivot column that holds the parent's key, qualified with the pivot table.
     *
     * @return the qualified column, such as {@code role_user.user_id}, never {@code null}
     */
    public final String getQualifiedForeignPivotKeyName() {
        return table + "." + foreignPivotKey;
    }

    /**
     * Returns the pivot column that holds the related key, without the table.
     *
     * @return the column, never {@code null}
     */
    public final String getRelatedPivotKeyName() {
        return relatedPivotKey;
    }

    /**
     * Returns the pivot column that holds the related key, qualified with the pivot table.
     *
     * @return the qualified column, such as {@code role_user.role_id}, never {@code null}
     */
    public final String getQualifiedRelatedPivotKeyName() {
        return table + "." + relatedPivotKey;
    }

    /**
     * Returns the parent's column the pivot points at, without the table.
     *
     * @return the column, never {@code null}
     */
    public final String getParentKeyName() {
        return parentKey;
    }

    /**
     * Returns the parent's column the pivot points at, qualified with the parent's table.
     *
     * @return the qualified column, never {@code null}
     */
    public final String getQualifiedParentKeyName() {
        return getParent().qualifyColumn(parentKey);
    }

    /**
     * Returns the related model's column the pivot points at, without the table.
     *
     * @return the column, never {@code null}
     */
    public final String getRelatedKeyName() {
        return relatedKey;
    }

    /**
     * Returns the related model's column the pivot points at, qualified with its table. Inside an
     * existence subquery the table is the alias the subquery uses.
     *
     * @return the qualified column, never {@code null}
     */
    public final String getQualifiedRelatedKeyName() {
        return getRelated().qualifyColumn(relatedKey);
    }

    /**
     * Returns the name the pivot is stored under on each related model.
     *
     * @return the accessor, never {@code null}; {@code pivot} unless {@link #as(String)} changed it
     */
    public final String getPivotAccessor() {
        return pivotAccessor;
    }

    /**
     * Returns the extra pivot columns in the order they were added.
     *
     * @return an unmodifiable copy, never {@code null}; later changes to the relation do not show
     */
    public final List<String> getPivotColumns() {
        return Collections.unmodifiableList(new ArrayList<>(pivotColumns));
    }

    /**
     * Returns the pivot class the related models' pivots are built as.
     *
     * @return the class given to {@link #using(Class)}, or {@link Pivot} when none was given
     */
    public final Class<? extends Pivot> getPivotClass() {
        return pivotClass != null ? pivotClass : Pivot.class;
    }

    /**
     * Returns the pivot column that holds the creation time, when the relation keeps one.
     *
     * @return the column, or {@code null} when none is kept
     */
    public final String createdAt() {
        return createdAt;
    }

    /**
     * Returns the pivot column that holds the update time, when the relation keeps one.
     *
     * @return the column, or {@code null} when none is kept
     */
    public final String updatedAt() {
        return updatedAt;
    }

    // ---- configuration

    /**
     * Adds extra pivot columns to read, after the ones already added. A column already present is
     * not added again, and an empty argument list changes nothing.
     *
     * <p>The values come back on each related model as attributes that are not dirty. A later
     * {@code discardChanges()} on the related model restores them.
     *
     * <p>A column that contains a dot is refused, because each column is read under an alias built
     * from its name. Every column is checked before any is added, so a refused call changes nothing.
     *
     * @param columns the pivot columns, never {@code null}, no {@code null} element
     * @return this relation
     * @throws NullPointerException if {@code columns} or one of its elements is {@code null}
     * @throws IllegalArgumentException if a column contains a dot; nothing is added
     */
    public BelongsToMany<R> withPivot(String... columns) {
        Objects.requireNonNull(columns, "columns");
        for (String column : columns) {
            PivotColumns.requireUndotted(column, describe());
        }
        for (String column : columns) {
            addPivotColumn(column);
        }
        return self();
    }

    /**
     * Keeps the parent's creation and update time columns on the pivot, as a call with no names does.
     *
     * @return this relation
     */
    public BelongsToMany<R> withTimestamps() {
        return withTimestamps(null, null);
    }

    /**
     * Keeps a creation and an update time column on the pivot and reads them with the other pivot columns.
     *
     * <p>A {@code null} name takes the parent's column of that kind. An empty name turns that column
     * off. A parent column that is {@code null} is skipped, and no error is raised.
     *
     * @param createdAt the pivot's creation column, {@code null} for the parent's, empty for none
     * @param updatedAt the pivot's update column, {@code null} for the parent's, empty for none
     * @return this relation
     * @throws IllegalArgumentException if a name contains a dot; nothing is changed
     */
    public BelongsToMany<R> withTimestamps(String createdAt, String updatedAt) {
        Model parent = getParent();
        String created = PivotColumns.timestampColumn(createdAt, parent.getCreatedAtColumn());
        String updated = PivotColumns.timestampColumn(updatedAt, parent.getUpdatedAtColumn());
        withPivot(PivotColumns.enabledColumns(created, updated));
        this.createdAt = created;
        this.updatedAt = updated;
        return self();
    }

    /**
     * Stores the pivot under another name on each related model, instead of {@code pivot}.
     *
     * <p>A loaded relation of the same name on the related model is replaced without an error, as
     * in Laravel. The name applies to the reads that run after this call; an earlier result keeps
     * the name it was read under.
     *
     * @param accessor the attribute name, never {@code null} or empty
     * @return this relation
     * @throws NullPointerException if {@code accessor} is {@code null}
     * @throws IllegalArgumentException if {@code accessor} is empty
     */
    public BelongsToMany<R> as(String accessor) {
        Objects.requireNonNull(accessor, "accessor");
        if (accessor.isEmpty()) {
            throw new IllegalArgumentException("The pivot accessor of " + describe() + " may not be empty");
        }
        this.pivotAccessor = accessor;
        return self();
    }

    /**
     * Builds the pivots as the given class, which must be registered, instead of {@link Pivot}.
     *
     * <p>The registration is checked at this call. A chaperone asked for earlier stays off, and a
     * chaperone asked for after this call turns on.
     *
     * @param pivotClass the pivot class, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code pivotClass} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.internal.UnregisteredModelException if the class
     *     has no registry entry
     */
    public BelongsToMany<R> using(Class<? extends Pivot> pivotClass) {
        Objects.requireNonNull(pivotClass, "pivotClass");
        Access.eloquent().newRegistered(pivotClass, null);
        this.pivotClass = pivotClass;
        return self();
    }

    // ---- the pivot-table conditions

    /**
     * Adds {@code column = value} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param value the value, {@code null} compiles to {@code is null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public BelongsToMany<R> wherePivot(String column, Object value) {
        return addPivotWhere(PivotClause.basic(qualifyPivotColumn(column), null, value, false));
    }

    /**
     * Adds {@code column operator value} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param operator the comparison operator, such as {@code >=}, never {@code null}
     * @param value the value
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the query builder refuses the operator, when the query is built
     */
    public BelongsToMany<R> wherePivot(String column, String operator, Object value) {
        return addPivotWhere(PivotClause.basic(qualifyPivotColumn(column), operator, value, false));
    }

    /**
     * Adds {@code or column = value} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param value the value, {@code null} compiles to {@code is null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public BelongsToMany<R> orWherePivot(String column, Object value) {
        return addPivotWhere(PivotClause.basic(qualifyPivotColumn(column), null, value, true));
    }

    /**
     * Adds {@code or column operator value} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param operator the comparison operator, never {@code null}
     * @param value the value
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the query builder refuses the operator, when the query is built
     */
    public BelongsToMany<R> orWherePivot(String column, String operator, Object value) {
        return addPivotWhere(PivotClause.basic(qualifyPivotColumn(column), operator, value, true));
    }

    /**
     * Adds {@code column in (values)} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param values the values, never {@code null}; empty matches no row
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     */
    public BelongsToMany<R> wherePivotIn(String column, Collection<?> values) {
        return addPivotWhere(PivotClause.in(qualifyPivotColumn(column), values, false, false));
    }

    /**
     * Adds {@code column not in (values)} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param values the values, never {@code null}; empty matches every row
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     */
    public BelongsToMany<R> wherePivotNotIn(String column, Collection<?> values) {
        return addPivotWhere(PivotClause.in(qualifyPivotColumn(column), values, false, true));
    }

    /**
     * Adds {@code or column in (values)} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param values the values, never {@code null}; empty matches no row
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     */
    public BelongsToMany<R> orWherePivotIn(String column, Collection<?> values) {
        return addPivotWhere(PivotClause.in(qualifyPivotColumn(column), values, true, false));
    }

    /**
     * Adds {@code or column not in (values)} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param values the values, never {@code null}; empty matches every row
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     */
    public BelongsToMany<R> orWherePivotNotIn(String column, Collection<?> values) {
        return addPivotWhere(PivotClause.in(qualifyPivotColumn(column), values, true, true));
    }

    /**
     * Adds {@code column between two values} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param values the low and the high value, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     * @throws IllegalArgumentException if the list does not hold exactly two values, when the query is built
     */
    public BelongsToMany<R> wherePivotBetween(String column, List<?> values) {
        return addPivotWhere(PivotClause.between(qualifyPivotColumn(column), values, false, false));
    }

    /**
     * Adds {@code column not between two values} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param values the low and the high value, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     * @throws IllegalArgumentException if the list does not hold exactly two values, when the query is built
     */
    public BelongsToMany<R> wherePivotNotBetween(String column, List<?> values) {
        return addPivotWhere(PivotClause.between(qualifyPivotColumn(column), values, false, true));
    }

    /**
     * Adds {@code or column between two values} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param values the low and the high value, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     * @throws IllegalArgumentException if the list does not hold exactly two values, when the query is built
     */
    public BelongsToMany<R> orWherePivotBetween(String column, List<?> values) {
        return addPivotWhere(PivotClause.between(qualifyPivotColumn(column), values, true, false));
    }

    /**
     * Adds {@code or column not between two values} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @param values the low and the high value, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code values} is {@code null}
     * @throws IllegalArgumentException if the list does not hold exactly two values, when the query is built
     */
    public BelongsToMany<R> orWherePivotNotBetween(String column, List<?> values) {
        return addPivotWhere(PivotClause.between(qualifyPivotColumn(column), values, true, true));
    }

    /**
     * Adds {@code column is null} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public BelongsToMany<R> wherePivotNull(String column) {
        return addPivotWhere(PivotClause.nullCheck(qualifyPivotColumn(column), false, false));
    }

    /**
     * Adds {@code column is not null} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public BelongsToMany<R> wherePivotNotNull(String column) {
        return addPivotWhere(PivotClause.nullCheck(qualifyPivotColumn(column), false, true));
    }

    /**
     * Adds {@code or column is null} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public BelongsToMany<R> orWherePivotNull(String column) {
        return addPivotWhere(PivotClause.nullCheck(qualifyPivotColumn(column), true, false));
    }

    /**
     * Adds {@code or column is not null} on the pivot table to the relation's query.
     *
     * @param column the pivot column, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public BelongsToMany<R> orWherePivotNotNull(String column) {
        return addPivotWhere(PivotClause.nullCheck(qualifyPivotColumn(column), true, true));
    }

    /**
     * Orders the query by a pivot column, ascending. Ordering is not a condition, so an existence
     * subquery and an aggregate drop it.
     *
     * @param column the pivot column, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public BelongsToMany<R> orderByPivot(String column) {
        return orderPivot(column, ASCENDING);
    }

    /**
     * Orders the query by a pivot column in the given direction.
     *
     * @param column the pivot column, never {@code null}
     * @param direction {@code asc} or {@code desc} in any case, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} or {@code direction} is {@code null}
     * @throws IllegalArgumentException if the direction is neither {@code asc} nor {@code desc}, when
     *     the query is built
     */
    public BelongsToMany<R> orderByPivot(String column, String direction) {
        return orderPivot(column, direction);
    }

    /**
     * Orders the query by a pivot column, descending.
     *
     * @param column the pivot column, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     */
    public BelongsToMany<R> orderByPivotDesc(String column) {
        return orderPivot(column, DESCENDING);
    }

    /**
     * Filters the pivot table on {@code column = value} and records the pair, so the pivot's value
     * for that column is known.
     *
     * @param column the pivot column, never {@code null}
     * @param value the value, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}; nothing is recorded
     */
    public BelongsToMany<R> withPivotValue(String column, Object value) {
        PivotColumns.requireValue(column, value, describe());
        pivotValues.put(column, value);
        return wherePivot(column, EQUALS, value);
    }

    /**
     * Filters the pivot table on each entry of the map, as {@code withPivotValue(String, Object)}
     * does for each one, in the map's iteration order.
     *
     * <p>Every value is checked before any is applied. One {@code null} value throws, and then no
     * entry is applied or recorded.
     *
     * @param values the column and value pairs, never {@code null}, no {@code null} key
     * @return this relation
     * @throws NullPointerException if {@code values} or one of its keys is {@code null}
     * @throws IllegalArgumentException if a value is {@code null}; nothing is applied
     */
    public BelongsToMany<R> withPivotValue(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            PivotColumns.requireValue(entry.getKey(), entry.getValue(), describe());
        }
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            withPivotValue(entry.getKey(), entry.getValue());
        }
        return self();
    }

    // ---- chaperone

    /**
     * Links each pivot to its parent and to its related model, guessing both inverse names from the
     * pivot class. Does nothing when no pivot class was given with {@link #using(Class)}.
     *
     * @return this relation
     */
    public BelongsToMany<R> chaperone() {
        return chaperone(null, null);
    }

    /**
     * Links each pivot to its parent under the given name, and guesses the name on the related side.
     * Does nothing when no pivot class was given with {@link #using(Class)}.
     *
     * @param declaringInverse the pivot relation that points at the parent, or {@code null} to guess
     * @return this relation
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the pivot
     *     class declares no relation of the given name
     */
    public BelongsToMany<R> chaperone(String declaringInverse) {
        return chaperone(declaringInverse, null);
    }

    /**
     * Links each pivot to its parent and to its related model under the given names. A {@code null}
     * name is guessed from the pivot class, and a side with no match is left unlinked without an
     * error. Does nothing when no pivot class was given with {@link #using(Class)}.
     *
     * @param declaringInverse the pivot relation that points at the parent, or {@code null} to guess
     * @param relatedInverse the pivot relation that points at the related model, or {@code null} to guess
     * @return this relation
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the pivot
     *     class declares no relation of a given name
     */
    public BelongsToMany<R> chaperone(String declaringInverse, String relatedInverse) {
        if (pivotClass == null) {
            return self();
        }
        Pivot sample = Access.eloquent().newRegistered(pivotClass, null);
        declaringChaperone = inverseName(sample, declaringInverse, foreignPivotKey, getParent());
        relatedChaperone = inverseName(sample, relatedInverse, relatedPivotKey, getRelated());
        return self();
    }

    /**
     * Removes both chaperone links, so pivots are not linked to their parent or related model.
     *
     * @return this relation
     */
    public BelongsToMany<R> withoutChaperone() {
        declaringChaperone = null;
        relatedChaperone = null;
        return self();
    }

    // ---- package-private: the contract the read side uses

    /**
     * Qualifies a pivot column with the pivot table, unless it already names a table.
     *
     * @param column the column as the caller wrote it, never {@code null}
     * @return the column, qualified when it had no dot
     * @throws NullPointerException if {@code column} is {@code null}
     */
    final String qualifyPivotColumn(String column) {
        Objects.requireNonNull(column, "column");
        return column.indexOf('.') >= 0 ? column : table + "." + column;
    }

    /**
     * Returns the related table's name with every column, such as {@code roles.*}.
     *
     * @return the select list entry, never {@code null}
     */
    final String relatedAll() {
        return getRelated().getTable() + ".*";
    }

    /**
     * Returns the select entries that carry the pivot columns: the two keys first, then each extra
     * column, each aliased with the pivot prefix and listed once.
     *
     * @return the entries, never {@code null}, never empty
     */
    String[] aliasedPivotColumns() {
        return PivotColumns.aliased(table, foreignPivotKey, relatedPivotKey, pivotColumns);
    }

    /**
     * Returns the pivot class given to {@link #using(Class)}, without the default.
     *
     * @return the class, or {@code null} when {@code using} was not called
     */
    final Class<? extends Pivot> usingClass() {
        return pivotClass;
    }

    /**
     * Returns the pivot relation that points at the parent, when a chaperone is set on that side.
     *
     * @return the relation name, or {@code null} when that side is not linked
     */
    final String declaringChaperone() {
        return declaringChaperone;
    }

    /**
     * Returns the pivot relation that points at the related model, when a chaperone is set on that side.
     *
     * @return the relation name, or {@code null} when that side is not linked
     */
    final String relatedChaperone() {
        return relatedChaperone;
    }

    /**
     * Returns the recorded pivot conditions in the order they were added.
     *
     * @return an unmodifiable copy, never {@code null}; later changes to the relation do not show
     */
    final List<PivotClause> pivotClauses() {
        return Collections.unmodifiableList(new ArrayList<>(pivotClauses));
    }

    /**
     * Returns the values recorded by {@code withPivotValue(String, Object)}, keyed by column in the
     * order they were added.
     *
     * @return an unmodifiable copy, never {@code null}
     */
    final Map<String, Object> pivotValues() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pivotValues));
    }

    /**
     * Replays the recorded wheres into an existence subquery: the relation's other wheres in the
     * order they were added, then every pivot condition in one nested group.
     *
     * <p>The caller adds the parent's correlation before this call, so the group comes after it. A
     * flat {@code or} among the pivot conditions would otherwise match rows of every parent. The
     * relation must be unconstrained, which every existence path guarantees by building it fresh.
     *
     * @return the existence query, the relation's own instance, never {@code null}
     */
    final EloquentBuilder<R> existenceWheres() {
        isDeferringPivotWheres = true;
        EloquentBuilder<R> existence;
        try {
            existence = existenceBaseQuery();
        } finally {
            isDeferringPivotWheres = false;
        }
        List<PivotClause> conditions = pivotClauses();
        if (!conditions.isEmpty()) {
            existence.where(group -> conditions.forEach(clause -> clause.applyTo(group)));
        }
        return existence;
    }

    // ---- helpers

    private BelongsToMany<R> addPivotWhere(PivotClause clause) {
        pivotClauses.add(clause);
        applyWhere(builder -> {
            if (!isDeferringPivotWheres) {
                builder.query(clause::applyTo);
            }
        });
        return self();
    }

    private BelongsToMany<R> orderPivot(String column, String direction) {
        String qualified = qualifyPivotColumn(column);
        applyClause(builder -> builder.orderBy(qualified, direction));
        return self();
    }

    private void addPivotColumn(String column) {
        PivotColumns.requireUndotted(column, describe());
        if (!pivotColumns.contains(column)) {
            pivotColumns.add(column);
        }
    }

    private String inverseName(Model sample, String requested, String key, Model model) {
        if (requested != null) {
            return InverseRelations.checkPivot(sample, requested);
        }
        return InverseRelations.guessPivot(sample, key, model);
    }

    private String describe() {
        String name = getRelationName() != null ? getRelationName() : getTable();
        return "relation [" + name + "] on model [" + getParent().getClass().getName() + "]";
    }
}
