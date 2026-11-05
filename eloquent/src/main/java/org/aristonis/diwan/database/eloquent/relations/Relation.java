package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;
import org.aristonis.diwan.database.eloquent.internal.EloquentAccess;
import org.aristonis.diwan.database.eloquent.internal.TouchScope;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryGrammar;

/**
 * A relation between a parent model and the related model class: Laravel's Eloquent
 * {@code Relation}, with {@link HasOne}, {@link HasMany} and {@link BelongsTo} as its kinds.
 *
 * <p>A relation is built unconstrained. The public query members ({@link #getQuery()},
 * {@link #get()}, {@link #getResults()} and the clause forwarders) add the relation's lazy
 * constraints once, before their own work, so {@code post.comments().get()} reads the post's
 * comments. Eager loading and {@code whereHas} reach the relation through package-private hooks
 * that never add them. That is how Laravel's static {@code Relation::$constraints} switch is
 * replaced: no flag shared between threads, and each path says which constraints it wants.
 *
 * <p>A relation instance is single use and not safe to share across threads. Build a new one,
 * through the model's typed method or {@code relation(name)}, for each query.
 *
 * <p>Users write the concrete type, such as {@code HasMany<Comment>}; {@code S} is the relation's
 * own class, so the forwarders return it. Only this package defines relation kinds.
 *
 * @param <R> the related model class
 * @param <S> the relation's own class
 */
public abstract class Relation<R extends Model, S extends Relation<R, S>> extends RelationClauses<R, S> {

    static {
        Access.setRelations(new RelationAccessImpl());
    }

    private final EloquentBuilder<R> query;

    private final Model parent;

    private String relationName;

    private boolean isConstrained;

    private final List<Consumer<EloquentBuilder<R>>> pendingClauses = new ArrayList<>();

    private final List<Boolean> pendingIsWhere = new ArrayList<>();

    Relation(EloquentBuilder<R> query, Model parent, String relationName) {
        this.query = Objects.requireNonNull(query, "query");
        this.parent = Objects.requireNonNull(parent, "parent");
        this.relationName = relationName;
    }

    /**
     * Makes sure this class has registered its internal access, so the model package can use
     * eager loading and existence queries.
     *
     * @apiNote Internal: not API, may change in any release. A class literal alone does not run a
     *     static initialiser; a call does, without reflection, and survives R8.
     */
    public static void ensureInitialised() {
        // The call itself is the point: the JVM runs the static initialiser above before it.
    }

    /**
     * Returns the relation query with the lazy constraints applied. Clauses added to it stay on
     * this relation.
     *
     * @return the constrained query, never {@code null}
     */
    public final EloquentBuilder<R> getQuery() {
        return constrainedQuery();
    }

    /**
     * Returns the model this relation was built on.
     *
     * @return the parent model, never {@code null}
     */
    public final Model getParent() {
        return parent;
    }

    /**
     * Returns the related model instance the query is built over. It is not a row: it carries
     * the related class's table, keys and connection.
     *
     * @return the related model, never {@code null}
     */
    public final R getRelated() {
        return query.getModel();
    }

    /**
     * Returns the relation's name: the explicit name a builder was given, else the name the
     * model's {@code relations()} map resolved it under.
     *
     * @return the name, or {@code null} for a relation built outside the map and without one
     */
    public final String getRelationName() {
        return relationName;
    }

    /**
     * Runs the lazy query and returns its result in the relation's shape: one model or
     * {@code null} for a single relation, a collection for a many relation.
     *
     * @return the result; its type is given by the subclass
     */
    public abstract Object getResults();

    /**
     * Runs the lazy query and returns every related model it finds.
     *
     * @return the models, never {@code null}
     */
    public final ModelCollection<R> get() {
        ModelCollection<R> results = readQuery().get();
        linkResults(results);
        return results;
    }

    /**
     * The query {@code get()} runs; the constrained query itself by default. A relation kind such
     * as {@code BelongsToMany} overrides this to add select columns and a per-row hydrator without
     * touching the relation's own query instance.
     *
     * @return the query {@code get()} runs, never {@code null}
     */
    EloquentBuilder<R> readQuery() {
        return constrainedQuery();
    }

