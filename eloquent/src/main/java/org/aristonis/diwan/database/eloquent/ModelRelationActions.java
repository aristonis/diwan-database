package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.TouchScope;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;
import org.aristonis.diwan.database.eloquent.relations.Relation;
import org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * The relation action layer of {@link Model}: work that a model runs through its relations, as
 * opposed to {@link ModelRelations}, which builds and holds them.
 *
 * <p>The {@code load*} forms read relation aggregates onto this loaded model. Each one builds a
 * one-model list and runs the same loader as {@link ModelCollection}'s forms, so the query is the
 * one a one-model collection sends, and each returns this model. An unsaved model, whose key is
 * {@code null}, sends no query and is left as it is. The values are set as attributes and synced
 * as original, so they are not dirty; an {@code exists} value reads as {@link Boolean}.
 *
 * <p>{@link #touchOwners()} touches the owners named by {@code touches()} now, and the
 * {@code withoutTouching} forms run work with those touches switched off for a model class on
 * the current thread. {@link #push()} saves this model and then every model in its loaded
 * relations.
 */
abstract class ModelRelationActions extends ModelRelations {

    private static final String ANY_COLUMN = "*";

    ModelRelationActions() {
    }

    // ---- load aggregates

    /**
     * Loads the number of related rows of each relation onto this model, under
     * {@code <relation>_count} or the alias given with {@code as}.
     *
     * @param relations the relation names, none {@code null}; {@code "posts as total"} sets the
     *     alias
     * @return this model
     * @throws NullPointerException if {@code relations} or a name is {@code null}
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadCount(String... relations) {
        return loadParsed(WithParser.parseAggregates(relations), ANY_COLUMN, "count");
    }

    /**
     * Loads the number of related rows of each relation, each counted under its constraint.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @return this model
     * @throws NullPointerException if {@code relations}, a name or a constraint is {@code null}
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadCount(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        return loadParsed(WithParser.parseAggregates(relations), ANY_COLUMN, "count");
    }

    /**
     * Loads the largest value of a related column, under {@code <relation>_max_<column>}.
     *
     * @param relation the relation name, never {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument is {@code null}
     * @throws RelationNotFoundException if the name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadMax(String relation, String column) {
        return loadAggregate(relation, column, "max");
    }

    /**
     * Loads the largest value of a related column for each constrained relation.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadMax(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return loadAggregate(relations, column, "max");
    }

    /**
     * Loads the smallest value of a related column, under {@code <relation>_min_<column>}.
     *
     * @param relation the relation name, never {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument is {@code null}
     * @throws RelationNotFoundException if the name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadMin(String relation, String column) {
        return loadAggregate(relation, column, "min");
    }

    /**
     * Loads the smallest value of a related column for each constrained relation.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadMin(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return loadAggregate(relations, column, "min");
    }

    /**
     * Loads the sum of a related column, under {@code <relation>_sum_<column>}.
     *
     * @param relation the relation name, never {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument is {@code null}
     * @throws RelationNotFoundException if the name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadSum(String relation, String column) {
        return loadAggregate(relation, column, "sum");
    }

    /**
     * Loads the sum of a related column for each constrained relation.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadSum(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return loadAggregate(relations, column, "sum");
    }

    /**
     * Loads the average of a related column, under {@code <relation>_avg_<column>}.
     *
     * @param relation the relation name, never {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument is {@code null}
     * @throws RelationNotFoundException if the name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadAvg(String relation, String column) {
        return loadAggregate(relation, column, "avg");
    }

    /**
     * Loads the average of a related column for each constrained relation.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadAvg(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return loadAggregate(relations, column, "avg");
    }

    /**
     * Loads whether each relation has a related row, under {@code <relation>_exists}, as a
     * {@link Boolean}.
     *
     * @param relations the relation names, none {@code null}
     * @return this model
     * @throws NullPointerException if {@code relations} or a name is {@code null}
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadExists(String... relations) {
        return loadParsed(WithParser.parseAggregates(relations), ANY_COLUMN, "exists");
    }

    /**
     * Loads whether each constrained relation has a related row, as a {@link Boolean}.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @return this model
     * @throws NullPointerException if {@code relations}, a name or a constraint is {@code null}
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadExists(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        return loadParsed(WithParser.parseAggregates(relations), ANY_COLUMN, "exists");
    }

    /**
     * Loads an aggregate of a related column, under {@code <relation>_<function>_<column>}:
     * Laravel's {@code loadAggregate}.
     *
     * @param relation the relation name, never {@code null}
     * @param column the related column, never {@code null}
     * @param function the aggregate function, such as {@code "max"}; {@code null} reads one related
     *     row's column, as {@link #loadAggregate(String, String)}
     * @return this model
     * @throws NullPointerException if {@code relation} or {@code column} is {@code null}
     * @throws IllegalArgumentException if the function is not one the loader knows
     * @throws RelationNotFoundException if the name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadAggregate(String relation, String column, String function) {
        Objects.requireNonNull(relation, "relation");
        return loadParsed(WithParser.parseAggregates(relation), Objects.requireNonNull(column, "column"), function);
    }

    /**
     * Loads an aggregate of a related column for each constrained relation.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @param function the aggregate function, or {@code null} to read one related row's column
     * @return this model
     * @throws NullPointerException if {@code relations}, {@code column}, a name or a constraint is
     *     {@code null}
     * @throws IllegalArgumentException if the function is not one the loader knows
     * @throws RelationNotFoundException if a name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadAggregate(Map<String, Consumer<EloquentBuilder<?>>> relations, String column,
            String function) {
        Objects.requireNonNull(column, "column");
        return loadParsed(WithParser.parseAggregates(relations), column, function);
    }

    /**
     * Loads one related row's column with no aggregate function, under
     * {@code <relation>_<column>}.
     *
     * @param relation the relation name, never {@code null}
     * @param column the related column, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument is {@code null}
     * @throws RelationNotFoundException if the name is not a relation of this model
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     */
    public Model loadAggregate(String relation, String column) {
        return loadAggregate(relation, column, null);
    }

    // ---- touch owners

    /**
     * Sets {@code updated_at} on the rows of each relation named in {@code touches()}, to now,
     * a port of Laravel's {@code touchOwners()}. A save of a dirty model and a delete of an
     * existing one call this on their own.
     *
     * <p>Each relation sends one {@code UPDATE} built from this model's keys; no {@code SELECT}
     * runs and no relation is loaded. The owner models already in memory keep their old
     * {@code updated_at}. The touch goes on into an owner only when the relation is loaded on this
     * model: a single loaded owner gets its {@code saved()} hook and then its own touches, each
     * item of a loaded collection gets only its own touches. Within one call each model is walked
     * once, and a cycle of loaded relations ends. A {@code save()} that a hook starts begins a new
     * call. The recursion is as deep as the loaded chain, so a very deep loaded chain can overflow
     * a small thread stack (Android). Owners of a class in a
     * {@link #withoutTouching(Class, Runnable)} scope are skipped.
     *
     * @throws org.aristonis.diwan.database.eloquent.relations.InvalidRelationException if a name in
     *     {@code touches()} is not a relation of this model or names a relation type that does not
     *     support touches
     * @throws IllegalArgumentException if a loaded relation value is not a model, a collection of
     *     models or null
     * @throws IllegalStateException if the model class has no {@link ModelQuery} entry
     * @throws org.aristonis.diwan.database.QueryException if the database refuses an update
     */
    public void touchOwners() {
        Model model = self();
        Relation.ensureInitialised();
        Access.relations().touchOwners(model, new VisitPath());
    }

    /**
     * Runs work with owner touches of a class and its subclasses switched off on this thread,
     * a port of Laravel's {@code withoutTouchingOn([$class], ...)}; calls nest.
     *
     * <p>Only the touch of an owner through a relation is skipped. {@link Model#touch()} and a
     * model's own {@code updated_at} are not affected, and other threads, including one started
     * inside the work, touch as usual. The scope ends when the work returns or throws.
     *
     * @param <V> the result type
     * @param modelClass the owner class to leave untouched, never {@code null}
     * @param work the work, never {@code null}
     * @return the value the work returns, may be {@code null}
     * @throws NullPointerException if an argument is {@code null}; no scope is opened then
     */
    public static <V> V withoutTouching(Class<? extends Model> modelClass, Supplier<V> work) {
        Objects.requireNonNull(modelClass, "modelClass");
        return TouchScope.run(Collections.singletonList(modelClass), work);
    }

    /**
     * Runs work with owner touches of a class and its subclasses switched off on this thread, as
     * {@code withoutTouching(Class, Supplier)} for work that returns no value.
     *
     * @param modelClass the owner class to leave untouched, never {@code null}
     * @param work the work, never {@code null}
     * @throws NullPointerException if an argument is {@code null}; no scope is opened then
     */
    public static void withoutTouching(Class<? extends Model> modelClass, Runnable work) {
        Objects.requireNonNull(modelClass, "modelClass");
        Objects.requireNonNull(work, "work");
        TouchScope.run(Collections.singletonList(modelClass), () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs work with owner touches of several classes and their subclasses switched off on this
     * thread, a port of Laravel's {@code withoutTouchingOn()}; as
     * {@code withoutTouching(Class, Supplier)} otherwise.
     *
     * @param <V> the result type
     * @param modelClasses the owner classes to leave untouched, never {@code null} and holding no
     *     {@code null}; may be empty; copied, so a later change has no effect
     * @param work the work, never {@code null}
     * @return the value the work returns, may be {@code null}
     * @throws NullPointerException if {@code modelClasses}, an item of it, or {@code work} is
     *     {@code null}; no scope is opened then
     */
    public static <V> V withoutTouchingOn(Collection<? extends Class<? extends Model>> modelClasses,
            Supplier<V> work) {
        return TouchScope.run(modelClasses, work);
    }

    // Called by save(boolean) after saved(), before syncOriginal(), so isDirty() still
    // sees the saved changes. touches() first: a model that touches nothing pays no dirty scan.
    // A non-null outer is the factory a saveOrFail() transaction was opened on: the walk then
    // runs each owner bound to that same connection on the transaction (see RelationTouches).
    final void touchOwnersAfterSave(QueryFactory outer) {
        if (touchesNothing() || !isDirty()) {
            return;
        }
        if (outer == null) {
            touchOwners();
            return;
        }
        Model model = self();
        Relation.ensureInitialised();
        Access.relations().touchOwners(model, new VisitPath(model.getQueryFactory(), outer.connection()));
    }

    // Called by delete() once exists and the deleting() veto have passed.
    final void touchOwnersBeforeDelete() {
        if (!touchesNothing()) {
            touchOwners();
        }
    }

    // A null set is left to touchOwners(), whose config check reports it.
    private boolean touchesNothing() {
        Set<String> names = touches();
        return names != null && names.isEmpty();
    }

    // ---- push

    /**
     * Saves this model and then every model in its loaded relations, recursively, a port of
     * Laravel's {@code push()}.
     *
     * <p>This model is saved first; when that save fails nothing else is visited. Then each loaded
     * relation, in the order it was set, has its model or each model of its collection pushed the
     * same way; a {@code null} relation is skipped. The first save that fails stops the walk and
     * the call returns {@code false}. No transaction is opened, so the rows saved before the
     * failure stay saved. Each save is the public {@link Model#save()}, so the {@code saving} and
     * {@code saved} hooks and the owner touches run for every model.
     *
     * <p>No foreign key is set: a new model put under a relation with {@code setRelation} is
     * inserted with whatever key attributes it already has. A {@code withDefault} placeholder that
     * a load put in the relations is inserted as well, as in Laravel. Within one call each model is
     * saved once, so a cycle of loaded relations ends and a model reached by two paths is not saved
     * twice (unlike Laravel, which saves it at each visit). The recursion is as deep as the loaded
     * chain, so a very deep loaded chain can overflow a small thread stack (Android).
     *
     * <p>Each {@code save()} inside the walk runs its own touch walk, so N dirty children that
     * touch the same owner send N {@code UPDATE}s to that owner, as in Laravel.
     *
     * @return {@code true} when every model was saved or already clean, {@code false} when a hook
     *     vetoed a save
     * @throws IllegalArgumentException if a loaded relation value is not a model, a collection of
     *     models or null
     * @throws IllegalStateException if a model to save has no connection
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public boolean push() {
        return push(new VisitPath());
    }

    // A model on the path or pushed already in this call counts as saved, so a cycle ends and a
    // shared model is written once. The relations are snapshotted, since a hook may change them during the walk.
    final boolean push(VisitPath path) {
        Model model = self();
        if (path.isDone(model) || !path.enter(model)) {
            return true;
        }
        path.markDone(model);
        try {
            if (!model.save()) {
                return false;
            }
            for (Object value : new ArrayList<>(getRelations().values())) {
                for (Model related : VisitPath.modelsOf(value)) {
                    if (!related.push(path)) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            path.leave(model);
        }
    }

    // The one call every load form ends in: a one-model list, the collection's loader.
    private Model loadParsed(Map<String, Consumer<EloquentBuilder<?>>> parsed, Object column, String function) {
        Model model = self();
        Relation.ensureInitialised();
        Access.relations().loadAggregate(Collections.singletonList(model), parsed, column, function);
        return model;
    }
}
