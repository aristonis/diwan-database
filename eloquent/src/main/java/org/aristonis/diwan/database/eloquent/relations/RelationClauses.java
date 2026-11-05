package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.Scope;
import org.aristonis.diwan.database.query.Builder;

/**
 * The clause forwarders of {@link Relation}: the common {@code where}, {@code orderBy},
 * {@code select} and {@code limit} calls, each applied to the relation's lazy query.
 *
 * <p>A forwarder called before the relation is constrained records its clause instead of applying
 * it. The lazy path adds the relation's own constraints first and then replays the recorded
 * clauses, so the SQL keeps Laravel's order: {@code where posts.user_id = ? and posts.user_id is
 * not null and published = ?}. The eager path replays them onto the unconstrained query and the
 * existence path replays only the {@code where} clauses, as Laravel does, so a clause chained inside a relation factory never drags the lazy constraints of a blank
 * sample model into those queries. Once constrained, a forwarder applies its clause directly. Each returns the relation itself, typed as its own class, so
 * {@code post.comments().where("approved", true).create(values)} compiles. Any other clause is
 * reached through {@link Relation#getQuery()}.
 *
 * @param <R> the related model class
 * @param <S> the relation's own class
 */
abstract class RelationClauses<R extends Model, S extends RelationClauses<R, S>> {

    RelationClauses() {
    }

    /**
     * Returns this relation typed as its own class.
     *
     * @return this relation
     */
    abstract S self();

    /**
     * Returns the relation query with the lazy constraints applied, once.
     *
     * @return the constrained query
     */
    abstract EloquentBuilder<R> constrainedQuery();

    /**
     * Applies a clause to the query now when the relation is constrained, else records it for
     * replay.
     *
     * @param clause the clause, never {@code null}
     */
    abstract void applyClause(Consumer<EloquentBuilder<R>> clause);

    /**
     * Applies a {@code where} clause like {@link #applyClause(Consumer)}. Existence queries replay
     * only these, as Laravel merges only the wheres of a relation into {@code has()}.
     *
     * @param clause the clause, never {@code null}
     */
    abstract void applyWhere(Consumer<EloquentBuilder<R>> clause);

    /**
     * Adds {@code column = value} to the lazy query.
     *
     * @param column the column, never {@code null}
     * @param value the value; {@code null} compiles to {@code is null}
     * @return this relation
     */
    public final S where(String column, Object value) {
        applyWhere(query -> query.where(column, value));
        return self();
    }

    /**
     * Adds {@code column operator value} to the lazy query.
     *
     * @param column the column, never {@code null}
     * @param operator the comparison operator, such as {@code >=}, never {@code null}
     * @param value the value
     * @return this relation
     * @throws IllegalArgumentException if the query builder refuses the operator
     */
    public final S where(String column, String operator, Object value) {
        applyWhere(query -> query.where(column, operator, value));
        return self();
    }

    /**
     * Adds a parenthesised group of conditions to the lazy query.
     *
     * @param nested adds the grouped conditions, never {@code null}
     * @return this relation
     */
    public final S where(Consumer<Builder> nested) {
        applyWhere(query -> query.where(nested));
        return self();
    }

    /**
     * Adds {@code or column = value} to the lazy query.
     *
     * @apiNote The relation's own constraints are not grouped, as in Laravel, so an {@code or}
     *     here widens the result past the parent's rows; group it with {@link #where(Consumer)}.
     * @param column the column, never {@code null}
     * @param value the value
     * @return this relation
     */
    public final S orWhere(String column, Object value) {
        applyWhere(query -> query.orWhere(column, value));
        return self();
    }

    /**
     * Adds {@code or column operator value} to the lazy query.
     *
     * @apiNote As for {@link #orWhere(String, Object)}, group the {@code or} to keep it inside the
     *     relation.
     * @param column the column, never {@code null}
     * @param operator the comparison operator, never {@code null}
     * @param value the value
     * @return this relation
     * @throws IllegalArgumentException if the query builder refuses the operator
     */
    public final S orWhere(String column, String operator, Object value) {
        applyWhere(query -> query.orWhere(column, operator, value));
        return self();
    }

    /**
     * Adds {@code column in (values)} to the lazy query.
     *
     * @param column the column, never {@code null}
     * @param values the values, never {@code null}; empty matches no row
     * @return this relation
     */
    public final S whereIn(String column, Collection<?> values) {
        applyWhere(query -> query.whereIn(column, values));
        return self();
    }

    /**
     * Adds {@code column not in (values)} to the lazy query.
     *
     * @param column the column, never {@code null}
     * @param values the values, never {@code null}; empty matches every row
     * @return this relation
     */
    public final S whereNotIn(String column, Collection<?> values) {
        applyWhere(query -> query.whereNotIn(column, values));
        return self();
    }

    /**
     * Adds {@code column is null} for each column to the lazy query.
     *
     * @param columns the columns, never {@code null}
     * @return this relation
     */
    public final S whereNull(String... columns) {
        String[] copy = columns.clone();
        applyWhere(query -> query.whereNull(copy));
        return self();
    }

    /**
     * Adds {@code column is not null} for each column to the lazy query.
     *
     * @param columns the columns, never {@code null}
     * @return this relation
     */
    public final S whereNotNull(String... columns) {
        String[] copy = columns.clone();
        applyWhere(query -> query.whereNotNull(copy));
        return self();
    }

    /**
     * Adds {@code first operator second}, comparing two columns, to the lazy query.
     *
     * @param first the first column, never {@code null}
     * @param operator the comparison operator, never {@code null}
     * @param second the second column, never {@code null}
     * @return this relation
     * @throws IllegalArgumentException if the query builder refuses the operator
     */
    public final S whereColumn(String first, String operator, String second) {
        applyWhere(query -> query.whereColumn(first, operator, second));
        return self();
    }