    /**
     * Lets a kind act on the lazy results of {@link #get()} before they are returned; does
     * nothing by default. Eager results never pass through here, so the blank parent an eager
     * relation is built on is never linked.
     *
     * @param results the models the lazy query found, never {@code null}
     */
    void linkResults(List<R> results) {
        // Nothing by default: only kinds with an inverse link their results.
    }

    // ---- touch

    /**
     * Sets the update timestamp of every related row this relation reads to now, in one raw
     * {@code UPDATE} over the lazy query: no select, no events, no casts. Laravel's
     * {@code Relation::touch}.
     *
     * <p>Nothing runs when the related class keeps no timestamps (including inside
     * {@link Model#withoutTimestamps}), has no update timestamp column, or has its touches
     * switched off on this thread by {@code Model.withoutTouching}. The value is the related
     * model's own {@link Model#freshTimestampValue()}: its clock and stored form, the same text or
     * {@code Instant} an ordinary save of that model writes, whatever the parent's clock is.
     *
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public void touch() {
        String column = touchColumn();
        if (column != null) {
            rawUpdate(Collections.singletonMap(column, getRelated().freshTimestampValue()));
        }
    }

    /**
     * Returns the related update timestamp column a touch writes, or {@code null} when a touch
     * must not run: the related class keeps no timestamps, has no such column, or has its touches
     * switched off on this thread.
     *
     * @return the column name, or {@code null} for no touch
     */
    final String touchColumn() {
        R related = getRelated();
        String column = related.getUpdatedAtColumn();
        if (!related.usesTimestamps() || column == null || TouchScope.isIgnoring(related.getClass())) {
            return null;
        }
        return column;
    }

    /**
     * Runs an {@code UPDATE} of the given columns over the lazy query, with the clauses added to
     * this relation. No model is read or saved: no events, no casts, no timestamps.
     *
     * @param attributes the columns and their new values, never {@code null} or empty; values are
     *     bound as given
     * @return the number of rows changed
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws IllegalArgumentException if {@code attributes} is empty or a value is a list, map or
     *     array
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int rawUpdate(Map<String, ?> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        return getQuery().toBase().update(attributes);
    }

    /**
     * Returns whether a model may list this relation in {@code touches()}. Kinds opt in, so
     * a new kind is refused until it says it can be touched.
     *
     * @return {@code false} unless the kind overrides it
     */
    boolean supportsTouch() {
        return false;
    }

    // ---- package-private: name, query access, lazy constraints

    final void useRelationName(String name) {
        // An explicit name from the 4-arg belongsTo wins over the map's name.
        if (relationName == null) {
            relationName = Objects.requireNonNull(name, "name");
        }
    }

    @Override
    final EloquentBuilder<R> constrainedQuery() {
        ensureLazyConstraints();
        return query;
    }

    @Override
    final void applyClause(Consumer<EloquentBuilder<R>> clause) {
        Objects.requireNonNull(clause, "clause");
        if (isConstrained) {
            clause.accept(query);
            return;
        }
        pendingClauses.add(clause);
        pendingIsWhere.add(Boolean.FALSE);
    }

    @Override
    final void applyWhere(Consumer<EloquentBuilder<R>> clause) {
        Objects.requireNonNull(clause, "clause");
        if (isConstrained) {
            clause.accept(query);
            return;
        }
        pendingClauses.add(clause);
        pendingIsWhere.add(Boolean.TRUE);
    }

    /**
     * Returns the query without the lazy constraints but with every recorded forwarder clause
     * applied. Replaying is idempotent: each recorded clause is applied once. Eager and existence
     * hooks read this.
     *
     * @return the raw query, the relation's own instance
     */
    final EloquentBuilder<R> rawQuery() {
        replayClauses(false);
        return query;
    }

    /**
     * Returns the query for an existence subquery: the recorded {@code where} clauses are applied
     * and the other recorded clauses (order, select, limit) are dropped, as Laravel merges only a
     * relation's wheres into {@code has()}. Each recorded clause is consumed once.
     *
     * @return the query, the relation's own instance
     */
    final EloquentBuilder<R> existenceBaseQuery() {
        replayClauses(true);
        return query;
    }

