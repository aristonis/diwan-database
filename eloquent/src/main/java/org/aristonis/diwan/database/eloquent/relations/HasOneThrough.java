package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * A one-to-one relation that reaches the related row through an intermediate model, such as a
 * mechanic's owner through the car: Laravel's {@code HasOneThrough}.
 *
 * <p>Declare it on the far parent with {@code hasOneThrough(Owner.class, Car.class)}. The
 * relation joins the through table and reads the first matching row. When several rows match,
 * the first in result order wins, lazily and eagerly. Single use: one instance serves one
 * lazy read or one eager load, and it is not safe to share across threads (see
 * {@link Relation}).
 *
 * <p>A far parent without a local key value runs no query and yields the default, or
 * {@code null}.
 *
 * @param <R> the related model class
 */
public final class HasOneThrough<R extends Model> extends HasOneOrManyThrough<R, HasOneThrough<R>> {

    private DefaultModel<R> defaultModel = DefaultModel.none();

    /**
     * Creates the relation. Models normally build it through {@code hasOneThrough(...)}.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param farParent the model the relation belongs to, never {@code null}
     * @param throughParent a new instance of the through model, never {@code null}
     * @param firstKey the through table's column pointing at the far parent, bare or qualified,
     *     never {@code null}
     * @param secondKey the related table's column pointing at the through model, bare or
     *     qualified, never {@code null}
     * @param localKey the far parent's column the first key holds, never {@code null}
     * @param secondLocalKey the through model's column the second key holds, never
     *     {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public HasOneThrough(
            EloquentBuilder<R> query,
            Model farParent,
            Model throughParent,
            String firstKey,
            String secondKey,
            String localKey,
            String secondLocalKey) {
        super(query, farParent, throughParent, firstKey, secondKey, localKey, secondLocalKey);
    }

    /**
     * Returns the related model of the far parent, or the default when there is none. A far
     * parent without a local key value runs no query.
     *
     * @return the related model, the default, or {@code null}
     */
    @Override
    public R getResults() {
        if (getParent().getAttribute(getLocalKeyName()) == null) {
            return defaultFor(getParent());
        }
        return first().orElseGet(() -> defaultFor(getParent()));
    }

    /**
     * Makes the relation return a bare related model instead of {@code null} when no row
     * matches. The default has no key set and is never saved.
     *
     * @return this relation
     */
    public HasOneThrough<R> withDefault() {
        defaultModel = DefaultModel.bare();
        return this;
    }

