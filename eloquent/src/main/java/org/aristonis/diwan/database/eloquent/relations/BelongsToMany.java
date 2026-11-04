package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;

/**
 * A many-to-many relation through a pivot table, such as a user's roles through {@code role_user}:
 * Laravel's {@code BelongsToMany}.
 *
 * <p>The relation joins the related table to the pivot table on the related key, and filters the
 * pivot table on the parent's key. Each related row comes back with its pivot as the
 * {@code pivot} relation (or the name set by {@code as}), built from the {@code pivot_*} columns
 * the read selects. A related model linked to two parents is two instances, each with its own
 * pivot.
 *
 * <p>These methods return the relation, so they chain in any order. The settings and conditions are
 * {@code withPivot}, {@code withTimestamps}, {@code as}, {@code using}, {@code withPivotValue},
 * {@code chaperone}, {@code withoutChaperone}, the {@code wherePivot} family, {@code orWherePivot},
 * {@code wherePivotIn}, {@code wherePivotBetween}, {@code wherePivotNull}, {@code orderByPivot} and
 * {@code orderByPivotDesc}. The readers are {@code find}, {@code findMany}, {@code findOrFail},
 * {@code findOr}, {@code first}, {@code firstOrFail}, {@code firstOr}, {@code firstWhere}, {@code sole},
 * {@code chunk}, {@code chunkById}, {@code each}, {@code lazy}, {@code lazyById}, {@code cursor},
 * {@code paginate}, {@code simplePaginate} and {@code cursorPaginate}, each in its builder form:
 * they run on a copy of the relation's query, so the relation itself is unchanged by a read.
 *
 * <p>{@link #get()} and {@link #getResults()} run the lazy query. {@link #getQuery()} returns the
 * relation's own query with the join and the parent constraint but without the pivot aliases and
 * without the pivot hydration: a read through it returns plain related models.
 *
 * <p>Subclassing is possible only from this package. The constructor and the hooks that choose
 * the select step and the pivot instances are package-private, so a subclass in another package
 * cannot be built or override them.
 *
 * <p>Not safe to share across threads, and single use: build a relation for each query, through
 * the model's typed method or {@code relation(name)}. Each read copies the query and creates its
 * own hydrator, so a relation read twice sends the same SQL both times.
 *
 * @param <R> the related model class
 */
public class BelongsToMany<R extends Model> extends PivotModelWrites<R> {

    /** A blank pivot of the {@code using} class, cloned for each row; {@code null} until first needed. */
    private Pivot prototype;

    /** The hydrator shared by every chunk of one eager load, read again by {@link #match}. */
    private PivotHydrator eagerHydrator;

    /**
     * Creates the relation. Models build it through their {@code belongsToMany} methods.
     *
     * @param query an unconstrained query on the related model, never {@code null}
     * @param parent the model the relation belongs to, never {@code null}
     * @param table the pivot table, never {@code null}
     * @param foreignPivotKey the pivot column that holds the parent's key, never {@code null}
     * @param relatedPivotKey the pivot column that holds the related key, never {@code null}
     * @param parentKey the parent's column the pivot points at, never {@code null}
     * @param relatedKey the related model's column the pivot points at, never {@code null}
     * @param relation the relation's name, or {@code null} when it has none
     * @throws NullPointerException if a required argument is {@code null}
     */
    BelongsToMany(EloquentBuilder<R> query, Model parent, String table, String foreignPivotKey,
            String relatedPivotKey, String parentKey, String relatedKey, String relation) {
        super(query, parent, table, foreignPivotKey, relatedPivotKey, parentKey, relatedKey, relation);
    }

    @Override
    final BelongsToMany<R> self() {
        return this;
    }

    /**
     * Returns the related models of the parent. A parent without a key value runs no query and
     * gets an empty collection.
     *
     * @return the related models in query order, never {@code null}
     */
    @Override
    public ModelCollection<R> getResults() {
        if (getParent().getAttribute(getParentKeyName()) == null) {
            return emptyCollection();
        }
        return get();
    }

    /**
     * Runs the lazy query with the given columns. The columns apply only when the relation's query
     * selects nothing; a select the relation already has is kept and the columns are ignored.
     *
     * @param columns the related columns to select, or none for every related column; no
     *     {@code null} element
     * @return the related models in query order, never {@code null}
     * @throws NullPointerException if {@code columns} is {@code null}
     */
    public final ModelCollection<R> get(String... columns) {
        return prepared(Select.REPLACE, columns).get();
    }