    /**
     * Orders the lazy query by a column, ascending.
     *
     * @param column the column, never {@code null}
     * @return this relation
     */
    public final S orderBy(String column) {
        applyClause(query -> query.orderBy(column));
        return self();
    }

    /**
     * Orders the lazy query by a column.
     *
     * @param column the column, never {@code null}
     * @param direction {@code asc} or {@code desc}, never {@code null}
     * @return this relation
     * @throws IllegalArgumentException if the direction is neither
     */
    public final S orderBy(String column, String direction) {
        applyClause(query -> query.orderBy(column, direction));
        return self();
    }

    /**
     * Orders the lazy query by a column, descending.
     *
     * @param column the column, never {@code null}
     * @return this relation
     */
    public final S orderByDesc(String column) {
        applyClause(query -> query.orderByDesc(column));
        return self();
    }

    /**
     * Orders the lazy query by the related model's creation column, {@code created_at} unless
     * the model names another, newest first.
     *
     * @return this relation
     */
    public final S latest() {
        applyClause(query -> query.latest());
        return self();
    }

    /**
     * Orders the lazy query by a column, newest first.
     *
     * @param column the column, never {@code null}
     * @return this relation
     */
    public final S latest(String column) {
        applyClause(query -> query.latest(column));
        return self();
    }

    /**
     * Orders the lazy query by the related model's creation column, {@code created_at} unless
     * the model names another, oldest first.
     *
     * @return this relation
     */
    public final S oldest() {
        applyClause(query -> query.oldest());
        return self();
    }

    /**
     * Orders the lazy query by a column, oldest first.
     *
     * @param column the column, never {@code null}
     * @return this relation
     */
    public final S oldest(String column) {
        applyClause(query -> query.oldest(column));
        return self();
    }

    /**
     * Sets the columns the lazy query selects.
     *
     * @param columns the columns, each a {@code String} or an expression, never {@code null}
     * @return this relation
     */
    public final S select(Object... columns) {
        Object[] copy = columns.clone();
        applyClause(query -> query.select(copy));
        return self();
    }

    /**
     * Limits the number of rows the lazy query returns.
     *
     * @param value the maximum row count, zero or more
     * @return this relation
     * @throws IllegalArgumentException if the query builder refuses the value
     */
    public final S limit(int value) {
        applyClause(query -> query.limit(value));
        return self();
    }

    /**
     * Takes the given number of rows from the lazy query, with the same effect as
     * {@code limit(int)}.
     *
     * @param value the maximum row count, zero or more
     * @return this relation
     * @throws IllegalArgumentException if the query builder refuses the value
     */
    public final S take(int value) {
        return limit(value);
    }

    /**
     * Removes one global scope of the related model from this relation's queries.
     *
     * @param name the scope key, never {@code null}
     * @return this relation
     */
    public final S withoutGlobalScope(String name) {
        applyWhere(query -> query.withoutGlobalScope(name));
        return self();
    }

    /**
     * Removes one global scope of the related model, found by its class.
     *
     * @param type the scope class, never {@code null}
     * @return this relation
     */
    public final S withoutGlobalScope(Class<? extends Scope> type) {
        applyWhere(query -> query.withoutGlobalScope(type));
        return self();
    }

    /**
     * Removes one global scope of the related model, found by its instance.
     *
     * @param scope the scope, never {@code null}
     * @return this relation
     */
    public final S withoutGlobalScope(Scope scope) {
        applyWhere(query -> query.withoutGlobalScope(scope));
        return self();
    }

    /**
     * Removes every global scope of the related model from this relation's queries.
     *
     * @return this relation
     */
    public final S withoutGlobalScopes() {
        applyWhere(query -> query.withoutGlobalScopes());
        return self();
    }

    /**
     * Removes the named global scopes of the related model.
     *
     * @param names the scope keys, never {@code null}
     * @return this relation
     */
    public final S withoutGlobalScopes(Collection<String> names) {
        applyWhere(query -> query.withoutGlobalScopes(names));
        return self();
    }

    /**
     * Removes every global scope of the related model except the named ones.
     *
     * @param names the scope keys to keep, never {@code null}
     * @return this relation
     */
    public final S withoutGlobalScopesExcept(Collection<String> names) {
        applyWhere(query -> query.withoutGlobalScopesExcept(names));
        return self();
    }

    /**
     * Applies a local scope to this relation's queries.
     *
     * @param scope the scope, never {@code null}
     * @return this relation
     */
    public final S scope(Consumer<EloquentBuilder<R>> scope) {
        applyWhere(query -> query.scope(scope));
        return self();
    }

    /**
     * Applies several local scopes, in order.
     *
     * @param scopes the scopes, never {@code null}
     * @return this relation
     */
    public final S scopes(List<Consumer<EloquentBuilder<R>>> scopes) {
        applyWhere(query -> query.scopes(scopes));
        return self();
    }

    /**
     * Includes soft-deleted related rows.
     *
     * @return this relation
     */
    public final S withTrashed() {
        applyWhere(query -> query.withTrashed());
        return self();
    }

    /**
     * Includes or excludes soft-deleted related rows.
     *
     * @param withTrashed {@code true} to include them
     * @return this relation
     */
    public final S withTrashed(boolean withTrashed) {
        applyWhere(query -> query.withTrashed(withTrashed));
        return self();
    }

    /**
     * Returns only soft-deleted related rows.
     *
     * @return this relation
     */
    public final S onlyTrashed() {
        applyWhere(query -> query.onlyTrashed());
        return self();
    }

    /**
     * Excludes soft-deleted related rows.
     *
     * @return this relation
     */
    public final S withoutTrashed() {
        applyWhere(query -> query.withoutTrashed());
        return self();
    }
}
