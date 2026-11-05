package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.MorphEagerOptions;
import org.aristonis.diwan.database.eloquent.relations.Relation;
import org.aristonis.diwan.database.query.Builder;

/**
 * The morph-to query methods of {@link EloquentBuilder}: {@code hasMorph} and its
 * {@code whereHasMorph} forms, {@code whereMorphedTo}, and per-class eager options with
 * {@code withMorph}.
 *
 * <p>Types are model classes or morph aliases, resolved through the morph map; the single element
 * {@code "*"} stands for every non-empty type found in the table. A {@code "*"} reads those types
 * with one extra query, while the condition is being built, on a query without global scopes.
 * Every type is resolved before any condition is added. The negative forms ({@code doesntHaveMorph}
 * and counts of "fewer than") also match a row whose type is null.
 *
 * @param <T> the model class
 */
abstract class EloquentMorphQueries<T extends Model> extends EloquentRelationQueries<T> {

    private static final String AT_LEAST = ">=";
    private static final String LESS_THAN = "<";
    private static final int ONE = 1;

    EloquentMorphQueries(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
    }

    /**
     * Adds a condition that a morph-to relation has an owner of one of the types, as Laravel's
     * {@code hasMorph}: one group, an {@code or} of {@code (type = ? and exists(owner query))} per type.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code types} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> hasMorph(String relation, List<?> types) {
        return morph(relation, types, AT_LEAST, ONE, false, null);
    }

    /**
     * Adds a condition on the count of a morph-to relation's owners of the types, per type.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param operator the count comparison: {@code =}, {@code <}, {@code >}, {@code <=}, {@code >=},
     *     {@code <>} or {@code !=}, never {@code null}
     * @param count the count compared with
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the operator is not one of the seven, the name is a dot path or not a
     *     morph-to relation, or a type is neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> hasMorph(String relation, List<?> types, String operator,
            long count) {
        return morph(relation, types, operator, count, false, null);
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has an owner of one of the types.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code types} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orHasMorph(String relation, List<?> types) {
        return morph(relation, types, AT_LEAST, ONE, true, null);
    }

    /**
     * Adds an {@code or} condition on the count of a morph-to relation's owners of the types, per type.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param operator the count comparison: {@code =}, {@code <}, {@code >}, {@code <=}, {@code >=},
     *     {@code <>} or {@code !=}, never {@code null}
     * @param count the count compared with
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the operator is not one of the seven, the name is a dot path or not a
     *     morph-to relation, or a type is neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orHasMorph(String relation, List<?> types, String operator,
            long count) {
        return morph(relation, types, operator, count, true, null);
    }

    /**
     * Adds a condition that a morph-to relation has no owner of any of the types. A row with a null
     * type matches too, for every type list.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code types} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> doesntHaveMorph(String relation, List<?> types) {
        return morph(relation, types, LESS_THAN, ONE, false, null);
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has no owner of any of the types. A row
     * with a null type matches too.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code types} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orDoesntHaveMorph(String relation, List<?> types) {
        return morph(relation, types, LESS_THAN, ONE, true, null);
    }

    /**
     * Adds a condition that a morph-to relation has an owner of one of the types matching a
     * constraint, as Laravel's {@code whereHasMorph}.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param constraint the constraint on each owner query, given the owner class, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> whereHasMorph(String relation, List<?> types,
            BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint) {
        return morph(relation, types, AT_LEAST, ONE, false, Objects.requireNonNull(constraint, "constraint"));
    }

    /**
     * Adds a condition that a morph-to relation has an owner of one of the types matching a
     * constraint, as Laravel's {@code whereHasMorph}.
     * The constraint does not see the owner class.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param constraint the constraint on each owner query, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> whereHasMorph(String relation, List<?> types,
            Consumer<EloquentBuilder<?>> constraint) {
        Objects.requireNonNull(constraint, "constraint");
        return whereHasMorph(relation, types, (query, type) -> constraint.accept(query));
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has an owner of one of the types
     * matching a constraint.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param constraint the constraint on each owner query, given the owner class, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orWhereHasMorph(String relation, List<?> types,
            BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint) {
        return morph(relation, types, AT_LEAST, ONE, true, Objects.requireNonNull(constraint, "constraint"));
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has an owner of one of the types
     * matching a constraint.
     * The constraint does not see the owner class.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param constraint the constraint on each owner query, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orWhereHasMorph(String relation, List<?> types,
            Consumer<EloquentBuilder<?>> constraint) {
        Objects.requireNonNull(constraint, "constraint");
        return orWhereHasMorph(relation, types, (query, type) -> constraint.accept(query));
    }

    /**
     * Adds a condition that a morph-to relation has no owner of any of the types matching a
     * constraint. A row with a null type matches too.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param constraint the constraint on each owner query, given the owner class, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> whereDoesntHaveMorph(String relation, List<?> types,
            BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint) {
        return morph(relation, types, LESS_THAN, ONE, false, Objects.requireNonNull(constraint, "constraint"));
    }

    /**
     * Adds a condition that a morph-to relation has no owner of any of the types matching a
     * constraint. A row with a null type matches too.
     * The constraint does not see the owner class.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param constraint the constraint on each owner query, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> whereDoesntHaveMorph(String relation, List<?> types,
            Consumer<EloquentBuilder<?>> constraint) {
        Objects.requireNonNull(constraint, "constraint");
        return whereDoesntHaveMorph(relation, types, (query, type) -> constraint.accept(query));
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has no owner of any of the types
     * matching a constraint. A row with a null type matches too.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param constraint the constraint on each owner query, given the owner class, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orWhereDoesntHaveMorph(String relation, List<?> types,
            BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint) {
        return morph(relation, types, LESS_THAN, ONE, true, Objects.requireNonNull(constraint, "constraint"));
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has no owner of any of the types
     * matching a constraint. A row with a null type matches too.
     * The constraint does not see the owner class.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param constraint the constraint on each owner query, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orWhereDoesntHaveMorph(String relation, List<?> types,
            Consumer<EloquentBuilder<?>> constraint) {
        Objects.requireNonNull(constraint, "constraint");
        return orWhereDoesntHaveMorph(relation, types, (query, type) -> constraint.accept(query));
    }

    /**
     * Adds a condition that a morph-to relation has an owner of one of the types whose column
     * compares with a value, as Laravel's {@code whereMorphRelation}.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param column the owner column, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> whereMorphRelation(String relation, List<?> types, String column,
            Object value) {
        Objects.requireNonNull(column, "column");
        return whereHasMorph(relation, types, (query, type) -> query.where(column, value));
    }

    /**
     * Adds a condition that a morph-to relation has an owner of one of the types whose column
     * compares with a value, as Laravel's {@code whereMorphRelation}.
     * The column is compared with an operator.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param column the owner column, never {@code null}
     * @param operator the column operator, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types}, {@code column} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> whereMorphRelation(String relation, List<?> types, String column, String operator,
            Object value) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        return whereHasMorph(relation, types, (query, type) -> query.where(column, operator, value));
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has an owner of one of the types whose
     * column compares with a value.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param column the owner column, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orWhereMorphRelation(String relation, List<?> types, String column,
            Object value) {
        Objects.requireNonNull(column, "column");
        return orWhereHasMorph(relation, types, (query, type) -> query.where(column, value));
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has an owner of one of the types whose
     * column compares with a value.
     * The column is compared with an operator.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param column the owner column, never {@code null}
     * @param operator the column operator, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types}, {@code column} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orWhereMorphRelation(String relation, List<?> types, String column, String operator,
            Object value) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        return orWhereHasMorph(relation, types, (query, type) -> query.where(column, operator, value));
    }

    /**
     * Adds a condition that a morph-to relation has no owner of any of the types whose column
     * compares with a value. A row with a null type matches too.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param column the owner column, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> whereMorphDoesntHaveRelation(String relation, List<?> types, String column,
            Object value) {
        Objects.requireNonNull(column, "column");
        return whereDoesntHaveMorph(relation, types, (query, type) -> query.where(column, value));
    }

    /**
     * Adds a condition that a morph-to relation has no owner of any of the types whose column
     * compares with a value. A row with a null type matches too.
     * The column is compared with an operator.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param column the owner column, never {@code null}
     * @param operator the column operator, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types}, {@code column} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> whereMorphDoesntHaveRelation(String relation, List<?> types, String column,
            String operator, Object value) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        return whereDoesntHaveMorph(relation, types, (query, type) -> query.where(column, operator, value));
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has no owner of any of the types whose
     * column compares with a value. A row with a null type matches too.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param column the owner column, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types} or {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orWhereMorphDoesntHaveRelation(String relation, List<?> types, String column,
            Object value) {
        Objects.requireNonNull(column, "column");
        return orWhereDoesntHaveMorph(relation, types, (query, type) -> query.where(column, value));
    }

    /**
     * Adds an {@code or} condition that a morph-to relation has no owner of any of the types whose
     * column compares with a value. A row with a null type matches too.
     * The column is compared with an operator.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or a list holding only {@code "*"}, never {@code null}
     * @param column the owner column, never {@code null}
     * @param operator the column operator, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code types}, {@code column} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, or a type is
     *     neither a model class nor a string
     * @throws ClassMorphViolationException if a type cannot be resolved; no condition has been added
     */
    public final EloquentBuilder<T> orWhereMorphDoesntHaveRelation(String relation, List<?> types, String column,
            String operator, Object value) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        return orWhereDoesntHaveMorph(relation, types, (query, type) -> query.where(column, operator, value));
    }

    /**
     * Adds a condition that a morph-to relation points at a target, as Laravel's
     * {@code whereMorphedTo}: a model by type and key, a class or alias by type, a collection grouped
     * per type, {@code null} by a null type.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param target a model, a model class, a morph alias or class name, a non-empty collection of models, or
     *     {@code null} for a row with no type
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, a collection is empty,
     *     a model has no key, or the target is of another kind
     * @throws ClassMorphViolationException if a class or alias cannot be resolved
     */
    public final EloquentBuilder<T> whereMorphedTo(String relation, Object target) {
        return morphedTo(relation, target, false, false);
    }

    /**
     * Adds a condition that a morph-to relation does not point at a target. A row with a null type or
     * a null key counts as not pointing at it.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param target a model, a model class, a morph alias or class name, a non-empty collection of models, or
     *     {@code null} for a row with no type
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, a collection is empty,
     *     a model has no key, or the target is of another kind
     * @throws ClassMorphViolationException if a class or alias cannot be resolved
     */
    public final EloquentBuilder<T> whereNotMorphedTo(String relation, Object target) {
        return morphedTo(relation, target, true, false);
    }

    /**
     * Adds an {@code or} condition that a morph-to relation points at a target.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param target a model, a model class, a morph alias or class name, a non-empty collection of models, or
     *     {@code null} for a row with no type
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, a collection is empty,
     *     a model has no key, or the target is of another kind
     * @throws ClassMorphViolationException if a class or alias cannot be resolved
     */
    public final EloquentBuilder<T> orWhereMorphedTo(String relation, Object target) {
        return morphedTo(relation, target, false, true);
    }

    /**
     * Adds an {@code or} condition that a morph-to relation does not point at a target. A row with a
     * null type or a null key counts as not pointing at it.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param target a model, a model class, a morph alias or class name, a non-empty collection of models, or
     *     {@code null} for a row with no type
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, a collection is empty,
     *     a model has no key, or the target is of another kind
     * @throws ClassMorphViolationException if a class or alias cannot be resolved
     */
    public final EloquentBuilder<T> orWhereNotMorphedTo(String relation, Object target) {
        return morphedTo(relation, target, true, true);
    }

    /**
     * Eager loads a morph-to relation with options per owner class: other relations, counts and
     * constraints for one class only.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param options fills the per-class options, never {@code null}; it runs at once, so a bad key
     *     throws here and not when the query runs
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code options} is {@code null}, or an option key
     *     is {@code null}
     * @throws IllegalArgumentException if an option key is {@code "*"}, a class that is not a model, or
     *     neither a class nor a string
     * @throws ClassMorphViolationException if an option key is an alias or class name that cannot be
     *     resolved, or a class without an alias while the morph map is required
     */
    public final EloquentBuilder<T> withMorph(String relation, Consumer<MorphEagerOptions> options) {
        return withMorph(relation, options, null);
    }

    /**
     * Eager loads a morph-to relation like {@link EloquentBuilder#withMorph(String, Consumer)}, with a constraint that
     * runs on every owner class.
     *
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param options fills the per-class options, never {@code null}; it runs at once, so a bad key
     *     throws here and not when the query runs
     * @param constraint the constraint on every owner query, or {@code null} for none
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code options} is {@code null}, or an option key
     *     is {@code null}
     * @throws IllegalArgumentException if an option key is {@code "*"}, a class that is not a model, or
     *     neither a class nor a string
     * @throws ClassMorphViolationException if an option key is an alias or class name that cannot be
     *     resolved, or a class without an alias while the morph map is required
     */
    public final EloquentBuilder<T> withMorph(String relation, Consumer<MorphEagerOptions> options,
            Consumer<EloquentBuilder<?>> constraint) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(options, "options");
        // A class literal does not initialise Relation, and its initialiser registers the bridge.
        Relation.ensureInitialised();
        return with(relation, Access.relations().morphEager(options, constraint));
    }

    private EloquentBuilder<T> morph(String relation, List<?> types, String operator, long count, boolean or,
            BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(operator, "operator");
        Relation.ensureInitialised();
        Access.relations().addMorphExistence(self(), relation, new ArrayList<Object>(types), operator, count, or,
                constraint);
        return self();
    }

    private EloquentBuilder<T> morphedTo(String relation, Object target, boolean not, boolean or) {
        Objects.requireNonNull(relation, "relation");
        Relation.ensureInitialised();
        Access.relations().whereMorphedTo(self(), relation, target, not, or);
        return self();
    }
}