    /**
     * Returns the query {@code get()} runs: the lazy query with the pivot aliases and the row hook
     * that hydrates each pivot, on a copy of the relation's query.
     *
     * @return the query, never {@code null}
     */
    @Override
    EloquentBuilder<R> readQuery() {
        return prepared(Select.REPLACE);
    }

    /**
     * Returns a copy of the constrained query with the select step applied and a row hook that
     * hydrates each pivot. The relation's own query is never changed.
     *
     * @param mode whether the read replaces the select or appends to it, never {@code null}
     * @param columns the related columns passed to the read, never {@code null}
     * @return the copy, never {@code null}
     */
    @Override
    EloquentBuilder<R> prepared(Select mode, String... columns) {
        EloquentBuilder<R> copy = Access.eloquent().copy(constrainedQuery());
        selectFor(copy, mode, columns);
        Access.eloquent().useRowHook(copy, new PivotHydrator(this, getParent())::hydrate);
        return copy;
    }

    /**
     * Adds the related columns and the pivot aliases to a read. The aliases are always added; the
     * related columns depend on the mode.
     *
     * <p>{@code APPEND} adds the related table's columns to whatever is selected, as chunked and
     * paginated reads do. {@code REPLACE} sets them only when nothing is selected, or only {@code *}
     * is, using the given columns or every related column when none are given.
     *
     * @param builder the query to change, never {@code null}
     * @param mode the select mode, never {@code null}
     * @param columns the related columns passed to the read, never {@code null}
     * @return the same builder
     */
    EloquentBuilder<R> selectFor(EloquentBuilder<R> builder, Select mode, String... columns) {
        if (mode == Select.APPEND) {
            builder.addSelect(relatedAll());
        } else if (isUnselected(builder)) {
            // A bare select() leaves "*", which would pull the pivot's own columns too; start over.
            builder.select(selectsEverything(columns) ? new Object[] {relatedAll()} : (Object[]) columns);
        }
        builder.addSelect((Object[]) aliasedPivotColumns());
        return builder;
    }

    /**
     * Creates the pivot of one row: the attributes moved out of the row, in the relation's pivot
     * class when one was given and in the plain pivot class otherwise. The values recorded by
     * {@code withPivotValue} are merged under the attributes, so an attribute given here wins.
     *
     * @param attributes the pivot columns with their prefix removed, never {@code null}
     * @param exists whether the row is in the database
     * @return the new pivot, never {@code null}
     */
    @Override
    public Pivot newPivot(Map<String, ?> attributes, boolean exists) {
        Map<String, Object> merged = new LinkedHashMap<>(pivotValues());
        merged.putAll(attributes);
        Class<? extends Pivot> using = usingClass();
        if (using == null) {
            return Pivot.fromAttributes(getParent(), merged, getTable(), exists);
        }
        return Pivot.fromRawAttributes(freshUsing(using), getParent(), merged, getTable(), exists);
    }

    /**
     * Adds the lazy constraints to the relation's own query: the join and the parent's key.
     */
    @Override
    void addConstraints() {
        performJoin(baseQuery());
        baseQuery().where(getQualifiedForeignPivotKeyName(), "=", getParent().getAttribute(getParentKeyName()));
    }

    /**
     * Sets every parent's relation to an empty collection before the match.
     *
     * @param parents the parent models, never {@code null}
     * @param name the relation name to set
     */
    @Override
    void initRelation(List<? extends Model> parents, String name) {
        for (Model parent : parents) {
            parent.setRelation(name, emptyCollection());
        }
    }

    /**
     * Returns the parents' keys for the eager query, normalised, without nulls and without
     * duplicates.
     *
     * @param parents the parent models, never {@code null}
     * @return the keys, never {@code null}
     */
    @Override
    List<Object> eagerKeys(List<? extends Model> parents) {
        return EagerKeys.<Model>collect(parents, (Model parent) -> parent.getAttribute(getParentKeyName()));
    }

    /**
     * Adds the join and the key filter of one chunk to an eager query.
     *
     * @param eagerQuery a fresh copy of the raw query, never {@code null}
     * @param chunk the chunk's keys, never {@code null}; empty for the binding probe
     */
    @Override
    void prepareEagerQuery(EloquentBuilder<R> eagerQuery, List<Object> chunk) {
        performJoin(eagerQuery);
        whereEagerKeys(eagerQuery, getQualifiedForeignPivotKeyName(), chunk);
    }

    /**
     * Returns whether the parent key is an integer primary key, so the eager keys are inlined.
     *
     * @return {@code true} when the parent key is the integer primary key
     */
    @Override
    boolean inlinesEagerKeys() {
        return isIntegerKey(getParent(), getParentKeyName());
    }