    /**
     * Returns the query as it stands, without replaying recorded clauses. The lazy constraint and
     * existence hooks add their own clauses here before the recorded ones.
     *
     * @return the query, the relation's own instance
     */
    final EloquentBuilder<R> baseQuery() {
        return query;
    }

    private void replayClauses(boolean isWheresOnly) {
        if (pendingClauses.isEmpty()) {
            return;
        }
        List<Consumer<EloquentBuilder<R>>> clauses = new ArrayList<>(pendingClauses);
        List<Boolean> isWhere = new ArrayList<>(pendingIsWhere);
        pendingClauses.clear();
        pendingIsWhere.clear();
        for (int i = 0; i < clauses.size(); i++) {
            if (!isWheresOnly || isWhere.get(i)) {
                clauses.get(i).accept(query);
            }
        }
    }

    /**
     * Adds the lazy constraints and then replays the recorded clauses the first time it is
     * called; later calls do nothing.
     */
    final void ensureLazyConstraints() {
        if (isConstrained) {
            return;
        }
        isConstrained = true;
        addConstraints();
        replayClauses(false);
    }

    // ---- hooks each relation kind implements

    /**
     * Adds the lazy constraints to the raw query. Called once, by {@link #ensureLazyConstraints()}.
     */
    abstract void addConstraints();

    /**
     * Joins another table to the relation's query. Called by the kinds that own a build order at
     * their lazy, eager and existence build points, after any aliasing and before the key
     * constraint. Does nothing by default; a relation that reads through a table overrides it.
     *
     * @param target the query to change, never {@code null}
     */
    void performJoin(EloquentBuilder<R> target) {
        // Nothing by default: only relations that join a table override it.
    }

    /**
     * Returns whether the eager-load path walks into the related class at validation time.
     * Every current kind has one related class; a polymorphic relation returns {@code true}.
     *
     * @return {@code false} for this release's kinds
     */
    boolean isPolymorphic() {
        return false;
    }

    /**
     * Sets every parent's relation to its empty value before matching: {@code null} or the
     * default for a single relation, an empty collection for a many relation.
     *
     * @param parents the parent models, never {@code null}
     * @param name the relation name to set
     */
    abstract void initRelation(List<? extends Model> parents, String name);

    /**
     * Returns the keys the eager query filters on, taken from the parents: normalised, without
     * nulls, without duplicates, sorted when all are integers.
     *
     * @param parents the parent models, never {@code null}
     * @return the keys, never {@code null}, may be empty
     */
    abstract List<Object> eagerKeys(List<? extends Model> parents);

    /**
     * Adds the key filter for one chunk to an eager query: {@code whereIntegerInRaw} when the
     * keys are inlined, else {@code whereIn}.
     *
     * @param eagerQuery a fresh copy of the raw query, never {@code null}
     * @param chunk the chunk's keys, never {@code null}; empty for the binding probe
     */
    abstract void prepareEagerQuery(EloquentBuilder<R> eagerQuery, List<Object> chunk);

    /**
     * Returns whether the eager keys are written into the SQL instead of bound.
     *
     * @return {@code true} for an integer primary key, see each kind
     */
    abstract boolean inlinesEagerKeys();

    /**
     * Returns the qualified column the eager results are matched on; the eager query must
     * select it.
     *
     * @return the qualified match column, never {@code null}
     */
    abstract String eagerMatchColumn();

    /**
     * Returns the number of keys per eager query before the probe's bindings are taken off.
     *
     * @param grammar the eager query's grammar, never {@code null}
     * @return the inline chunk size when keys are inlined, else the bound one
     */
    int eagerChunkSize(QueryGrammar grammar) {
        return inlinesEagerKeys() ? grammar.inlineKeyChunkSize() : grammar.eagerKeyChunkSize();
    }

    /**
     * Sets each parent's relation from the eager results.
     *
     * @param parents the parent models, never {@code null}
     * @param results the related models of every chunk, in query order, never {@code null}
     * @param name the relation name to set
     */
    abstract void match(List<? extends Model> parents, List<R> results, String name);

