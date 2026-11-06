package org.aristonis.diwan.database.eloquent.internal;

import org.aristonis.diwan.database.Internal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.MorphEagerOptions;
import org.aristonis.diwan.database.eloquent.relations.MorphToMany;
import org.aristonis.diwan.database.eloquent.relations.Relation;

/**
 * What the model package needs from the relation package's package-private members.
 *
 * <p>Implemented once, in the relation package, and registered by {@code Relation}'s static
 * initialiser through {@link Access#setRelations(RelationAccess)}. Callers in the model package
 * call {@code Relation.ensureInitialised()} first.
 *
 * <p>Internal: not API.
 *
 * @implSpec An implementation keeps no state of its own and is safe to call from any thread.
 */
@Internal
public interface RelationAccess {

    /**
     * Eager loads relations onto models of one class.
     *
     * @param models the parent models, never {@code null}, all of one class; empty does nothing
     * @param relations the constraints by dot path, as parsed by
     *     {@link EloquentAccess#parseWith(String...)}, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a path
     *     names an unknown relation; no query has run
     * @throws IllegalStateException if a constraint sets a limit or an offset
     */
    void load(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations);

    /**
     * Eager loads the relations that are not loaded yet on models of one class, level by level.
     *
     * @param models the parent models, never {@code null}, all of one class; empty does nothing
     * @param relations the constraints by dot path, as for {@link #load(List, Map)}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a path
     *     names an unknown relation
     * @throws IllegalStateException if a constraint sets a limit or an offset
     */
    void loadMissing(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations);

    /**
     * Adds a relation existence condition ({@code has}, {@code whereHas} and their forms) to a
     * builder.
     *
     * @param outer the builder the condition is added to, never {@code null}
     * @param relation the relation name or dot path, never {@code null}
     * @param operator the count comparison: {@code =}, {@code <}, {@code >}, {@code <=},
     *     {@code >=}, {@code <>} or {@code !=}, never {@code null}
     * @param count the count compared with
     * @param or {@code true} to join the condition with {@code or}
     * @param constraint the constraint on the related query, or {@code null} for none
     * @throws NullPointerException if {@code outer}, {@code relation} or {@code operator} is
     *     {@code null}
     * @throws IllegalArgumentException if the operator is not one of the seven
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the path
     *     names an unknown relation
     */
    void addExistence(EloquentBuilder<?> outer, String relation, String operator, long count, boolean or,
            Consumer<EloquentBuilder<?>> constraint);

    /**
     * Adds a morph existence group ({@code hasMorph}, {@code whereHasMorph} and their forms) to a
     * builder.
     *
     * @param outer the builder the group is added to, never {@code null}
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param types the owner classes or morph aliases, or the single element {@code "*"}, never
     *     {@code null}
     * @param operator the count comparison: {@code =}, {@code <}, {@code >}, {@code <=},
     *     {@code >=}, {@code <>} or {@code !=}, never {@code null}
     * @param count the count compared with
     * @param or {@code true} to join the group with {@code or}
     * @param constraint the constraint on each owner query, given the owner class, or {@code null}
     * @throws NullPointerException if an argument other than {@code constraint} is {@code null}
     * @throws IllegalArgumentException if the operator is unknown, the name is a dot path or not a
     *     morph-to relation, or a type is neither a model class nor a string
     * @throws org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException if a type cannot
     *     be resolved; nothing has been added
     */
    void addMorphExistence(EloquentBuilder<?> outer, String relation, List<Object> types, String operator,
            long count, boolean or, BiConsumer<EloquentBuilder<?>, Class<? extends Model>> constraint);

    /**
     * Adds a condition that a morph-to relation points at a model, a class, a type or a set of
     * models.
     *
     * @param outer the builder the condition is added to, never {@code null}
     * @param relation the name of a morph-to relation, without a dot, never {@code null}
     * @param target a model, a model class, a morph alias string, a non-empty collection of
     *     models, or {@code null} for no type
     * @param not {@code true} to negate; a null type or key then counts as not pointing at the target
     * @param or {@code true} to join the condition with {@code or}
     * @throws NullPointerException if {@code outer} or {@code relation} is {@code null}
     * @throws IllegalArgumentException if the name is a dot path or not a morph-to relation, the
     *     collection is empty, a model has no key, or the target is of another kind
     * @throws org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException if a class or
     *     alias cannot be resolved
     */
    void whereMorphedTo(EloquentBuilder<?> outer, String relation, Object target, boolean not, boolean or);

