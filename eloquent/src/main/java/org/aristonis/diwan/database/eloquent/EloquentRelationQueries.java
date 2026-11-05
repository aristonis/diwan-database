package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.Relation;
import org.aristonis.diwan.database.query.Builder;

/**
 * The relation methods of {@link EloquentBuilder}: the eager loads set with {@code with}, and the
 * alias counter that self-relation existence subqueries number their tables with.
 *
 * <p>Eager loads are kept by dot path in call order, each with its constraint. They run after the
 * models are read, one query per relation per level, on every read that returns models except
 * {@link EloquentBuilder#cursor()}, which refuses them.
 *
 * @param <T> the model class
 */
abstract class EloquentRelationQueries<T extends Model> extends EloquentCreators<T> {

    private static final String AT_LEAST = ">=";
    private static final String LESS_THAN = "<";

    /** The eager loads by dot path, parents before children, in call order. */
    private final Map<String, Consumer<EloquentBuilder<?>>> eagerLoads = new LinkedHashMap<>();

    /** The alias counter, created on first use and shared with existence subqueries. */
    private AliasCounter aliases;

    EloquentRelationQueries(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
    }

    // ---- eager loads

    /**
     * Eager loads relations with the models this builder reads.
     *
     * <p>A dot path such as {@code "posts.comments"} loads each level. {@code "posts:id,title"}
     * selects only those columns of the relation; the key the relation matches on is added when
     * left out. Naming a path twice runs both constraints, the earlier first. Names are checked
     * when the models are loaded, not here.
     *
     * @param relations the relation paths, none {@code null}
     * @return this builder
     * @throws NullPointerException if a path is {@code null}
     * @throws IllegalArgumentException if a {@code name:columns} path lists an empty column
     */
    public final EloquentBuilder<T> with(String... relations) {
        WithParser.merge(eagerLoads, WithParser.parse(relations));
        return self();
    }

    /**
     * Eager loads one relation, letting a callback constrain its query.
     *
     * <p>The callback runs once per key chunk of the eager query, plus once on a probe query
     * that is never run. A limit or offset in it is refused when the relation loads. The relation
     * keys are filtered first, so an {@code orWhere} in the callback must be grouped, as in
     * Laravel.
     *
     * @param relation the relation path, never {@code null}; a {@code :columns} part is not parsed
     * @param constraint the constraint on the relation's query, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public final EloquentBuilder<T> with(String relation, Consumer<EloquentBuilder<?>> constraint) {
        return with(Collections.singletonMap(Objects.requireNonNull(relation, "relation"),
                Objects.requireNonNull(constraint, "constraint")));
    }

    /**
     * Eager loads relations, each with a callback constraining its query, as
     * {@code with}.
     *
     * @param relations the constraints by relation path, no key or value {@code null}
     * @return this builder
     * @throws NullPointerException if the map, a path or a constraint is {@code null}
     */
    public final EloquentBuilder<T> with(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        WithParser.merge(eagerLoads, WithParser.parse(relations));
        return self();
    }

    /**
     * Removes eager loads by exact path; {@code without("posts")} keeps {@code "posts.comments"},
     * as in Laravel.
     *
     * @param relations the paths to remove, none {@code null}
     * @return this builder
     * @throws NullPointerException if a path is {@code null}
     */
    public final EloquentBuilder<T> without(String... relations) {
        for (String relation : relations) {
            eagerLoads.remove(Objects.requireNonNull(relation, "relation"));
        }
        return self();
    }

    /**
     * Replaces every eager load with the given ones, as {@code with}.
     *
     * @param relations the relation paths, none {@code null}
     * @return this builder
     * @throws NullPointerException if a path is {@code null}
     * @throws IllegalArgumentException if a {@code name:columns} path lists an empty column
     */
    public final EloquentBuilder<T> withOnly(String... relations) {
        Map<String, Consumer<EloquentBuilder<?>>> parsed = WithParser.parse(relations);
        eagerLoads.clear();
        eagerLoads.putAll(parsed);
        return self();
    }

