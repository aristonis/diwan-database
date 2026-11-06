package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.Internal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.Relation;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.Expression;

/**
 * The relation aggregate methods of {@link EloquentBuilder}: {@code withCount}, {@code withMax},
 * {@code withMin}, {@code withSum}, {@code withAvg}, {@code withExists} and
 * {@code withAggregate}, which add one correlated subselect per relation to the select list, and
 * {@code withCasts}, which casts columns of the models this builder reads.
 *
 * <p>Each aggregate subselect is named {@code relation_function_column} in snake case, or by the
 * alias in {@code "posts as total"}. When the query selects no columns yet, {@code table.*} is
 * selected first; a later {@code select} replaces the subselects, a later {@code addSelect} keeps
 * them, as in Laravel. Values keep the driver's types, except {@code withExists}, whose column is
 * cast to {@link Boolean}. {@code count()} and pagination totals ignore the subselects;
 * {@code exists()} and {@code pluck} keep them.
 *
 * <p>Names are checked at the call: an unknown relation or a dotted path throws before any
 * subselect of that call is added. A constraint runs once, at the call, on the aggregate subquery
 * with the rules of {@code whereHas}: joins are allowed, its wheres are grouped, and its columns,
 * orders, limit and offset are cleared; a groupBy, having, distinct or union is refused.
 *
 * @param <T> the model class
 */
abstract class EloquentAggregates<T extends Model> extends EloquentMorphQueries<T> {

    private static final String ANY_COLUMN = "*";
    private static final String COUNT = "count";
    private static final String MAX = "max";
    private static final String MIN = "min";
    private static final String SUM = "sum";
    private static final String AVG = "avg";
    private static final String EXISTS = "exists";

    /**
     * The casts applied to every model read, resolved, unmodifiable; replaced by a merged copy on
     * each {@code withCasts}, never changed in place, so a copy of this builder may share it.
     */
    private Map<String, Cast<?>> castsToMerge = Collections.emptyMap();

    EloquentAggregates(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
    }

    // ---- count and exists

    /**
     * Adds a {@code count(*)} subselect per relation, named {@code relation_count}.
     *
     * @param relations the relation names, each optionally {@code "name as alias"}, none
     *     {@code null}; none changes nothing
     * @return this builder
     * @throws NullPointerException if a name is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withCount(String... relations) {
        return aggregate(WithParser.parseAggregates(relations), ANY_COLUMN, COUNT);
    }

    /**
     * Adds a {@code count(*)} subselect per relation, each constrained by its callback.
     *
     * @param relations the constraints by relation name, each name optionally
     *     {@code "name as alias"}, no key or value {@code null}; an empty map changes nothing
     * @return this builder
     * @throws NullPointerException if the map, a name or a constraint is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     * @throws IllegalArgumentException if a constraint adds a groupBy, having, distinct or union
     */
    public final EloquentBuilder<T> withCount(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        return aggregate(WithParser.parseAggregates(relations), ANY_COLUMN, COUNT);
    }

    /**
     * Adds an {@code exists(...)} column per relation, named {@code relation_exists} and read as a
     * {@link Boolean}.
     *
     * @param relations the relation names, each optionally {@code "name as alias"}, none
     *     {@code null}; none changes nothing
     * @return this builder
     * @throws NullPointerException if a name is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withExists(String... relations) {
        return aggregate(WithParser.parseAggregates(relations), ANY_COLUMN, EXISTS);
    }

    /**
     * Adds an {@code exists(...)} column per relation, each constrained by its callback, read as a
     * {@link Boolean}.
     *
     * @param relations the constraints by relation name, each name optionally
     *     {@code "name as alias"}, no key or value {@code null}; an empty map changes nothing
     * @return this builder
     * @throws NullPointerException if the map, a name or a constraint is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withExists(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        return aggregate(WithParser.parseAggregates(relations), ANY_COLUMN, EXISTS);
    }

    // ---- max, min, sum, avg

    /**
     * Adds a {@code max} subselect of a related column, named {@code relation_max_column}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, qualified with the related table unless it has a dot,
     *     never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withMax(String relation, String column) {
        return single(relation, column, MAX);
    }

    /**
     * Adds a {@code max} subselect of a related column per relation, each constrained by its
     * callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     * @throws IllegalArgumentException if a constraint adds a groupBy, having, distinct or union
     */
    public final EloquentBuilder<T> withMax(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return many(relations, column, MAX);
    }

    /**
     * Adds a {@code max} subselect of a raw expression over the related table, written as given.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the expression, never {@code null}; it is neither quoted nor qualified
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withMax(String relation, Expression column) {
        return single(relation, column, MAX);
    }

    /**
     * Adds a {@code min} subselect of a related column, named {@code relation_min_column}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, qualified with the related table unless it has a dot,
     *     never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withMin(String relation, String column) {
        return single(relation, column, MIN);
    }

    /**
     * Adds a {@code min} subselect of a related column per relation, each constrained by its
     * callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     * @throws IllegalArgumentException if a constraint adds a groupBy, having, distinct or union
     */
    public final EloquentBuilder<T> withMin(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return many(relations, column, MIN);
    }

    /**
     * Adds a {@code min} subselect of a raw expression over the related table, written as given.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the expression, never {@code null}; it is neither quoted nor qualified
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withMin(String relation, Expression column) {
        return single(relation, column, MIN);
    }

    /**
     * Adds a {@code sum} subselect of a related column, named {@code relation_sum_column}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, qualified with the related table unless it has a dot,
     *     never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withSum(String relation, String column) {
        return single(relation, column, SUM);
    }

    /**
     * Adds a {@code sum} subselect of a related column per relation, each constrained by its
     * callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     * @throws IllegalArgumentException if a constraint adds a groupBy, having, distinct or union
     */
    public final EloquentBuilder<T> withSum(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return many(relations, column, SUM);
    }

