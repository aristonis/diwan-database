package org.aristonis.diwan.database.eloquent.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * What the relation package needs from the model package's package-private members.
 *
 * <p>Implemented once, in the model package, and registered by {@code ModelQuery}'s static
 * initialiser through {@link Access#setEloquent(EloquentAccess)}.
 *
 * <p>Internal: not API.
 *
 * @implSpec An implementation keeps no state of its own and is safe to call from any thread; the
 *     builders and models it is given are not shared, so it needs no lock.
 */
public interface EloquentAccess {

    /**
     * Wraps models in a collection that knows the model type of a builder.
     *
     * @param <T> the model type
     * @param source the builder whose model type the collection takes, never {@code null}
     * @param models the models, never {@code null}, copied
     * @return a new collection, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    <T extends Model> ModelCollection<T> newCollection(EloquentBuilder<T> source, List<T> models);

    /**
     * Copies a builder with its wheres, eager loads and an independent alias counter.
     *
     * @param <T> the model type
     * @param builder the builder, never {@code null}
     * @return a new builder, never {@code null}
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    <T extends Model> EloquentBuilder<T> copy(EloquentBuilder<T> builder);

    /**
     * Copies a builder without its eager loads.
     *
     * @param <T> the model type
     * @param builder the builder, never {@code null}
     * @return a new builder with no eager loads, never {@code null}
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    <T extends Model> EloquentBuilder<T> withoutEagerLoads(EloquentBuilder<T> builder);

    /**
     * Makes a subquery number its self-relation aliases with the counter of its outer builder.
     *
     * @param from the outer builder, never {@code null}
     * @param to the subquery builder, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    void shareAliases(EloquentBuilder<?> from, EloquentBuilder<?> to);

    /**
     * Returns a builder's alias counter, creating it on first use.
     *
     * @param builder the builder, never {@code null}
     * @return the counter, never {@code null}
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    AliasCounter aliases(EloquentBuilder<?> builder);

    /**
     * Makes a model report a table alias as its table, so its qualified columns use the alias.
     *
     * @param model the model, never {@code null}
     * @param alias the alias, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    void useTableAlias(Model model, String alias);

    /**
     * Tells whether a model was marked to refuse lazy loading when it was hydrated.
     *
     * @param model the model, never {@code null}
     * @return {@code true} if the model refuses lazy loading
     * @throws NullPointerException if {@code model} is {@code null}
     */
    boolean isLazyLoadingPrevented(Model model);

    /**
     * Parses eager load paths such as {@code "comments.author"} and {@code "author:id,name"}.
     *
     * @param relations the paths, none {@code null}
     * @return the constraints by path, parents before children, in call order, modifiable
     * @throws NullPointerException if a path is {@code null}
     * @throws IllegalArgumentException if a {@code name:} path lists no column
     */
    Map<String, Consumer<EloquentBuilder<?>>> parseWith(String... relations);

    /**
     * Creates a bare model of the same class and connection as a model, with no attributes.
     *
     * @param model the model to copy the class and connection of, never {@code null}
     * @return a new model of the same class, never {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     */
    Model newInstanceLike(Model model);

    /**
     * Returns the relation registry slot of a model's class: the {@code ModelQuery} entry's
     * reference, read through {@code ModelQuery.registered(model.getClass())}, written once.
     *
     * @apiNote The slot is typed {@code Object} because the registry type is package-private in
     *     the relation package; only that package reads or writes it.
     * @param model a model of the class, never {@code null}
     * @return the slot, never {@code null}; holds {@code null} until the first check
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalStateException if the model's class has no {@code ModelQuery} entry
     */
    AtomicReference<Object> relationRegistrySlot(Model model);

    /**
     * Returns the map a model declares in its protected {@code relations()} method.
     *
     * @param model the model, never {@code null}
     * @return the map, never {@code null}
     * @throws NullPointerException if {@code model} or the map it returns is {@code null}
     */
    RelationMap<?> relationMap(Model model);

    /**
     * Starts a query on the entry of a model's class, on the model's connection when the model
     * is bound to one, as a relation's related query does.
     *
     * @param <T> the model type
     * @param model the model whose class and connection the query takes, never {@code null}
     * @return a new builder, never {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalStateException if the model's class has no {@code ModelQuery} entry
     */
    <T extends Model> EloquentBuilder<T> newQueryOn(T model);

    /**
     * Returns the relation names a model declares in its protected {@code touches()} method.
     *
     * @param model the model, never {@code null}
     * @return the names, never {@code null}
     * @throws NullPointerException if {@code model} or the set it returns is {@code null}
     */
    Set<String> touches(Model model);

    /**
     * Returns the {@code touches()} cache slot of a model's class: the {@code ModelQuery} entry's
     * second reference, read through {@code ModelQuery.registered(model.getClass())}, written once.
     *
     * @apiNote The slot is typed {@code Object} because the configuration type is package-private
     *     in the relation package; only that package reads or writes it.
     * @param model a model of the class, never {@code null}
     * @return the slot, never {@code null}; holds {@code null} until the first check
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalStateException if the model's class has no {@code ModelQuery} entry
     */
    AtomicReference<Object> touchesSlot(Model model);

    /**
     * Runs a model's protected {@code saved()} hook.
     *
     * @param model the model, never {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     */
    void fireSaved(Model model);

    /**
     * Replaces the query factory a model is bound to and returns the one it had, so a caller can
     * put it back in {@code finally}. A {@code null} factory leaves the model to its resolver.
     *
     * @param model the model, never {@code null}
     * @param next the factory to bind, or {@code null} for none
     * @return the factory bound before, or {@code null} when there was none
     * @throws NullPointerException if {@code model} is {@code null}
     */
    QueryFactory swapFactory(Model model, QueryFactory next);

    /**
     * Returns the casts a builder's {@code withCasts} registered.
     *
     * @param builder the builder, never {@code null}
     * @return the casts by column, unmodifiable, empty when none, never {@code null}
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    Map<String, Cast<?>> castsToMerge(EloquentBuilder<?> builder);
}
