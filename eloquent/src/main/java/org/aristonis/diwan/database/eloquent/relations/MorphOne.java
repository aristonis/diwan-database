package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * A polymorphic one-to-one relation, such as a post's image: Laravel's {@code MorphOne}.
 *
 * <p>Declare it on the parent with {@code morphOne(Image.class, "imageable")}. It reads the first
 * matching row; when several match, the first in result order wins, lazily and eagerly. Single
 * use, not safe to share across threads (see {@link Relation}).
 *
 * @param <R> the related model class
 */
public final class MorphOne<R extends Model> extends MorphOneOrMany<R, MorphOne<R>> {

    private DefaultModel<R> defaultModel = DefaultModel.none();

    /**
     * Creates the relation. Models normally build it through {@code morphOne(...)}.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param parent the model the relation belongs to, never {@code null}
     * @param qualifiedType the related table's type column, bare or qualified, never {@code null}
     * @param qualifiedId the related table's id column, bare or qualified, never {@code null}
     * @param localKey the parent's column the id column holds, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public MorphOne(EloquentBuilder<R> query, Model parent, String qualifiedType, String qualifiedId,
            String localKey) {
        super(query, parent, qualifiedType, qualifiedId, localKey);
    }

    /**
     * Returns the related model of the parent, or the default when there is none. A parent
     * without a local key value runs no query.
     *
     * @return the related model, the default, or {@code null}
     */
    @Override
    public R getResults() {
        if (getParentKey() == null) {
            return defaultFor(getParent());
        }
        return first().orElseGet(() -> defaultFor(getParent()));
    }

    /**
     * Runs the lazy query and returns the first related model, without the default. With
     * {@link #chaperone()} on, the model has the parent set under the inverse name.
     *
     * @return the first related model, or empty
     */
    public Optional<R> first() {
        Optional<R> found = getQuery().first();
        found.ifPresent(model -> linkInverse(model, getParent()));
        return found;
    }

    /**
     * Makes the relation return a bare related model, with the id and type set to the parent's,
     * instead of {@code null} when no row matches. The default is never saved.
     *
     * @return this relation
     */
    public MorphOne<R> withDefault() {
        defaultModel = DefaultModel.bare();
        return this;
    }

    /**
     * Makes the relation return a related model filled with the attributes, ignoring the
     * fillable list, with the id and type set, instead of {@code null}. The default is never
     * saved.
     *
     * @param attributes the attributes, never {@code null}, copied
     * @return this relation
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public MorphOne<R> withDefault(Map<String, ?> attributes) {
        defaultModel = DefaultModel.filled(attributes);
        return this;
    }

    /**
     * Makes the relation return a related model, with the id and type set and then passed to the
     * callback with the parent, instead of {@code null}. The default is never saved.
     *
     * @param callback receives the new default and the parent, never {@code null}
     * @return this relation
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public MorphOne<R> withDefault(BiConsumer<? super R, Model> callback) {
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
    public MorphOne<R> latestOfMany() {
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
    public MorphOne<R> latestOfMany(String... columns) {
        useOneOfMany(OneOfMany.uniform(OneOfMany.MAX, columns), null);
        return this;
    }

    /**
     * Turns the relation into the oldest related row of each parent, by the primary key.
     *
     * @return this relation
     * @throws IllegalStateException if the relation already built its query
     */
    public MorphOne<R> oldestOfMany() {
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
    public MorphOne<R> oldestOfMany(String... columns) {
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
    public MorphOne<R> ofMany(String column, String aggregate) {
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
    public MorphOne<R> ofMany(String column, String aggregate, Consumer<EloquentBuilder<R>> constraint) {
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
    public MorphOne<R> ofMany(Map<String, String> columns) {
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
    public MorphOne<R> ofMany(Map<String, String> columns, Consumer<EloquentBuilder<R>> constraint) {
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
    public MorphOne<R> ofManyAs(String alias) {
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
    MorphOne<R> self() {
        return this;
    }

    @Override
    void initRelation(List<? extends Model> parents, String name) {
        OneMatcher.initRelation(parents, name, this::defaultFor);
    }

    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        OneMatcher.match(this, parents, results, name);
    }

    private R defaultFor(Model parent) {
        return defaultModel.createFor(parent, this::newRelatedFor);
    }

    private R newRelatedFor(Model parent) {
        R instance = getRelated().newInstance();
        instance.setAttribute(getForeignKeyName(), parent.getAttribute(getLocalKeyName()));
        instance.setAttribute(getMorphTypeName(), parent.getMorphClass());
        // Linked before a withDefault callback runs, as Laravel's newRelatedInstanceFor.
        linkInverse(instance, parent);
        return instance;
    }
}
