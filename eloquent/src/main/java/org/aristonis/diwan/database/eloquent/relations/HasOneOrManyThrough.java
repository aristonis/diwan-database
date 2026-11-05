package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;

/**
 * A relation that reaches the related rows through an intermediate table: the far parent owns
 * through rows, and each through row owns related rows. Laravel's {@code HasOneOrManyThrough},
 * the common part of {@link HasManyThrough} and {@link HasOneThrough}.
 *
 * <p>Six keys describe the path. On the through table, the first key points at the far parent's
 * local key. On the related table, the second key points at the through table's second local key.
 * The related query joins the through table, so a column used in a constraint or an order that
 * exists on both tables must be qualified with its table.
 *
 * <p>Every row read carries the first key under the hidden alias {@code laravel_through_key}.
 * The alias is removed from the model as it is hydrated; eager loading keeps it aside, per
 * relation instance, to match each row to its parent. The relation therefore serves one read or
 * one eager load: it is single use and not safe to share across threads (see {@link Relation}).
 * The related and the through table must differ; the relation refuses, with an
 * {@link IllegalStateException}, to build a query when they do not.
 *
 * @param <R> the related model class
 * @param <S> the relation's own class
 */
public abstract class HasOneOrManyThrough<R extends Model, S extends HasOneOrManyThrough<R, S>>
        extends ThroughCreators<R, S> {

    /** The alias the first key is selected under. */
    static final String THROUGH_KEY = "laravel_through_key";

    private static final String AS = " as ";
    private static final String ALL = "*";

    private final Model throughParent;
    private final String firstKey;
    private final String secondKey;
    private final String localKey;
    private final String secondLocalKey;
    private final Map<Model, Object> throughKeys = new IdentityHashMap<>();
    private boolean isWithTrashedParents;
    private boolean hasBuilt;
    private OneOfMany oneOfMany;
    private String oneOfManyAlias;
    private String throughAlias;

    /**
     * Builds the relation over the related query and the six keys.
     *
     * @param query the related model's query, never {@code null}
     * @param farParent the model the relation is read from, never {@code null}
     * @param throughParent an instance of the through model, never {@code null}
     * @param firstKey the through table's column that holds the far parent's key, never {@code null}
     * @param secondKey the related table's column that holds the through key, never {@code null}
     * @param localKey the far parent's column the first key points at, never {@code null}
     * @param secondLocalKey the through table's column the second key points at, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public HasOneOrManyThrough(EloquentBuilder<R> query, Model farParent, Model throughParent, String firstKey,
            String secondKey, String localKey, String secondLocalKey) {
        super(query, farParent, null);
        this.throughParent = Objects.requireNonNull(throughParent, "throughParent");
        this.firstKey = bare(Objects.requireNonNull(firstKey, "firstKey"));
        this.secondKey = bare(Objects.requireNonNull(secondKey, "secondKey"));
        this.localKey = Objects.requireNonNull(localKey, "localKey");
        this.secondLocalKey = Objects.requireNonNull(secondLocalKey, "secondLocalKey");
    }

    /**
     * Returns the through model instance the path goes through.
     *
     * @return the through model, never {@code null}
     */
    final Model throughParent() {
        return throughParent;
    }

    /**
     * Returns the through table's column that holds the far parent's key, without the table.
     *
     * @return the bare first key, never {@code null}
     */
    public final String getFirstKeyName() {
        return firstKey;
    }

    /**
     * Returns the through table's column that holds the far parent's key, qualified, such as
     * {@code users.country_id}.
     *
     * @return the qualified first key, never {@code null}
     */
    public final String getQualifiedFirstKeyName() {
        return throughParent.qualifyColumn(firstKey);
    }

    /**
     * Returns the related table's column that holds the through key, without the table.
     *
     * @return the bare second key, never {@code null}
     */
    public final String getForeignKeyName() {
        return secondKey;
    }

    /**
     * Returns the related table's column that holds the through key, qualified, such as
     * {@code posts.user_id}.
     *
     * @return the qualified second key, never {@code null}
     */
    public final String getQualifiedForeignKeyName() {
        return getRelated().qualifyColumn(secondKey);
    }

    /**
     * Returns the related table's column that holds the through key, qualified; the same as
     * {@link #getQualifiedForeignKeyName()}.
     *
     * @return the qualified second key, never {@code null}
     */
    public final String getQualifiedFarKeyName() {
        return getQualifiedForeignKeyName();
    }

    /**
     * Returns the far parent's column the first key points at.
     *
     * @return the local key, never {@code null}
     */
    public final String getLocalKeyName() {
        return localKey;
    }

    /**
     * Returns the far parent's column the first key points at, qualified with the parent's table.
     *
     * @return the qualified local key, never {@code null}
     */
    public final String getQualifiedLocalKeyName() {
        return getParent().qualifyColumn(localKey);
    }

    /**
     * Returns the through table's column the second key points at.
     *
     * @return the second local key, never {@code null}
     */
    public final String getSecondLocalKeyName() {
        return secondLocalKey;
    }

    /**
     * Returns the through table's column the second key points at, qualified with the through
     * table.
     *
     * @return the qualified second local key, never {@code null}
     */
    public final String getQualifiedSecondLocalKeyName() {
        return throughParent.qualifyColumn(secondLocalKey);
    }

    /**
     * Returns the far parent's value of the local key.
     *
     * @return the key, or {@code null} when the parent has none, such as before it is saved
     */
    final Object parentKey() {
        return getParent().getAttribute(localKey);
    }

    /**
     * Joins the through table to the related query.
     *
     * @param target the query to join, never {@code null}
     */
    @Override
    void performJoin(EloquentBuilder<R> target) {
        target.join(throughParent.getTable(), getQualifiedSecondLocalKeyName(), "=", getQualifiedForeignKeyName());
    }

    @Override
    final void addConstraints() {
        refuseSameTables();
        performJoin(baseQuery());
        baseQuery().where(getQualifiedFirstKeyName(), "=", parentKey());
        addThroughParentFilter(baseQuery(), throughParent.getTable());
    }

    @Override
    final List<Object> eagerKeys(List<? extends Model> parents) {
        return EagerKeys.<Model>collect(parents, (Model parent) -> parent.getAttribute(localKey));
    }

    @Override
    final void prepareEagerQuery(EloquentBuilder<R> eagerQuery, List<Object> chunk) {
        refuseSameTables();
        performJoin(eagerQuery);
        whereEagerKeys(eagerQuery, getQualifiedFirstKeyName(), chunk);
        addThroughParentFilter(eagerQuery, throughParent.getTable());
    }

    @Override
    final boolean inlinesEagerKeys() {
        return isIntegerKey(getParent(), localKey);
    }

    @Override
    final String eagerMatchColumn() {
        return getQualifiedFirstKeyName();
    }

    /**
     * Completes an eager chunk: the related columns when none are selected, bare selected columns
     * qualified with the related table (both tables are in the query), the hidden key alias, and a
     * row hook that moves the key into the relation's side map.
     *
     * @param eager the eager chunk query, never {@code null}
     */
    @Override
    final void completeEagerQuery(EloquentBuilder<R> eager) {
        List<Object> columns = eager.toBase().getColumns();
        if (isUnselected(columns)) {
            eager.select(getRelated().getTable() + ".*");
        } else {
            qualifyBareColumns(eager, columns);
        }
        addThroughKey(eager);
        Access.eloquent().useRowHook(eager, this::recordThroughKey);
    }

    private void qualifyBareColumns(EloquentBuilder<R> eager, List<Object> columns) {
        List<Object> qualified = new ArrayList<>(columns.size());
        boolean isChanged = false;
        for (Object column : columns) {
            Object next = column;
            if (column instanceof String && isBare((String) column)) {
                next = getRelated().qualifyColumn(((String) column).trim());
                isChanged = true;
            }
            qualified.add(next);
        }
        if (isChanged) {
            eager.select(qualified.toArray());
        }
    }

    private static boolean isBare(String column) {
        String name = column.trim();
        return !name.isEmpty() && !ALL.equals(name) && name.indexOf('.') < 0
                && !name.toLowerCase(Locale.ROOT).contains(AS);
    }

    private static boolean isUnselected(List<Object> columns) {
        return columns.isEmpty() || (columns.size() == 1 && ALL.equals(columns.get(0)));
    }

    private void addThroughKey(EloquentBuilder<R> builder) {
        for (Object column : builder.toBase().getColumns()) {
            if (column instanceof String && ((String) column).contains(THROUGH_KEY)) {
                return;
            }
        }
        builder.addSelect(getQualifiedFirstKeyName() + AS + THROUGH_KEY);
    }

    private void recordThroughKey(R model) {
        Map<String, Object> taken = Access.eloquent().takeAttributes(model, THROUGH_KEY);
        if (taken.containsKey("")) {
            throughKeys.put(model, taken.get(""));
        }
    }

    private void dropThroughKey(R model) {
        Access.eloquent().takeAttributes(model, THROUGH_KEY);
    }

    /**
     * Forgets the through keys recorded by an eager load.
     */
    final void clearThroughKeys() {
        throughKeys.clear();
    }

    /**
     * Groups the eager results by their normalised through key, keeping result order in each
     * group. A result with a {@code null} key joins no group.
     *
     * @param results the related models of every chunk, never {@code null}
     * @return the groups, never {@code null}
     * @throws IllegalStateException if a result has no recorded through key
     */
    final Map<Object, List<R>> dictionary(List<R> results) {
        Map<Object, List<R>> dictionary = new HashMap<>();
        for (R result : results) {
            if (!throughKeys.containsKey(result)) {
                throw new IllegalStateException("A result of relation [" + describe()
                        + "] has no recorded through key; the results were not read by this relation's eager load");
            }
            Object key = EagerKeys.normalise(throughKeys.get(result));
            if (key != null) {
                dictionary.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
            }
        }
        return dictionary;
    }

    /**
     * Returns the matched group of a parent.
     *
     * @param dictionary the groups by key, never {@code null}
     * @param parent the parent, never {@code null}
     * @return the parent's related models, or {@code null} when none matched
     */
    final List<R> matchesOf(Map<Object, List<R>> dictionary, Model parent) {
        Object key = EagerKeys.normalise(parent.getAttribute(localKey));
        return key == null ? null : dictionary.get(key);
    }

    /**
     * Returns the join and the correlation of an existence subquery. A relation whose related
     * table is the outer table aliases the related table; one whose through table is the outer
     * table aliases the through table; any other joins the through table as it is. The relation's
     * own query is changed, which is safe because a relation instance serves one query.
     *
     * @param parentQuery the outer query, never {@code null}
     * @param aliases the outermost query's alias counter, never {@code null}
     * @return the subquery, the relation's own query, never {@code null}
     * @throws IllegalStateException if the related and the through table are the same
     */
    @Override
    final EloquentBuilder<R> existenceQuery(EloquentBuilder<?> parentQuery, AliasCounter aliases) {
        Objects.requireNonNull(parentQuery, "parentQuery");
        Objects.requireNonNull(aliases, "aliases");
        refuseSameTables();
        String outerKey = parentKeyAgainst(parentQuery, localKey);
        if (RelationExistence.isSelfRelation(baseQuery(), parentQuery)) {
            aliasRelatedTable(aliases);
            performJoin(baseQuery());
            baseQuery().whereColumn(outerKey, "=", getQualifiedFirstKeyName());
            addThroughParentFilter(baseQuery(), throughParent.getTable());
            return existenceBaseQuery();
        }
        String outerFrom = parentQuery.toBase().getFrom();
        if (outerFrom != null && RelationExistence.table(outerFrom).equals(throughParent.getTable())) {
            String alias = aliases.next();
            throughAlias = alias;
            baseQuery().join(throughParent.getTable() + AS + alias, alias + "." + secondLocalKey, "=",
                    getQualifiedForeignKeyName());
            baseQuery().whereColumn(outerKey, "=", alias + "." + firstKey);
            addThroughParentFilter(baseQuery(), alias);
            return existenceBaseQuery();
        }
        performJoin(baseQuery());
        baseQuery().whereColumn(outerKey, "=", getQualifiedFirstKeyName());
        addThroughParentFilter(baseQuery(), throughParent.getTable());
        return existenceBaseQuery();
    }

    /**
     * Returns a copy of the constrained query with the select step applied and a row hook that
     * removes the hidden key. The relation's own query is never changed.
     *
     * @param mode whether the read replaces the select or appends to it, never {@code null}
     * @param columns the related columns passed to the read, never {@code null}
     * @return the copy, never {@code null}
     */
    @Override
    final EloquentBuilder<R> prepared(PivotReads.Select mode, String... columns) {
        EloquentBuilder<R> copy = Access.eloquent().copy(constrainedQuery());
        if (mode == PivotReads.Select.APPEND) {
            copy.addSelect(getRelated().getTable() + ".*");
        } else if (isUnselected(copy.toBase().getColumns())) {
            boolean isAll = columns.length == 0 || (columns.length == 1 && ALL.equals(columns[0]));
            copy.select(isAll ? new Object[] {getRelated().getTable() + ".*"} : (Object[]) columns);
        }
        addThroughKey(copy);
        Access.eloquent().useRowHook(copy, this::dropThroughKey);
        return copy;
    }

    /**
     * Tells whether the through model soft-deletes, so trashed through parents are left out.
     *
     * @return {@code true} when the through model soft-deletes
     */
    public final boolean throughParentSoftDeletes() {
        return throughParent.isSoftDeletable();
    }

    /**
     * Keeps through parents that are soft-deleted. Removing global scopes does not do this; only
     * this call does.
     *
     * @return this relation
     * @throws IllegalStateException if the relation already built a query
     */
    public final S withTrashedParents() {
        if (hasBuilt) {
            throw new IllegalStateException("Relation [" + describe() + "] of [" + getParent().getClass().getName()
                    + "] already built a query; call withTrashedParents() before the first read");
        }
        isWithTrashedParents = true;
        return self();
    }

    // ---- one of many

    /**
     * Makes this relation the one row per far parent that the columns pick.
     *
     * @param columns the columns to their aggregate, in order, never {@code null}
     * @param constraint the constraint for every subquery, or {@code null}
     * @throws IllegalStateException if the lazy query was already built
     * @throws IllegalArgumentException if a column or an aggregate is invalid
     */
    final void useOneOfMany(Map<String, String> columns, Consumer<EloquentBuilder<R>> constraint) {
        if (isLazyFinished() || hasBuilt) {
            throw alreadyBuilt();
        }
        oneOfMany = OneOfMany.of(new LinkedHashMap<>(Objects.requireNonNull(columns, "columns")),
                getRelated().getKeyName(), OneOfMany.adapt(constraint), oneOfManyAlias);
    }

    private IllegalStateException alreadyBuilt() {
        return new IllegalStateException("Relation [" + describe() + "] of [" + getParent().getClass().getName()
                + "] already built its query; call the ofMany method before the first read");
    }

    /**
     * Sets the alias of the joined subquery, also when {@code ofMany} comes later.
     *
     * @param alias the alias, never {@code null} or blank
     * @throws NullPointerException if {@code alias} is {@code null}
     * @throws IllegalArgumentException if {@code alias} is blank
     * @throws IllegalStateException if the lazy query was already built
     */
    final void useOneOfManyAlias(String alias) {
        if (isLazyFinished() || hasBuilt) {
            throw alreadyBuilt();
        }
        Objects.requireNonNull(alias, "alias");
        if (alias.trim().isEmpty()) {
            throw new IllegalArgumentException("The ofMany alias must not be blank");
        }
        oneOfMany = oneOfMany == null ? null : oneOfMany.withAlias(alias);
        oneOfManyAlias = alias;
    }

    /**
     * Returns the one-of-many description.
     *
     * @return the description, or {@code null} when the relation is not one of many
     */
    final OneOfMany oneOfMany() {
        return oneOfMany;
    }

    /**
     * Returns whether trashed through parents are kept.
     *
     * @return {@code true} after {@link #withTrashedParents()}
     */
    final boolean keepsTrashedParents() {
        return isWithTrashedParents;
    }

    private String throughQualifier(EloquentBuilder<R> query) {
        // The alias only exists on the existence query this relation owns.
        return throughAlias != null && query == baseQuery() ? throughAlias : throughParent.getTable();
    }

    private void joinOneOfMany(EloquentBuilder<R> query, OneOfManyJoin.Mode mode, List<Object> keys) {
        if (oneOfMany == null) {
            return;
        }
        OneOfManyJoin.apply(query, oneOfMany, new OneOfManyTarget<R>() {
            @Override
            public List<String> oneOfManyPartition(EloquentBuilder<R> q) {
                return Collections.singletonList(throughQualifier(q) + "." + firstKey);
            }

            @Override
            public void prepareOneOfManySubQuery(EloquentBuilder<R> sub) {
                refuseSameTables();
                // The related table is read by its real name in a subquery, never by an existence alias.
                sub.join(throughParent.getTable(), getQualifiedSecondLocalKeyName(), "=",
                        sub.getModel().qualifyColumn(secondKey));
                addThroughParentFilter(sub, throughParent.getTable());
            }

            @Override
            public void filterOneOfManySubQuery(EloquentBuilder<R> sub, OneOfManyJoin.Mode m, List<Object> ks) {
                if (m == OneOfManyJoin.Mode.LAZY) {
                    sub.where(getQualifiedFirstKeyName(), "=", parentKey());
                } else if (m == OneOfManyJoin.Mode.EAGER) {
                    whereEagerKeys(sub, getQualifiedFirstKeyName(), ks);
                }
            }

            @Override
            public String relatedQualifier(EloquentBuilder<R> target) {
                return target.getModel().getTable();
            }

            @Override
            public String oneOfManyAlias(String relatedTable) {
                return OneOfMany.resolveAlias(oneOfMany.alias(), getRelationName(), relatedTable,
                        Arrays.asList(throughParent.getTable(), getParent().getTable()));
            }
        }, mode, keys);
    }

    @Override
    final void afterLazyConstraints() {
        joinOneOfMany(baseQuery(), OneOfManyJoin.Mode.LAZY, Collections.<Object>emptyList());
    }

    @Override
    final void afterEagerConstraint(EloquentBuilder<R> eager, List<Object> chunk) {
        joinOneOfMany(eager, OneOfManyJoin.Mode.EAGER, chunk);
    }

    @Override
    final void afterExistenceWheres(EloquentBuilder<R> existence) {
        joinOneOfMany(existence, OneOfManyJoin.Mode.EXISTENCE, Collections.<Object>emptyList());
    }

    @Override
    final int eagerBindingsPerKey() {
        return oneOfMany != null && !inlinesEagerKeys() ? 2 : 1;
    }

    private void addThroughParentFilter(EloquentBuilder<R> target, String tableOrAlias) {
        if (throughParentSoftDeletes() && !isWithTrashedParents) {
            target.whereNull(tableOrAlias + "." + throughParent.getDeletedAtColumn());
        }
    }

    private void refuseSameTables() {
        hasBuilt = true;
        if (getRelated().getTable().equals(throughParent.getTable())) {
            throw new IllegalStateException("Relation [" + describe() + "] of [" + getParent().getClass().getName()
                    + "] reads the table [" + throughParent.getTable()
                    + "] as both its related and its through table; use a different through model");
        }
    }

    private String describe() {
        return getRelationName() == null ? getRelated().getClass().getName() : getRelationName();
    }
}
