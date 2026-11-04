package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.EloquentAccess;
import org.aristonis.diwan.database.eloquent.internal.UnregisteredModelException;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * Touches the owners a model names in its {@code touches()}: Laravel's {@code touchOwners}.
 *
 * <p>The names are read and checked once per model class, on a bare copy of the first model, and
 * the checked {@link Config} is kept in the class's {@code ModelQuery} touches slot. Each name must
 * be a relation of the class's map whose type supports touches. A failed check is kept too and
 * thrown again as a new {@link InvalidRelationException} on every later use, except a failure
 * caused by a related class with no {@code ModelQuery} entry yet, which is checked again next time.
 *
 * <p>A touch is the relation's own raw {@code UPDATE}; nothing is loaded and no {@code SELECT}
 * runs. The walk goes on only into owners already loaded on the model: a single loaded owner gets
 * its {@code saved()} hook, then its own touches; each item of a loaded collection gets its own
 * touches only, as in Laravel. The {@link VisitPath} ends cycles, and its done set walks each model
 * once per outermost call, so a shared owner is walked once where Laravel walks it per path.
 * A loaded {@code BelongsTo} owner walked already in the call, whose key equals the child's current
 * foreign key, gets no second {@code UPDATE}, so N children sharing one loaded owner touch it once.
 * A {@code HasOne} or {@code HasMany} always sends its {@code UPDATE}, since a loaded value
 * may hold only some of the rows; a stale loaded owner does not stop the current row's update; an
 * unloaded relation cannot know its owner, so it sends its {@code UPDATE} for every model naming it.
 *
 * <p>Stateless apart from the per-class slot, which is written once by {@code compareAndSet}; safe
 * to use from any thread. The models and the path it is given are not.
 */
final class RelationTouches {

    private RelationTouches() {
    }

    /**
     * The checked {@code touches()} of one model class: its names in order, or the failure of
     * the check. Immutable and safe to share across threads.
     */
    static final class Config {

        /** The configuration of every class that touches nothing. */
        static final Config EMPTY = new Config(Collections.<String>emptyList(), null);

        private final List<String> names;

        private final InvalidRelationException failure;

        private Config(List<String> names, InvalidRelationException failure) {
            this.names = names;
            this.failure = failure;
        }

        /**
         * Returns a configuration of checked names.
         *
         * @param names the relation names in {@code touches()} order, never {@code null}; copied
         * @return the configuration, {@link #EMPTY} when there are no names, never {@code null}
         */
        static Config of(List<String> names) {
            if (names.isEmpty()) {
                return EMPTY;
            }
            // No List.copyOf: Android API 28.
            return new Config(Collections.unmodifiableList(new ArrayList<>(names)), null);
        }

        /**
         * Returns a configuration that throws a check failure again on every use.
         *
         * @param failure the first failure, never {@code null}
         * @return the failed configuration, never {@code null}
         */
        static Config failed(InvalidRelationException failure) {
            return new Config(Collections.<String>emptyList(), Objects.requireNonNull(failure, "failure"));
        }

        /**
         * Returns the relation names to touch, in {@code touches()} order.
         *
         * @return the names, unmodifiable, never {@code null}; empty when the class touches nothing
         * @throws InvalidRelationException if the class's check failed; a new exception each
         *     time, the first failure as its cause
         */
        List<String> names() {
            if (failure != null) {
                throw new InvalidRelationException(failure);
            }
            return names;
        }
    }

    /**
     * Returns the checked {@code touches()} configuration of a model's class, checking it on
     * first use.
     *
     * @param model a model of the class, never {@code null}
     * @return the configuration, never {@code null}; the shared {@link Config#EMPTY} when the class
     *     touches nothing
     * @throws NullPointerException if {@code model} is {@code null}, or its {@code touches()}
     *     returns {@code null}
     * @throws InvalidRelationException if a name is not a relation of the class, its relation type
     *     does not support touches, or the class's relation map fails its check; kept, except when
     *     a related class has no {@code ModelQuery} entry yet
     * @throws IllegalStateException if the model's class has no {@code ModelQuery} entry
     */
    static Config config(Model model) {
        Objects.requireNonNull(model, "model");
        AtomicReference<Object> slot = Access.eloquent().touchesSlot(model);
        Object current = slot.get();
        if (current == null) {
            Config built = check(model);
            // First writer wins; the caller whose check was kept sees its own failure.
            if (slot.compareAndSet(null, built) && built.failure != null) {
                throw built.failure;
            }
            current = slot.get();
        }
        Config config = (Config) current;
        // A kept failure is thrown again as a new exception, the first one as its cause.
        config.names();
        return config;
    }

    // Builds the configuration, catching a check failure into a failed one; a failure caused by an
    // unregistered related class is thrown uncached, so registering the class later is seen.
    private static Config check(Model model) {
        try {
            return build(model);
        } catch (InvalidRelationException e) {
            if (isUnregistered(e)) {
                throw e;
            }
            return Config.failed(e);
        }
    }

    private static Config build(Model model) {
        EloquentAccess access = Access.eloquent();
        Class<?> modelClass = model.getClass();
        Model sample = access.newInstanceLike(model);
        Set<String> declared = access.touches(sample);
        if (declared.isEmpty()) {
            return Config.EMPTY;
        }
        RelationRegistry registry = RelationRegistry.of(sample);
        List<String> names = new ArrayList<>();
        for (String name : declared) {
            if (name == null) {
                throw new InvalidRelationException(modelClass, null, "touches() holds a null name");
            }
            if (!resolve(registry, name, sample, modelClass).supportsTouch()) {
                throw new InvalidRelationException(modelClass, name, "relation type does not support touches");
            }
            names.add(name);
        }
        return Config.of(names);
    }

