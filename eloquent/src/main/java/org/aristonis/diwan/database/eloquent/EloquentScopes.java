package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.scope.Scope;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.aristonis.diwan.database.query.Builder;

/**
 * The global scopes, local scopes and soft-delete filters of {@link EloquentBuilder}.
 *
 * <p>Global scopes are held by name and applied lazily, on a copy, at every terminal call through
 * {@link #scoped()}; the wrapped query builder itself never holds a global scope's clauses. The
 * scope map is replaced, never changed in place, so a copy of this builder may share it.
 *
 * @param <T> the model class
 */
abstract class EloquentScopes<T extends Model> extends EloquentWheres<T> {

    private Map<String, Scope> scopes = Collections.emptyMap();
    private List<String> removed = Collections.emptyList();
    private boolean removedAll;
    private boolean isApplying;

    EloquentScopes(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
    }

    // ---- class scopes

    /**
     * Returns the registry key of a scope class: the fixed name for the soft-deleting scope, the
     * class name for any other.
     *
     * @param type the scope class, never {@code null}
     * @return the key
     */
    static String keyOf(Class<?> type) {
        return SoftDeletingScope.class.equals(type) ? SoftDeletingScope.NAME : type.getName();
    }

    /**
     * Takes the class's scopes: the soft-deleting scope first when the model soft deletes, then
     * the registered scopes in registration order, and calls {@code extend} on each once.
     */
    final void attachClassScopes() {
        Map<String, Scope> next = new LinkedHashMap<>();
        if (model.isSoftDeletable()) {
            next.put(SoftDeletingScope.NAME, SoftDeletingScope.INSTANCE);
        }
        AtomicReference<Map<String, Scope>> slot = source.scopeSlot();
        Map<String, Scope> registered = slot.get();
        if (registered != null) {
            next.putAll(registered);
        }
        scopes = next.isEmpty() ? Collections.<String, Scope>emptyMap() : Collections.unmodifiableMap(next);
        for (Scope scope : scopes.values()) {
            scope.extend(self());
        }
    }

    /**
     * Gives this builder the scopes and removals of another.
     *
     * @param from the builder to copy from, never {@code null}
     */
    final void copyScopesFrom(EloquentScopes<T> from) {
        scopes = from.scopes;
        removed = from.removed;
        removedAll = from.removedAll;
    }

    /**
     * Refuses an operation that needs a soft-deleting model.
     *
     * @param operation the operation, for the message
     * @throws IllegalStateException if the model does not soft delete
     */
    final void requireSoftDeletes(String operation) {
        if (!model.isSoftDeletable()) {
            throw new IllegalStateException(model.getClass().getName() + " does not use soft deletes; "
                    + operation + " needs softDeletes() to be true");
        }
    }

    abstract EloquentBuilder<T> copy();

    // ---- lazy application

    /**
     * Returns the query to run a terminal on: the wrapped query when no scope is attached, else a
     * clone with every scope applied in order. On that clone, while its scopes run, the call
     * returns the clone's query as it stands, so a scope that reads its own builder (an aggregate
     * such as {@code withCount} does) does not apply the scopes again.
     *
     * @return the query, never {@code null}
     * @throws IllegalStateException if a scope changed eager loads, casts or the row hook
     */
    final Builder scoped() {
        if (scopes.isEmpty() || isApplying) {
            return query;
        }
        EloquentBuilder<T> work = copy();
        EloquentScopes<T> workScopes = work;
        workScopes.isApplying = true;
        for (Map.Entry<String, Scope> entry : scopes.entrySet()) {
            String key = entry.getKey();
            if (!workScopes.scopes.containsKey(key)) {
                continue;
            }
            int split = work.query.getWheres().size();
            State before = new State(work);
            entry.getValue().apply(work, work.model);
            refuseStateChange(key, before, work);
            if (work.query.getWheres().size() > split) {
                work.query.groupWheresAt(split, true);
            }
        }
        return work.query;
    }

    private void refuseStateChange(String key, State before, EloquentBuilder<T> work) {
        if (!before.matches(work)) {
            throw new IllegalStateException("Global scope '" + key + "' of " + model.getClass().getName()
                    + " changed eager loads, casts or the row hook; a scope may add query clauses only");
        }
    }

    /** The state outside the base query that a scope must leave alone. */
    private static final class State {
        private final Map<String, Consumer<EloquentBuilder<?>>> eagerLoads;
        private final Object casts;
        private final Object hook;

        State(EloquentBuilder<?> work) {
            eagerLoads = work.getEagerLoads();
            casts = work.castsToMerge();
            hook = work.rowHook();
        }

        boolean matches(EloquentBuilder<?> work) {
            return eagerLoads.equals(work.getEagerLoads()) && casts == work.castsToMerge()
                    && hook == work.rowHook();
        }
    }

    // ---- global scopes