    /**
     * Returns the raw query turned into the subquery of {@code has}/{@code whereHas}: the
     * column comparison with the outer query, with a {@code laravel_reserved_N} alias when the
     * relation joins its own table.
     *
     * @param parentQuery the outer query, never {@code null}
     * @param aliases the outermost query's alias counter, never {@code null}
     * @return the subquery, the raw query itself
     */
    abstract EloquentBuilder<R> existenceQuery(EloquentBuilder<?> parentQuery, AliasCounter aliases);

    // ---- the default eager loop

    /**
     * Runs the eager queries for the keys and returns their models in chunk order.
     *
     * <p>A probe, a copy of the raw query with an empty key filter and the constraint, is built
     * first and never run: its bindings shrink the bound chunk so constraint bindings plus keys
     * stay under the grammar's limit, and the eager loads the constraint sets are
     * captured from it into {@code context}. Then each chunk gets a fresh copy, the key
     * filter before the constraint (Laravel's SQL order), the eager loads the constraint set
     * cleared, a refusal of limit and offset, and the match column added to a narrow
     * select. The constraint therefore runs once per chunk plus once for the probe.
     *
     * @param keys the eager keys, never {@code null}
     * @param constraint the user constraint, never {@code null}
     * @param context receives the constraint's eager loads, never {@code null}
     * @return the related models, never {@code null}
     * @throws IllegalStateException if the constraint sets a limit or an offset
     * @throws IllegalArgumentException if a key that must be inlined is not an integer
     */
    List<R> eagerResults(List<Object> keys, Consumer<EloquentBuilder<?>> constraint, EagerContext context) {
        Objects.requireNonNull(constraint, "constraint");
        Objects.requireNonNull(context, "context");
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        EloquentAccess access = Access.eloquent();
        EloquentBuilder<R> probe = access.withoutEagerLoads(rawQuery());
        prepareEagerQuery(probe, Collections.emptyList());
        constraint.accept(probe);
        context.capture(probe.getEagerLoads());
        Builder probeBase = probe.toBase();
        int size = eagerChunkSize(probeBase.getGrammar());
        if (!inlinesEagerKeys()) {
            size = Math.max(1, size - probeBase.getBindings().size());
        }
        List<R> results = new ArrayList<>();
        for (List<Object> chunk : EagerKeys.chunks(keys, size)) {
            EloquentBuilder<R> eager = access.withoutEagerLoads(rawQuery());
            prepareEagerQuery(eager, chunk);
            constraint.accept(eager);
            // The probe captured these loads; running them inside get() would load them per chunk.
            eager = access.withoutEagerLoads(eager);
            refuseLimit(eager);
            completeEagerQuery(eager);
            results.addAll(eager.get());
        }
        return results;
    }

    private void refuseLimit(EloquentBuilder<R> eager) {
        Builder base = eager.toBase();
        if (base.getLimit().isPresent() || base.getOffset().isPresent()) {
            throw new IllegalStateException("The eager constraint of relation [" + relationName + "] on model ["
                    + parent.getClass().getName() + "] sets a limit or an offset; that would cut the results of"
                    + " every parent together, not per parent");
        }
    }

    /**
     * Completes an eager chunk query after the constraint ran; adds the match column by default.
     * A relation kind such as {@code BelongsToMany} overrides this to add its pivot columns and
     * its per-row hydrator instead of the plain match column.
     *
     * @param eager the eager chunk query, never {@code null}
     */
    void completeEagerQuery(EloquentBuilder<R> eager) {
        ensureMatchColumn(eager);
    }

    private void ensureMatchColumn(EloquentBuilder<R> eager) {
        String column = eagerMatchColumn();
        if (!selectsColumn(eager.toBase().getColumns(), column)) {
            eager.addSelect(column);
        }
    }