    /**
     * Makes the relation return a related model filled with the attributes, ignoring the
     * fillable list, instead of {@code null}. The default has no key set and is never saved.
     *
     * @param attributes the attributes, never {@code null}, copied
     * @return this relation
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public HasOneThrough<R> withDefault(Map<String, ?> attributes) {
        defaultModel = DefaultModel.filled(attributes);
        return this;
    }

    /**
     * Makes the relation return a new related model, passed to the callback with the far
     * parent, instead of {@code null}. The default has no key set and is never saved.
     *
     * @param callback receives the new default and the far parent, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public HasOneThrough<R> withDefault(BiConsumer<? super R, Model> callback) {
        defaultModel = DefaultModel.configured(callback);
        return this;
    }

    // ---- one of many

    /**
     * Turns the relation into the latest related row of each parent, by the primary key. The
     * subqueries are added when the query is built, so clauses and scopes added later are seen.
     *
     * @return this relation
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> latestOfMany() {
        useOneOfMany(OneOfMany.uniform(OneOfMany.MAX, getRelated().getKeyName()), null);
        return this;
    }

    /**
     * Turns the relation into the latest related row of each parent, by the columns in order.
     * The primary key breaks ties: the highest wins.
     *
     * @param columns the columns to order by, never {@code null} or empty, plain column names
     * @return this relation
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if {@code columns} is empty or a column is not a plain name
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> latestOfMany(String... columns) {
        useOneOfMany(OneOfMany.uniform(OneOfMany.MAX, columns), null);
        return this;
    }

    /**
     * Turns the relation into the oldest related row of each parent, by the primary key.
     *
     * @return this relation
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> oldestOfMany() {
        useOneOfMany(OneOfMany.uniform(OneOfMany.MIN, getRelated().getKeyName()), null);
        return this;
    }

    /**
     * Turns the relation into the oldest related row of each parent, by the columns in order.
     * The primary key breaks ties: the lowest wins.
     *
     * @param columns the columns to order by, never {@code null} or empty, plain column names
     * @return this relation
     * @throws NullPointerException if {@code columns} or a column is {@code null}
     * @throws IllegalArgumentException if {@code columns} is empty or a column is not a plain name
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> oldestOfMany(String... columns) {
        useOneOfMany(OneOfMany.uniform(OneOfMany.MIN, columns), null);
        return this;
    }

    /**
     * Turns the relation into the related row of each parent with the smallest or largest value of
     * a column. The primary key breaks ties with the same aggregate.
     *
     * @param column the column to compare, a plain column name, never {@code null}
     * @param aggregate {@code min} or {@code max}, in any case, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the column is not a plain name or the aggregate is
     *     neither {@code min} nor {@code max}
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> ofMany(String column, String aggregate) {
        return ofMany(column, aggregate, null);
    }

    /**
     * Turns the relation into the related row of each parent with the smallest or largest value of
     * a column, with a constraint that every subquery receives.
     *
     * @param column the column to compare, a plain column name, never {@code null}
     * @param aggregate {@code min} or {@code max}, in any case, never {@code null}
     * @param constraint adds clauses to each subquery, such as a date limit, or {@code null} for none
     * @return this relation
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the column is not a plain name or the aggregate is
     *     neither {@code min} nor {@code max}
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> ofMany(String column, String aggregate, Consumer<EloquentBuilder<R>> constraint) {
        useOneOfMany(Collections.singletonMap(column, aggregate), constraint);
        return this;
    }

    /**
     * Turns the relation into the related row of each parent picked by several columns. Each
     * column is compared in the map's iteration order, which is the chain order, so pass an
     * ordered map such as a {@code LinkedHashMap}; a {@code HashMap} or {@code Map.of} gives an
     * undefined order. The primary
     * key is added last with the first entry's aggregate.
     *
     * @param columns the columns to their aggregate, {@code min} or {@code max}, never {@code null}
     *     or empty
     * @return this relation
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException if {@code columns} is empty, a column is not a plain name or
     *     an aggregate is neither {@code min} nor {@code max}
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> ofMany(Map<String, String> columns) {
        return ofMany(columns, null);
    }

    /**
     * Turns the relation into the related row of each parent picked by several columns, with a
     * constraint that every subquery receives.
     *
     * @param columns the columns to their aggregate, in iteration order, never {@code null} or empty
     * @param constraint adds clauses to each subquery, or {@code null} for none
     * @return this relation
     * @throws NullPointerException if {@code columns} is {@code null}
     * @throws IllegalArgumentException if {@code columns} is empty, a column is not a plain name or
     *     an aggregate is neither {@code min} nor {@code max}
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> ofMany(Map<String, String> columns, Consumer<EloquentBuilder<R>> constraint) {
        useOneOfMany(columns, constraint);
        return this;
    }

    /**
     * Names the joined subquery. The default is the relation's name, or the related table with
     * {@code _of_many} when the name is missing or equals a table of the query. Works before or
     * after the {@code ofMany} call.
     *
     * @param alias the alias, never {@code null} or blank
     * @return this relation
     * @throws NullPointerException if {@code alias} is {@code null}
     * @throws IllegalArgumentException if {@code alias} is blank
     * @throws IllegalStateException if the relation already built its query
     */
    public HasOneThrough<R> ofManyAs(String alias) {
        useOneOfManyAlias(alias);
        return this;
    }

    /**
     * Returns whether the relation was turned into a one-of-many relation.
     *
     * @return {@code true} after an {@code ofMany}, {@code latestOfMany} or {@code oldestOfMany} call
     */
    public boolean isOneOfMany() {
        return oneOfMany() != null;
    }

    @Override
    HasOneThrough<R> self() {
        return this;
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        clearThroughKeys();
        for (Model parent : parents) {
            parent.setRelation(name, defaultFor(parent));
        }
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        try {
            Map<Object, List<R>> dictionary = dictionary(results);
            for (Model parent : parents) {
                List<R> matches = matchesOf(dictionary, parent);
                if (matches != null) {
                    parent.setRelation(name, matches.get(0));
                }
            }
        } finally {
            clearThroughKeys();
        }
    }

    private R defaultFor(Model parent) {
        return defaultModel.createFor(parent, ignored -> getRelated().newInstance());
    }
}