    /**
     * Returns the eager loads set on this builder.
     *
     * @return the constraints by dot path, parents before children in call order; an
     *     unmodifiable copy, never {@code null}
     */
    public final Map<String, Consumer<EloquentBuilder<?>>> getEagerLoads() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(eagerLoads));
    }

    final boolean hasEagerLoads() {
        return !eagerLoads.isEmpty();
    }

    final void clearEagerLoads() {
        eagerLoads.clear();
    }

    /**
     * Runs this builder's eager loads on the models read, unless either is empty.
     *
     * @param models the models read, never {@code null}
     * @return {@code models}, now with their relations set
     */
    final ModelCollection<T> eagerLoaded(ModelCollection<T> models) {
        if (!eagerLoads.isEmpty() && !models.isEmpty()) {
            eagerLoad(models, getEagerLoads(), false);
        }
        return models;
    }

    /**
     * Eager loads relations onto models of one class, through the relation package's loader.
     *
     * @param models the models, never {@code null}
     * @param relations the constraints by dot path, never {@code null}
     * @param missingOnly whether to load only what is not loaded yet
     * @throws IllegalArgumentException if the models are of more than one class
     */
    static void eagerLoad(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations,
            boolean missingOnly) {
        if (models.isEmpty() || relations.isEmpty()) {
            return;
        }
        // A class literal does not initialise Relation, and its initialiser registers the bridge.
        Relation.ensureInitialised();
        if (missingOnly) {
            Access.relations().loadMissing(models, relations);
        } else {
            Access.relations().load(models, relations);
        }
    }

    // ---- existence queries

    /**
     * Keeps models that have at least one related model: {@code exists (select ...)}.
     *
     * <p>A dot path such as {@code "posts.comments"} nests one existence query per segment.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> has(String relation) {
        return existence(relation, AT_LEAST, 1, false, null);
    }

    /**
     * Keeps models whose related count compares with {@code count}. {@code >= 1} and {@code < 1}
     * compile to {@code exists} and {@code not exists}, anything else to
     * {@code (select count(*) ...) op ?}. On a dot path the comparison applies to the last
     * segment.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param operator the comparison: {@code =}, {@code <}, {@code >}, {@code <=}, {@code >=},
     *     {@code <>} or {@code !=}, never {@code null}
     * @param count the count compared with
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the operator is not one of the seven
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> has(String relation, String operator, long count) {
        return existence(relation, operator, count, false, null);
    }

    /**
     * Adds an {@code or} condition that the model has at least one related model.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> orHas(String relation) {
        return existence(relation, AT_LEAST, 1, true, null);
    }

    /**
     * Keeps models that have no related model: {@code not exists (select ...)}. On a dot path
     * only the first segment is negated, as in Laravel.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> doesntHave(String relation) {
        return existence(relation, LESS_THAN, 1, false, null);
    }

    /**
     * Adds an {@code or} condition that the model has no related model.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> orDoesntHave(String relation) {
        return existence(relation, LESS_THAN, 1, true, null);
    }

    /**
     * Keeps models that have at least one related model matching a constraint. The constraint
     * runs in a where group on the related query; a constraint that adds no condition adds no
     * group.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param constraint the constraint on the related query, or {@code null} for none
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> whereHas(String relation, Consumer<EloquentBuilder<?>> constraint) {
        return existence(relation, AT_LEAST, 1, false, constraint);
    }

    /**
     * Keeps models whose count of related models matching a constraint compares with
     * {@code count}, as {@code has}.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param constraint the constraint on the related query, or {@code null} for none
     * @param operator the comparison, one of the seven of {@link #has(String, String, long)},
     *     never {@code null}
     * @param count the count compared with
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code operator} is {@code null}
     * @throws IllegalArgumentException if the operator is not one of the seven
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> whereHas(String relation, Consumer<EloquentBuilder<?>> constraint,
            String operator, long count) {
        return existence(relation, operator, count, false, constraint);
    }

    /**
     * Adds an {@code or} condition that the model has a related model matching a constraint.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param constraint the constraint on the related query, or {@code null} for none
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> orWhereHas(String relation, Consumer<EloquentBuilder<?>> constraint) {
        return existence(relation, AT_LEAST, 1, true, constraint);
    }

    /**
     * Keeps models that have no related model matching a constraint.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param constraint the constraint on the related query, or {@code null} for none
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> whereDoesntHave(String relation, Consumer<EloquentBuilder<?>> constraint) {
        return existence(relation, LESS_THAN, 1, false, constraint);
    }

    /**
     * Adds an {@code or} condition that the model has no related model matching a constraint.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param constraint the constraint on the related query, or {@code null} for none
     * @return this builder
     * @throws NullPointerException if {@code relation} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> orWhereDoesntHave(String relation, Consumer<EloquentBuilder<?>> constraint) {
        return existence(relation, LESS_THAN, 1, true, constraint);
    }

    /**
     * Keeps models with a related model whose column equals a value: {@code whereHas} with
     * {@code where(column, value)}. The column is not qualified.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param column the related column, never {@code null}
     * @param value the value, as {@link EloquentBuilder#where(String, Object)} accepts it
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code column} is {@code null}
     * @throws IllegalArgumentException if the related builder refuses the value
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> whereRelation(String relation, String column, Object value) {
        Objects.requireNonNull(column, "column");
        return whereHas(relation, q -> q.where(column, value));
    }

    /**
     * Keeps models with a related model whose column compares with a value: {@code whereHas}
     * with {@code where(column, operator, value)}.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param column the related column, never {@code null}
     * @param operator the column operator, as {@link EloquentBuilder#where(String, String, Object)}
     *     accepts it, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code column} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the related builder refuses the value
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> whereRelation(String relation, String column, String operator, Object value) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        return whereHas(relation, q -> q.where(column, operator, value));
    }

    /**
     * Adds an {@code or} condition that a related model's column equals a value.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param column the related column, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation} or {@code column} is {@code null}
     * @throws IllegalArgumentException if the related builder refuses the value
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> orWhereRelation(String relation, String column, Object value) {
        Objects.requireNonNull(column, "column");
        return orWhereHas(relation, q -> q.where(column, value));
    }

    /**
     * Adds an {@code or} condition that a related model's column compares with a value.
     *
     * @param relation the relation name or dot path, never {@code null}
     * @param column the related column, never {@code null}
     * @param operator the column operator, never {@code null}
     * @param value the value
     * @return this builder
     * @throws NullPointerException if {@code relation}, {@code column} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the related builder refuses the value
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     path names an unknown relation
     */
    public final EloquentBuilder<T> orWhereRelation(String relation, String column, String operator, Object value) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        return orWhereHas(relation, q -> q.where(column, operator, value));
    }

    /**
     * Adds a parenthesised group of clauses built on an Eloquent builder for the same model, as
     * Laravel's {@code where(Closure)}. Existence methods called inside the group continue this
     * builder's alias numbering. A group that adds no condition is left out. Scope removals made
     * inside the group, such as {@code withTrashed()} or {@code withoutGlobalScope(...)}, are
     * ignored, as in Laravel.
     *
     * @param group builds the clauses of the group, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    public final EloquentBuilder<T> whereGroup(Consumer<? super EloquentBuilder<T>> group) {
        return group(group, false);
    }

    /**
     * Adds a group like {@link #whereGroup(Consumer)}, but merges the scope removals made inside it
     * into this builder, so a relation constraint can lift a scope of the related model.
     *
     * @param group builds the clauses of the group, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code group} is {@code null}
     */
    final EloquentBuilder<T> constraintGroup(Consumer<? super EloquentBuilder<T>> group) {
        return group(group, true);
    }

    private EloquentBuilder<T> group(Consumer<? super EloquentBuilder<T>> group, boolean isMerging) {
        Objects.requireNonNull(group, "group");
        AliasCounter shared = aliases();
        List<EloquentBuilder<T>> inners = new ArrayList<>(1);
        query(base -> base.where(nested -> {
            EloquentBuilder<T> inner = new EloquentBuilder<>(source, model, nested);
            inner.useAliases(shared);
            group.accept(inner);
            inners.add(inner);
        }));
        if (isMerging) {
            for (EloquentBuilder<T> inner : inners) {
                mergeRemovedFrom(inner);
            }
        }
        return self();
    }

    private EloquentBuilder<T> existence(String relation, String operator, long count, boolean or,
            Consumer<EloquentBuilder<?>> constraint) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(operator, "operator");
        // A class literal does not initialise Relation, and its initialiser registers the bridge.
        Relation.ensureInitialised();
        Access.relations().addExistence(self(), relation, operator, count, or, constraint);
        return self();
    }

    // ---- alias counter

    final AliasCounter aliases() {
        if (aliases == null) {
            aliases = new AliasCounter();
        }
        return aliases;
    }

    final void useAliases(AliasCounter counter) {
        aliases = Objects.requireNonNull(counter, "counter");
    }

    /**
     * Gives a copy of this builder the same eager loads and its own copy of the alias counter,
     * so building on the copy cannot shift this builder's aliases.
     *
     * @param target the copy, never {@code null}
     */
    final void copyRelationStateTo(EloquentRelationQueries<T> target) {
        target.eagerLoads.putAll(eagerLoads);
        target.aliases = aliases == null ? null : aliases.copy();
    }
}