    private static boolean selectsColumn(List<Object> columns, String qualified) {
        if (columns.isEmpty()) {
            return true;
        }
        String bare = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (Object column : columns) {
            // Expressions cannot be read; the user owns them.
            if (!(column instanceof String)) {
                continue;
            }
            String name = ((String) column).trim();
            int as = name.toLowerCase(Locale.ROOT).indexOf(" as ");
            if (as >= 0) {
                name = name.substring(0, as).trim();
            }
            if (name.equals("*") || name.endsWith(".*")
                    || name.equalsIgnoreCase(qualified) || name.equalsIgnoreCase(bare)) {
                return true;
            }
        }
        return false;
    }

    // ---- helpers shared by the kinds

    /**
     * Adds the key filter for a chunk: inlined integers when {@link #inlinesEagerKeys()}, else
     * bound values.
     *
     * @param eagerQuery the eager query, never {@code null}
     * @param column the qualified key column, never {@code null}
     * @param chunk the keys, never {@code null}
     * @throws IllegalArgumentException if a key to inline is not an integer
     */
    final void whereEagerKeys(EloquentBuilder<R> eagerQuery, String column, List<Object> chunk) {
        if (!inlinesEagerKeys()) {
            eagerQuery.whereIn(column, chunk);
            return;
        }
        List<Long> longs = new ArrayList<>(chunk.size());
        for (Object key : chunk) {
            longs.add(EagerKeys.exactLong(key, parent.getClass(), relationName));
        }
        eagerQuery.query(base -> base.whereIntegerInRaw(column, longs));
    }

    /**
     * Returns an empty collection of the related class, for a parent with no rows.
     *
     * @return a new empty collection, never {@code null}
     */
    final ModelCollection<R> emptyCollection() {
        return Access.eloquent().newCollection(query, Collections.<R>emptyList());
    }

    /**
     * Returns a collection of the related class holding the given models.
     *
     * @param models the models, never {@code null}, copied
     * @return the collection, never {@code null}
     */
    final ModelCollection<R> collectionOf(List<R> models) {
        return Access.eloquent().newCollection(query, new ArrayList<>(models));
    }

    /**
     * Returns whether a model's integer key type allows inlining: {@code int} or
     * {@code integer}, as Laravel's {@code whereInMethod}.
     *
     * @param model the model whose key is compared, never {@code null}
     * @param column the bare key column the relation uses
     * @return whether the column is the model's primary key and the key type is integer
     */
    static boolean isIntegerKey(Model model, String column) {
        String type = model.getKeyType();
        return column.equals(model.getKeyName()) && ("int".equals(type) || "integer".equals(type));
    }

    /**
     * Returns the part of a column after the last dot.
     *
     * @param column the column, never {@code null}
     * @return the bare column
     */
    static String bare(String column) {
        return column.substring(column.lastIndexOf('.') + 1);
    }

    /**
     * Qualifies a parent-side column against the outer query's {@code from}: the alias part of
     * {@code "users as u"}, else the table. Laravel qualifies with the parent model's table, so an
     * aliased outer {@code from} breaks the correlated subquery; without an alias the result
     * equals {@code getParent().qualifyColumn(column)}.
     *
     * @param parentQuery the outer query the subquery correlates with, never {@code null}
     * @param column the bare parent column, never {@code null}; a qualified one is returned as is
     * @return the qualified column, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    final String parentKeyAgainst(EloquentBuilder<?> parentQuery, String column) {
        Objects.requireNonNull(parentQuery, "parentQuery");
        Objects.requireNonNull(column, "column");
        String from = parentQuery.toBase().getFrom();
        if (from == null || column.indexOf('.') >= 0) {
            // A subquery or raw outer from has no name to read; keep the model table.
            return getParent().qualifyColumn(column);
        }
        int as = from.toLowerCase(Locale.ROOT).lastIndexOf(" as ");
        String name = as < 0 ? from.trim() : from.substring(as + " as ".length()).trim();
        return name + "." + column;
    }

    /**
     * Points the raw query and its related model at a fresh {@code laravel_reserved_N} alias.
     *
     * @param aliases the alias counter, never {@code null}
     * @return the alias
     */
    final String aliasRelatedTable(AliasCounter aliases) {
        String alias = aliases.next();
        String table = getRelated().getTable();
        query.query(base -> base.from(table, alias));
        Access.eloquent().useTableAlias(query.getModel(), alias);
        return alias;
    }
}