    /**
     * Wraps per-class eager options, and an optional plain constraint, into the constraint stored
     * under a morph-to relation's name in the eager-load map.
     *
     * @param options fills the per-class options, never {@code null}
     * @param plain the constraint that runs on every owner class, or {@code null} for none
     * @return the constraint, never {@code null}
     * @throws NullPointerException if {@code options} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException if an option
     *     names a class or alias that cannot be resolved
     */
    Consumer<EloquentBuilder<?>> morphEager(Consumer<MorphEagerOptions> options, Consumer<EloquentBuilder<?>> plain);

    /**
     * Merges two constraints registered for one eager-load path, keeping the per-class options of
     * a constraint built by {@link #morphEager}; plain constraints run first {@code a}, then
     * {@code b}.
     *
     * @param a the constraint added first, never {@code null}
     * @param b the constraint added second, never {@code null}
     * @return the merged constraint, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    Consumer<EloquentBuilder<?>> combineEager(Consumer<EloquentBuilder<?>> a, Consumer<EloquentBuilder<?>> b);

    /**
     * Loads relations, or relation counts, onto the already loaded owners of a morph-to relation,
     * per owner class.
     *
     * @param models the child models, never {@code null}; those without the relation loaded are
     *     skipped
     * @param relation the morph-to relation name, never {@code null}
     * @param relations the relations to load, by owner class, never {@code null}
     * @param isCount {@code true} to load counts instead of models
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name
     *     is unknown
     */
    void loadMorph(Collection<? extends Model> models, String relation,
            Map<Class<? extends Model>, List<String>> relations, boolean isCount);

    /**
     * Adds one aggregate subselect per relation ({@code withCount}, {@code withMax} and their
     * forms) to a builder, in map order.
     *
     * @param outer the builder the subselects are added to, never {@code null}
     * @param relations the constraints keyed by raw name, {@code as} alias included, with no dot
     *     expansion, never {@code null}; empty does nothing
     * @param column the aggregated column: a {@code String} ({@code "*"} for count and exists) or
     *     a core {@code Expression}, never {@code null}
     * @param function the aggregate function in any case (count, max, min, sum, avg or exists), or
     *     {@code null} for one related row's column
     * @throws NullPointerException if {@code outer}, {@code relations} or {@code column} is
     *     {@code null}
     * @throws IllegalArgumentException if the function is not one of the six
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name
     *     is unknown or is a dot path; the builder is unchanged
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     and groupBy; the builder is unchanged
     */
    void addAggregate(EloquentBuilder<?> outer, Map<String, Consumer<EloquentBuilder<?>>> relations, Object column,
            String function);

    /**
     * Loads relation aggregates onto models already in memory ({@code loadCount},
     * {@code loadMax} and their forms): one key-only query per key chunk, the values copied onto
     * each model whose row came back, not dirty.
     *
     * @param models the models, never {@code null}, all of one class; empty does nothing and
     *     checks no name
     * @param relations the constraints keyed by raw name, {@code as} alias included, as for
     *     {@link #addAggregate(EloquentBuilder, Map, Object, String)}, never {@code null}
     * @param column the aggregated column: a {@code String} ({@code "*"} for count and exists) or
     *     a core {@code Expression}, never {@code null}
     * @param function the aggregate function in any case, or {@code null} for one related row's
     *     column
     * @throws NullPointerException if {@code models}, a model, {@code relations} or
     *     {@code column} is {@code null}
     * @throws IllegalArgumentException if the models are not all of one class, the function is not
     *     one of the six, or the constraint bindings leave no room for a key; no query has run
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name
     *     is unknown or is a dot path; no query has run
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     and groupBy
     */
    void loadAggregate(List<? extends Model> models, Map<String, Consumer<EloquentBuilder<?>>> relations,
            Object column, String function);