    /**
     * Adds a global scope to this builder only, replacing one of the same name in place.
     *
     * @param name the scope's name, never {@code null} or blank
     * @param scope the scope, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public final EloquentBuilder<T> withGlobalScope(String name, Scope scope) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("A global scope name must not be blank");
        }
        Map<String, Scope> next = new LinkedHashMap<>(scopes);
        next.put(name, scope);
        scopes = Collections.unmodifiableMap(next);
        return self();
    }

    /**
     * Adds a global scope given as a function, to this builder only.
     *
     * @param name the scope's name, never {@code null} or blank
     * @param scope adds the scope's clauses, never {@code null}
     * @return this builder
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public final EloquentBuilder<T> withGlobalScope(String name, Consumer<EloquentBuilder<?>> scope) {
        Objects.requireNonNull(scope, "scope");
        return withGlobalScope(name, (Scope) (builder, owner) -> scope.accept(builder));
    }

    /**
     * Stops one global scope from applying to this builder. A name that is not attached is still
     * recorded in {@link #removedScopes()}.
     *
     * @param name the scope's name, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public final EloquentBuilder<T> withoutGlobalScope(String name) {
        Objects.requireNonNull(name, "name");
        List<String> nextRemoved = new ArrayList<>(removed);
        nextRemoved.add(name);
        removed = Collections.unmodifiableList(nextRemoved);
        if (scopes.containsKey(name)) {
            Map<String, Scope> next = new LinkedHashMap<>(scopes);
            next.remove(name);
            scopes = Collections.unmodifiableMap(next);
        }
        return self();
    }

    /**
     * Stops the global scope of a class from applying to this builder.
     *
     * @param type the scope class, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public final EloquentBuilder<T> withoutGlobalScope(Class<? extends Scope> type) {
        return withoutGlobalScope(keyOf(Objects.requireNonNull(type, "type")));
    }

    /**
     * Stops the global scope of a scope object's class from applying to this builder.
     *
     * @param scope the scope, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code scope} is {@code null}
     */
    public final EloquentBuilder<T> withoutGlobalScope(Scope scope) {
        return withoutGlobalScope(keyOf(Objects.requireNonNull(scope, "scope").getClass()));
    }

    /**
     * Stops every global scope from applying to this builder.
     *
     * @return this builder
     */
    public final EloquentBuilder<T> withoutGlobalScopes() {
        removedAll = true;
        return withoutGlobalScopes(new ArrayList<>(scopes.keySet()));
    }

    /**
     * Stops the named global scopes from applying to this builder.
     *
     * @param names the scope names, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code names} or a name is {@code null}
     */
    public final EloquentBuilder<T> withoutGlobalScopes(Collection<String> names) {
        Objects.requireNonNull(names, "names");
        for (String name : new ArrayList<>(names)) {
            withoutGlobalScope(name);
        }
        return self();
    }

    /**
     * Stops every global scope except the named ones from applying to this builder.
     *
     * @param names the scope names to keep, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code names} is {@code null}
     */
    public final EloquentBuilder<T> withoutGlobalScopesExcept(Collection<String> names) {
        Objects.requireNonNull(names, "names");
        List<String> drop = new ArrayList<>(scopes.keySet());
        drop.removeAll(names);
        return withoutGlobalScopes(drop);
    }

    /**
     * Returns the names removed from this builder, in call order.
     *
     * @return a copy, with duplicates and names that were never attached kept, never {@code null}
     */
    public final List<String> removedScopes() {
        return new ArrayList<>(removed);
    }

    // ---- local scopes

    /**
     * Applies a local scope. Clauses it adds are grouped in parentheses when either side has an
     * {@code or}, as Laravel does.
     *
     * @param scope adds the clauses, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code scope} is {@code null}
     */
    public final EloquentBuilder<T> scope(Consumer<EloquentBuilder<T>> scope) {
        Objects.requireNonNull(scope, "scope");
        int split = query.getWheres().size();
        scope.accept(self());
        if (query.getWheres().size() > split) {
            self().query(base -> base.groupWheresAt(split, false));
        }
        return self();
    }

    /**
     * Applies several local scopes in order.
     *
     * @param locals the scopes, never {@code null}
     * @return this builder
     * @throws NullPointerException if {@code locals} or a scope is {@code null}
     */
    public final EloquentBuilder<T> scopes(List<Consumer<EloquentBuilder<T>>> locals) {
        for (Consumer<EloquentBuilder<T>> scope : Objects.requireNonNull(locals, "locals")) {
            scope(scope);
        }
        return self();
    }

    // ---- soft deletes

    /**
     * Includes soft-deleted rows.
     *
     * @return this builder
     * @throws IllegalStateException if the model does not soft delete
     */
    public final EloquentBuilder<T> withTrashed() {
        requireSoftDeletes("withTrashed()");
        return withoutGlobalScope(SoftDeletingScope.NAME);
    }

    /**
     * Includes soft-deleted rows, or excludes them.
     *
     * @param withTrashed {@code true} to include them, {@code false} to exclude them
     * @return this builder
     * @throws IllegalStateException if the model does not soft delete
     */
    public final EloquentBuilder<T> withTrashed(boolean withTrashed) {
        return withTrashed ? withTrashed() : withoutTrashed();
    }

    /**
     * Excludes soft-deleted rows, also after {@code withTrashed()}.
     *
     * @return this builder
     * @throws IllegalStateException if the model does not soft delete
     */
    public final EloquentBuilder<T> withoutTrashed() {
        requireSoftDeletes("withoutTrashed()");
        withoutGlobalScope(SoftDeletingScope.NAME);
        return whereNull(model.getQualifiedDeletedAtColumn());
    }

    /**
     * Returns only soft-deleted rows.
     *
     * @return this builder
     * @throws IllegalStateException if the model does not soft delete
     */
    public final EloquentBuilder<T> onlyTrashed() {
        requireSoftDeletes("onlyTrashed()");
        withoutGlobalScope(SoftDeletingScope.NAME);
        return whereNotNull(model.getQualifiedDeletedAtColumn());
    }
}