    /**
     * Returns the pivot's qualified foreign key, which the eager query filters on.
     *
     * @return the qualified foreign pivot key, never {@code null}
     */
    @Override
    String eagerMatchColumn() {
        return getQualifiedForeignPivotKeyName();
    }

    /**
     * Completes an eager chunk after its constraint ran: the related columns when none are
     * selected, the pivot aliases, and the row hook. The hook is the same hydrator for every chunk
     * of the load, so {@link #match} reads the pivots of all of them.
     *
     * @param eager the eager chunk query, never {@code null}
     */
    @Override
    void completeEagerQuery(EloquentBuilder<R> eager) {
        selectFor(eager, Select.REPLACE);
        if (eagerHydrator == null) {
            eagerHydrator = new PivotHydrator(this, getParent());
        }
        Access.eloquent().useRowHook(eager, eagerHydrator::hydrate);
    }

    /**
     * Sets each parent's relation to the related models whose pivot key equals the parent's key,
     * and points each of those pivots at the parent.
     *
     * <p>A related model linked to two parents is two instances, one per pivot row. A pivot row
     * with a {@code null} key matches no parent. When no chunk ran there is nothing to match.
     *
     * @param parents the parent models, never {@code null}
     * @param results the related models of every chunk, in query order, never {@code null}
     * @param name the relation name to set
     */
    @Override
    void match(List<? extends Model> parents, List<R> results, String name) {
        if (eagerHydrator == null) {
            return;
        }
        Map<Object, List<R>> dictionary = pivotDictionary(results);
        for (Model parent : parents) {
            Object key = EagerKeys.normalise(parent.getAttribute(getParentKeyName()));
            List<R> matches = key == null ? null : dictionary.get(key);
            if (matches == null) {
                continue;
            }
            parent.setRelation(name, collectionOf(matches));
            for (R related : matches) {
                // The pivots were built before the real parent was known; relink them to this one.
                eagerHydrator.relinkParent(eagerHydrator.pivotOf(related), parent);
            }
        }
    }

    /**
     * Returns the join and the parent constraint of an existence subquery: the relation's own
     * query with the related table aliased when it joins itself. The pivot conditions follow the
     * parent constraint in one nested group, so an {@code or} among them cannot widen it.
     *
     * @param parentQuery the outer query, never {@code null}
     * @param aliases the outermost query's alias counter, never {@code null}
     * @return the subquery, the relation's own query, never {@code null}
     */
    @Override
    EloquentBuilder<R> existenceQuery(EloquentBuilder<?> parentQuery, AliasCounter aliases) {
        Objects.requireNonNull(parentQuery, "parentQuery");
        Objects.requireNonNull(aliases, "aliases");
        String parentColumn = parentKeyAgainst(parentQuery, getParentKeyName());
        if (RelationExistence.isSelfRelation(baseQuery(), parentQuery)) {
            // Aliased before the join, so the join names the alias; the pivot table is never aliased.
            aliasRelatedTable(aliases);
        }
        performJoin(baseQuery());
        baseQuery().whereColumn(parentColumn, "=", getQualifiedForeignPivotKeyName());
        return existenceWheres();
    }

    @Override
    void performJoin(EloquentBuilder<R> target) {
        String relatedColumn = getRelated().qualifyColumn(getRelatedKeyName());
        target.join(getTable(), relatedColumn, "=", getQualifiedRelatedPivotKeyName());
    }

    private Pivot freshUsing(Class<? extends Pivot> using) {
        if (prototype == null) {
            prototype = Access.eloquent().newRegistered(using, getParent());
        }
        return (Pivot) Access.eloquent().newInstanceLike(prototype);
    }

    private Map<Object, List<R>> pivotDictionary(List<R> results) {
        Map<Object, List<R>> dictionary = new HashMap<>();
        for (R related : results) {
            Pivot pivot = eagerHydrator.pivotOf(related);
            Object key = pivot == null ? null : EagerKeys.normalise(pivot.getAttribute(getForeignPivotKeyName()));
            if (key != null) {
                dictionary.computeIfAbsent(key, ignored -> new ArrayList<>()).add(related);
            }
        }
        return dictionary;
    }

    private static boolean isUnselected(EloquentBuilder<?> builder) {
        List<Object> selected = builder.toBase().getColumns();
        return selected.isEmpty() || (selected.size() == 1 && "*".equals(selected.get(0)));
    }

    private static boolean selectsEverything(String[] columns) {
        return columns.length == 0 || (columns.length == 1 && "*".equals(columns[0]));
    }
}