    /**
     * Adds a {@code sum} subselect of a raw expression over the related table, written as given:
     * {@code withSum("orders", Expression.raw("price * qty"))} is named
     * {@code orders_sum_price_qty}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the expression, never {@code null}; it is neither quoted nor qualified
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withSum(String relation, Expression column) {
        return single(relation, column, SUM);
    }

    /**
     * Adds an {@code avg} subselect of a related column, named {@code relation_avg_column}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, qualified with the related table unless it has a dot,
     *     never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withAvg(String relation, String column) {
        return single(relation, column, AVG);
    }

    /**
     * Adds an {@code avg} subselect of a related column per relation, each constrained by its
     * callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     * @throws IllegalArgumentException if a constraint adds a groupBy, having, distinct or union
     */
    public final EloquentBuilder<T> withAvg(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return many(relations, column, AVG);
    }

    /**
     * Adds an {@code avg} subselect of a raw expression over the related table, written as given.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the expression, never {@code null}; it is neither quoted nor qualified
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withAvg(String relation, Expression column) {
        return single(relation, column, AVG);
    }

    // ---- any function

    /**
     * Adds a subselect applying an aggregate function to a related column, named
     * {@code relation_function_column}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, never {@code null}
     * @param function the aggregate function, {@code count}, {@code max}, {@code min},
     *     {@code sum}, {@code avg} or {@code exists}, in any case; it is lower-cased in the SQL, and
     *     {@code null} selects the column of one related row, as
     *     {@link #withAggregate(String, String)}
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code function} is not one of those
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withAggregate(String relation, String column, String function) {
        return single(relation, column, function);
    }

    /**
     * Adds a subselect applying an aggregate function to a related column per relation, each
     * constrained by its callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @param function the function as {@link #withAggregate(String, String, String)} takes, or
     *     {@code null} for the column of one related row
     * @return this builder
     * @throws NullPointerException if the map, the column, a name or a constraint is {@code null}
     * @throws IllegalArgumentException if {@code function} is not a supported function, or a
     *     constraint adds a groupBy, having, distinct or union
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withAggregate(Map<String, Consumer<EloquentBuilder<?>>> relations,
            String column, String function) {
        return many(relations, column, function);
    }

    /**
     * Adds a subselect applying an aggregate function to a raw expression over the related table,
     * written as given.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the expression, never {@code null}; it is neither quoted nor qualified
     * @param function the function as {@link #withAggregate(String, String, String)} takes, or
     *     {@code null} for the expression over one related row
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code function} is not a supported function
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withAggregate(String relation, Expression column, String function) {
        return single(relation, column, function);
    }

    /**
     * Adds a subselect of a related column from one related row, with {@code limit 1} and no
     * function, named {@code relation_column}. Which row is the database's choice.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the name
     *     is not a relation of the model or is a dotted path
     */
    public final EloquentBuilder<T> withAggregate(String relation, String column) {
        return single(relation, column, null);
    }

    // ---- casts

    /**
     * Casts columns of every model this builder reads, on top of the model's own casts, as
     * {@link Model#mergeCasts(Map)} does for one model. The casts are in place before
     * {@code retrieved()} runs. Models of eager loaded relations are not affected.
     *
     * <p>Calls merge: a later call adds to the earlier ones and wins for a key in both, so the
     * casts {@code withExists} registers survive. The values are resolved now, so a bad cast
     * fails here; the map is not kept.
     *
     * @param casts the casts by column, each a {@link Cast} or a cast string, never {@code null};
     *     an empty map changes nothing
     * @return this builder
     * @throws NullPointerException if {@code casts} or a key in it is {@code null}
     * @throws IllegalArgumentException if a value is {@code null} or neither a cast nor a string
     * @throws org.aristonis.diwan.database.eloquent.exception.InvalidCastException if a cast string
     *     cannot be resolved
     */
    public final EloquentBuilder<T> withCasts(Map<String, ?> casts) {
        Objects.requireNonNull(casts, "casts");
        if (casts.isEmpty()) {
            return self();
        }
        // A bare instance resolves and validates exactly as Model.mergeCasts does, naming the model.
        Map<String, Cast<?>> resolved = model.newInstanceLike().mergeCasts(casts).castOverlay();
        Map<String, Cast<?>> merged = new LinkedHashMap<>(castsToMerge);
        merged.putAll(resolved);
        castsToMerge = Collections.unmodifiableMap(merged);
        return self();
    }

    /**
     * Returns the casts {@code withCasts} registered.
     *
     * @return the casts by column, unmodifiable, empty when none, never {@code null}
     */
    final Map<String, Cast<?>> castsToMerge() {
        return castsToMerge;
    }

    /**
     * Gives a copy of this builder the same casts; the map is never changed in place, so it is
     * shared.
     *
     * @param target the copy, never {@code null}
     */
    final void copyAggregateStateTo(EloquentAggregates<T> target) {
        target.castsToMerge = castsToMerge;
    }

    // ---- helpers

    private EloquentBuilder<T> single(String relation, Object column, String function) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(column, "column");
        return aggregate(WithParser.parseAggregates(relation), column, function);
    }

    private EloquentBuilder<T> many(Map<String, Consumer<EloquentBuilder<?>>> relations, Object column,
            String function) {
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(column, "column");
        return aggregate(WithParser.parseAggregates(relations), column, function);
    }

    private EloquentBuilder<T> aggregate(Map<String, Consumer<EloquentBuilder<?>>> parsed, Object column,
            String function) {
        // A class literal does not initialise Relation, and its initialiser registers the bridge.
        Relation.ensureInitialised();
        Access.relations().addAggregate(self(), parsed, column, function);
        return self();
    }
}
