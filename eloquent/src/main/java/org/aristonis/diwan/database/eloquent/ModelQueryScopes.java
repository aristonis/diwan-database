package org.aristonis.diwan.database.eloquent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.aristonis.diwan.database.eloquent.scope.Scope;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;

/**
 * The global scope registry of one model class: adds, reads and removes the scopes that every
 * query of the class applies. {@link ModelQuery} is the only subclass and adds the query entry
 * points on top of this registry.
 *
 * <p>The scopes live in one slot per model class, so every entry of a class shares them, as
 * described on {@link ModelQuery}.
 *
 * @param <T> the model class
 */
abstract class ModelQueryScopes<T extends Model> {

    /** The model class of this entry. */
    final Class<T> type;

    /**
     * The global scopes of the model class by key, an unmodifiable map in registration order that
     * is replaced, never changed. Each entry makes its own slot, but the slot is reached only
     * through {@link #scopeSlot()}, which finds the registered entry's, so every derived entry and
     * a losing duplicate {@link ModelQuery#of} share one.
     */
    private final AtomicReference<Map<String, Scope>> scopes =
            new AtomicReference<>(Collections.<String, Scope>emptyMap());

    ModelQueryScopes(Class<T> type) {
        this.type = type;
    }

    /**
     * Returns the global scope slot of the model class: the registered entry's, else this
     * entry's when the class has none registered.
     *
     * @return the slot, never {@code null}
     */
    AtomicReference<Map<String, Scope>> scopeSlot() {
        ModelQueryScopes<?> registered = ModelQuery.registered(type);
        return registered != null ? registered.scopes : scopes;
    }

    /**
     * Adds a global scope under its class name, or under {@link SoftDeletingScope#NAME} for the
     * soft-deleting scope. Every query of the model class applies it, and a scope with the same
     * key is replaced in place. The scope is shared by all threads, so it must be stateless or
     * thread safe.
     *
     * @param scope the scope, never {@code null}
     * @return this entry
     * @throws NullPointerException if {@code scope} is {@code null}
     * @throws IllegalArgumentException if the scope is a lambda, an anonymous class or a synthetic
     *     class, which have no stable name; use {@link #addGlobalScope(String, Scope)}
     */
    public ModelQuery<T> addGlobalScope(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return putScopes(Collections.singletonMap(keyOf(scope), scope));
    }

    /**
     * Adds a global scope under a name; a scope with the same name is replaced in place.
     *
     * @param name the key, never {@code null} or blank
     * @param scope the scope, never {@code null}
     * @return this entry
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public ModelQuery<T> addGlobalScope(String name, Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return putScopes(Collections.singletonMap(requireName(name), scope));
    }

    /**
     * Adds a global scope given as a function of the builder, under a name.
     *
     * @param name the key, never {@code null} or blank
     * @param scope the function that adds clauses to the builder, never {@code null}
     * @return this entry
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public ModelQuery<T> addGlobalScope(String name, Consumer<EloquentBuilder<?>> scope) {
        Objects.requireNonNull(scope, "scope");
        Scope wrapped = (builder, model) -> scope.accept(builder);
        return putScopes(Collections.singletonMap(requireName(name), wrapped));
    }

    /**
     * Adds several global scopes by name in one step, in the map's iteration order.
     *
     * @param scopes the scopes by name, no key or value {@code null}
     * @return this entry
     * @throws NullPointerException if the map, a name or a scope is {@code null}
     * @throws IllegalArgumentException if a name is blank
     */
    public ModelQuery<T> addGlobalScopes(Map<String, Scope> scopes) {
        Objects.requireNonNull(scopes, "scopes");
        Map<String, Scope> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Scope> entry : scopes.entrySet()) {
            checked.put(requireName(entry.getKey()), Objects.requireNonNull(entry.getValue(), "scope"));
        }
        return putScopes(checked);
    }

    /**
     * Returns whether a global scope is registered under a name.
     *
     * @param name the key, never {@code null}
     * @return whether the scope exists
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public boolean hasGlobalScope(String name) {
        return scopeSlot().get().containsKey(Objects.requireNonNull(name, "name"));
    }

    /**
     * Returns whether a global scope of a class is registered under the class's key.
     *
     * @param type the scope class, never {@code null}
     * @return whether the scope exists
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public boolean hasGlobalScope(Class<? extends Scope> type) {
        return hasGlobalScope(keyOf(type));
    }

    /**
     * Returns the global scope registered under a name.
     *
     * @param name the key, never {@code null}
     * @return the scope, or {@code null} when none is registered
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Scope getGlobalScope(String name) {
        return scopeSlot().get().get(Objects.requireNonNull(name, "name"));
    }

    /**
     * Returns the global scopes of the model class in registration order.
     *
     * @return an unmodifiable snapshot, never {@code null}
     */
    public Map<String, Scope> getGlobalScopes() {
        return scopeSlot().get();
    }

    /**
     * Removes the global scope registered under a name; unknown names are ignored.
     *
     * @param name the key, never {@code null}
     * @return this entry
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public ModelQuery<T> removeGlobalScope(String name) {
        Objects.requireNonNull(name, "name");
        return updateScopes(current -> {
            if (!current.containsKey(name)) {
                return current;
            }
            Map<String, Scope> next = new LinkedHashMap<>(current);
            next.remove(name);
            return Collections.unmodifiableMap(next);
        });
    }

    /**
     * Removes the global scope registered under a class's key; an absent scope is ignored.
     *
     * @param type the scope class, never {@code null}
     * @return this entry
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public ModelQuery<T> removeGlobalScope(Class<? extends Scope> type) {
        return removeGlobalScope(keyOf(type));
    }

    /**
     * Removes every global scope of the model class, which lets a host release scope lambdas.
     *
     * @return this entry
     */
    public ModelQuery<T> clearGlobalScopes() {
        return updateScopes(current -> Collections.<String, Scope>emptyMap());
    }

    private ModelQuery<T> putScopes(Map<String, Scope> added) {
        return updateScopes(current -> {
            Map<String, Scope> next = new LinkedHashMap<>(current);
            next.putAll(added);
            return Collections.unmodifiableMap(next);
        });
    }

    private ModelQuery<T> updateScopes(UnaryOperator<Map<String, Scope>> change) {
        AtomicReference<Map<String, Scope>> slot = scopeSlot();
        Map<String, Scope> current;
        Map<String, Scope> next;
        do {
            current = slot.get();
            next = change.apply(current);
        } while (next != current && !slot.compareAndSet(current, next));
        return self();
    }

    @SuppressWarnings("unchecked")
    private ModelQuery<T> self() {
        return (ModelQuery<T>) this;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("A global scope name must not be blank");
        }
        return name;
    }

    private static String keyOf(Class<? extends Scope> type) {
        return EloquentScopes.keyOf(Objects.requireNonNull(type, "type"));
    }

    private static String keyOf(Scope scope) {
        Class<? extends Scope> type = scope.getClass();
        if (type == SoftDeletingScope.class) {
            return SoftDeletingScope.NAME;
        }
        if (type.isAnonymousClass() || type.isSynthetic() || type.getSimpleName().isEmpty()
                || type.getName().contains("$$Lambda")) {
            throw new IllegalArgumentException("A lambda or anonymous scope has no stable name; "
                    + "use addGlobalScope(name, scope)");
        }
        return type.getName();
    }
}