    /**
     * Touches the owners a model names in its {@code touches()} and walks on into the owners
     * already loaded on it: Laravel's {@code touchOwners}.
     *
     * <p>Each name runs one raw {@code UPDATE} of its relation; no relation is loaded and no
     * {@code SELECT} runs. A single loaded owner gets its {@code saved()} hook and then its own
     * touches; each item of a loaded collection gets its own touches only. A model on the path or
     * walked already in this call is skipped, so a cycle ends and a shared owner is walked once.
     *
     * @param model the model whose owners are touched, never {@code null}
     * @param path the path and done set of the outermost call, never {@code null}; a new one per
     *     outermost call, never shared between threads
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.InvalidRelationException if the
     *     class's {@code touches()} names an unknown relation or one whose type does not support
     *     touches; checked once per class
     * @throws IllegalArgumentException if a loaded relation value is not a model, a collection of
     *     models or {@code null}
     * @throws IllegalStateException if a model's class has no {@code ModelQuery} entry
     */
    void touchOwners(Model model, VisitPath path);

    /**
     * Resolves a relation by name through the model class's checked relation map.
     *
     * @param model the parent model, never {@code null}
     * @param name the relation name, never {@code null}
     * @return the unconstrained relation, its name set, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if the
     *     model class has no relation of that name
     * @throws org.aristonis.diwan.database.eloquent.exception.InvalidRelationException if the
     *     model class's relation map fails its check
     */
    Relation<?, ?> resolve(Model model, String name);

    /**
     * Returns the type string a model class writes into a morph type column.
     *
     * @param type the model class, never {@code null}
     * @return the alias or the class name, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException if the class has
     *     no alias and the morph map is required
     */
    String aliasOf(Class<? extends Model> type);

    /**
     * Returns the model class a stored morph type string names.
     *
     * @param type the stored type string, never {@code null}
     * @param loader the class loader to load a class name through, or {@code null} for the
     *     bootstrap loader
     * @return the model class, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException if the string
     *     cannot be resolved
     */
    Class<? extends Model> resolveMorph(String type, ClassLoader loader);

    /**
     * Builds a {@code BelongsToMany} for the model layer; called by {@code belongsToMany(...)}
     * factories. All arguments are already resolved; none is {@code null} except {@code relation}.
     *
     * @param <R> the related model class
     * @param query the relation's unconstrained query, never {@code null}
     * @param parent the owning model, never {@code null}
     * @param table the pivot table, never {@code null}
     * @param foreignPivotKey the pivot's parent key column, never {@code null}
     * @param relatedPivotKey the pivot's related key column, never {@code null}
     * @param parentKey the parent's key column, never {@code null}
     * @param relatedKey the related model's key column, never {@code null}
     * @param relation the relation's name, or {@code null} to take it from the model's map
     * @return the new relation, never {@code null}
     */
    <R extends Model> BelongsToMany<R> newBelongsToMany(EloquentBuilder<R> query, Model parent, String table,
            String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey, String relation);

    /**
     * Builds a morph-to-many relation. The arguments are resolved by the model's factories.
     *
     * @param <R> the related model class
     * @param query the relation's unconstrained query, never {@code null}
     * @param parent the owning model, never {@code null}
     * @param name the morph name, never {@code null}
     * @param table the pivot table, or {@code null} for the plural of the last word of the name
     * @param foreignPivotKey the pivot's parent key column, never {@code null}
     * @param relatedPivotKey the pivot's related key column, never {@code null}
     * @param parentKey the parent's key column, never {@code null}
     * @param relatedKey the related model's key column, never {@code null}
     * @param relation the relation's name, or {@code null} to take it from the model's map
     * @param inverse whether the type column holds the related model's morph class
     * @return the new relation, never {@code null}
     */
    <R extends Model> MorphToMany<R> newMorphToMany(EloquentBuilder<R> query, Model parent, String name,
            String table, String foreignPivotKey, String relatedPivotKey, String parentKey,
            String relatedKey, String relation, boolean inverse);
}
