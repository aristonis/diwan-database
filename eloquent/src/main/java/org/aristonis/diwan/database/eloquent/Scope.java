package org.aristonis.diwan.database.eloquent;

/**
 * A query constraint that applies to every query on a model, as Laravel's {@code Scope}.
 *
 * <p>A scope is applied lazily, on a throwaway copy of the builder, at each terminal call such as
 * {@code get()}, {@code count()}, {@code update(Map)} or {@code toSql()}. It may add query clauses
 * only: eager loads, casts (from {@code withCasts} or {@code withExists}) and row hooks set by a
 * scope would be lost with the copy, so the terminal refuses them with an
 * {@link IllegalStateException} naming the scope.
 *
 * <p>One scope object is shared by every builder and every thread, so it must be stateless or
 * thread safe, pure and cheap (no I/O). It must not run a terminal, {@code toBase()},
 * {@code toSql()} or {@code getBindings()} on the builder it receives: while the scopes are being
 * applied those see the query only as far as it is scoped so far.
 *
 * <p>A scope registered without a name is keyed by its class name, so a lambda or an anonymous
 * class needs an explicit name.
 */
public interface Scope {

    /**
     * Adds this scope's clauses to a builder.
     *
     * @param builder the builder to constrain, never {@code null}
     * @param model the model the builder reads, never {@code null}
     */
    void apply(EloquentBuilder<?> builder, Model model);

    /**
     * Called once when a builder takes this scope from the registry, for scopes that need to
     * prepare the builder. The default does nothing.
     *
     * @param builder the builder that took the scope, never {@code null}
     */
    default void extend(EloquentBuilder<?> builder) {
    }
}
