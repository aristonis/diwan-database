package org.aristonis.diwan.database.eloquent.internal;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
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
public interface RelationAccess {

    /**
     * Eager loads relations onto models of one class.
     *
     * @param models the parent models, never {@code null}, all of one class; empty does nothing
     * @param relations the constraints by dot path, as parsed by
     *     {@link EloquentAccess#parseWith(String...)}, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a path
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
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a path
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
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the path
     *     names an unknown relation
     */
    void addExistence(EloquentBuilder<?> outer, String relation, String operator, long count, boolean or,
            Consumer<EloquentBuilder<?>> constraint);

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
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name
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
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name
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
     * @throws org.aristonis.diwan.database.eloquent.relations.InvalidRelationException if the
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
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the
     *     model class has no relation of that name
     * @throws org.aristonis.diwan.database.eloquent.relations.InvalidRelationException if the
     *     model class's relation map fails its check
     */
    Relation<?, ?> resolve(Model model, String name);

    /**
     * Returns the type string a model class writes into a morph type column.
     *
     * @param type the model class, never {@code null}
     * @return the alias or the class name, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.ClassMorphViolationException if the class has
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
     * @throws org.aristonis.diwan.database.eloquent.ClassMorphViolationException if the string
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
}