    private static Relation<?, ?> resolve(RelationRegistry registry, String name, Model sample,
            Class<?> modelClass) {
        try {
            return registry.resolve(name, sample);
        } catch (RelationNotFoundException e) {
            throw new InvalidRelationException(modelClass, name, "not a registered relation", e);
        } catch (UnregisteredModelException e) {
            // The registry has built every relation once, so this is not expected; kept uncached all
            // the same, as the registry does.
            throw new InvalidRelationException(modelClass, name,
                    "the related model [" + e.getModelClassName() + "] has no ModelQuery.of(...) entry", e);
        }
    }

    private static boolean isUnregistered(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnregisteredModelException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Touches the owners a model names in its {@code touches()}, then walks on into the owners
     * already loaded on it.
     *
     * <p>Each name, in order, runs its relation's {@code touch()} (skipped only for a loaded
     * {@code BelongsTo} owner already walked in this call whose key equals the current foreign
     * key): one raw {@code UPDATE} built
     * from this model's keys, skipped by the relation itself for a {@code null} foreign key, an
     * owner without timestamps or an owner class in a {@code withoutTouching} scope. When the
     * relation is loaded, each loaded owner not on the path and not walked yet in this call is
     * walked: a single owner gets its {@code saved()} hook and then its own touches, each item of
     * a collection its own touches only. An unloaded relation is never loaded. The in-memory
     * owners keep their old {@code updated_at}. A model walked already in this call, or on the
     * path, does nothing. The recursion is as deep as the loaded chain.
     *
     * <p>When the path carries a transaction, each walked owner bound to the connection that
     * transaction was opened on is bound to the transaction's factory for its {@code saved()} hook
     * and its own touches, then put back to its own binding, even when they throw. An owner bound
     * to any other connection keeps its own binding, since the transaction cannot cover it.
     *
     * @param model the model whose owners are touched, never {@code null}
     * @param path the path and done set of the outermost call, never {@code null}; the model is
     *     on the path while its owners are walked and taken off it in {@code finally}
     * @throws NullPointerException if an argument is {@code null}
     * @throws InvalidRelationException if the class's {@code touches()} fails its check
     * @throws IllegalArgumentException if a loaded relation value is not a model, a collection of
     *     models or {@code null}
     * @throws IllegalStateException if a model's class has no {@code ModelQuery} entry
     * @throws org.aristonis.diwan.database.QueryException if the database refuses an update
     */
    static void touchOwners(Model model, VisitPath path) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(path, "path");
        List<String> names = config(model).names();
        if (path.isDone(model) || path.contains(model)) {
            return;
        }
        // Done before the names check: an owner that touches nothing still gets saved() once only.
        path.markDone(model);
        if (names.isEmpty()) {
            return;
        }
        path.enter(model);
        try {
            for (String name : names) {
                touchOne(model, name, path);
            }
        } finally {
            path.leave(model);
        }
    }

    private static void touchOne(Model model, String name, VisitPath path) {
        Relation<?, ?> relation = model.relation(name);
        // Only what is loaded already, so no SELECT and no lazy-loading violation.
        if (!model.relationLoaded(name)) {
            relation.touch();
            return;
        }
        Object value = model.getRelation(name);
        List<Model> owners = VisitPath.modelsOf(value);
        // Only a BelongsTo whose loaded owner is the row of the current foreign key, and was
        // walked already in this call, skips its UPDATE. A loaded HasOne/HasMany may be partial, so
        // its UPDATE always runs.
        if (!isTouchedAlready(relation, model, value, path)) {
            relation.touch();
        }
        boolean isSingle = value instanceof Model;
        for (Model owner : owners) {
            if (path.contains(owner) || path.isDone(owner)) {
                continue;
            }
            // A collection's items get their own touches but no saved(), as in Laravel.
            walkOwner(owner, isSingle, path);
        }
    }

    // Inside a saveOrFail() transaction an owner bound to the transaction's own connection runs on
    // the transaction's factory, and gets its own binding back however the walk ends.
    private static void walkOwner(Model owner, boolean isSingle, VisitPath path) {
        EloquentAccess access = Access.eloquent();
        boolean isRebound = sharesTransaction(owner, path);
        QueryFactory prior = isRebound ? access.swapFactory(owner, path.transaction()) : null;
        try {
            if (isSingle) {
                access.fireSaved(owner);
            }
            touchOwners(owner, path);
        } finally {
            if (isRebound) {
                access.swapFactory(owner, prior);
            }
        }
    }

    // An owner on another connection keeps its own binding: the transaction cannot cover it.
    private static boolean sharesTransaction(Model owner, VisitPath path) {
        if (path.transaction() == null) {
            return false;
        }
        Connection own = owner.getQueryFactory().connection();
        return own == path.outer() || own == path.transaction().connection();
    }

    private static boolean isTouchedAlready(Relation<?, ?> relation, Model child, Object value, VisitPath path) {
        if (!(relation instanceof BelongsTo) || !(value instanceof Model) || !path.isDone((Model) value)) {
            return false;
        }
        BelongsTo<?> belongsTo = (BelongsTo<?>) relation;
        Object foreignKey = child.getAttribute(belongsTo.getForeignKeyName());
        Object ownerKey = ((Model) value).getAttribute(belongsTo.getOwnerKeyName());
        return foreignKey != null && sameKey(foreignKey, ownerKey);
    }

    // Integer and Long ids from different reads compare by value.
    private static boolean sameKey(Object a, Object b) {
        if (a instanceof Number && b instanceof Number
                && !(a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float)) {
            return ((Number) a).longValue() == ((Number) b).longValue();
        }
        return a.equals(b);
    }
}
