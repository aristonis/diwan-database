package org.aristonis.diwan.database.eloquent.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/**
 * The immutable settings of one factory: its model entry, base definition, state layers, count,
 * hooks, connection and relation settings.
 *
 * @param <M> the model type
 */
final class FactorySpec<M extends Model> {

    private static final int DEFAULT_COUNT = 1;

    private final ModelQuery<M> query;
    private final Supplier<Map<String, Object>> definition;
    private final List<State> states;
    private final Integer count;
    private final List<BiConsumer<M, Model>> afterMaking;
    private final List<BiConsumer<M, Model>> afterCreating;
    private final Connection connection;
    private final FactoryRelations relations;

    /**
     * One layer over the attributes built so far.
     */
    interface State {

        /**
         * Returns the attributes this layer sets.
         *
         * @param spec the settings of the factory being run
         * @param soFar a read-only snapshot of the attributes built so far
         * @param index the position of the model, from zero
         * @param parent the model the models of this run are created under, or {@code null} at the
         *     top level
         * @return the attributes to put over {@code soFar}
         */
        Map<String, Object> apply(FactorySpec<?> spec, Map<String, Object> soFar, int index, Model parent);
    }

    private FactorySpec(
            ModelQuery<M> query,
            Supplier<Map<String, Object>> definition,
            List<State> states,
            Integer count,
            List<BiConsumer<M, Model>> afterMaking,
            List<BiConsumer<M, Model>> afterCreating,
            Connection connection,
            FactoryRelations relations) {
        this.query = Objects.requireNonNull(query, "query");
        this.definition = definition;
        this.states = frozen(states);
        this.count = count;
        this.afterMaking = frozen(afterMaking);
        this.afterCreating = frozen(afterCreating);
        this.connection = connection;
        this.relations = Objects.requireNonNull(relations, "relations");
    }

    /**
     * Starts the settings of a factory with no layers, hooks, count or connection.
     *
     * @param query the model entry that builds the models, never {@code null}
     * @param definition the supplier of the base attributes, or {@code null} until one is bound
     * @param <M> the model type
     * @return the settings, never {@code null}
     * @throws NullPointerException if {@code query} is {@code null}
     */
    static <M extends Model> FactorySpec<M> start(ModelQuery<M> query, Supplier<Map<String, Object>> definition) {
        return new FactorySpec<>(query, definition, Collections.emptyList(), null, Collections.emptyList(),
                Collections.emptyList(), null, FactoryRelations.none());
    }

    /**
     * Returns the model entry that builds the models.
     *
     * @return the entry, never {@code null}
     */
    ModelQuery<M> query() {
        return query;
    }

    /**
     * Returns the supplier of the base attributes.
     *
     * @return the supplier, never {@code null} once a definition is bound
     */
    Supplier<Map<String, Object>> definition() {
        return Objects.requireNonNull(definition, "definition");
    }

    /**
     * Returns whether a definition is bound.
     *
     * @return {@code true} when the base attributes have a supplier
     */
    boolean hasDefinition() {
        return definition != null;
    }

    /**
     * Returns the state layers, applied in order after the definition.
     *
     * @return an unmodifiable list, never {@code null}
     */
    List<State> states() {
        return states;
    }

    /**
     * Returns the hooks run on each made model.
     *
     * @return an unmodifiable list, never {@code null}
     */
    List<BiConsumer<M, Model>> afterMaking() {
        return afterMaking;
    }

    /**
     * Returns the hooks run on each created model.
     *
     * @return an unmodifiable list, never {@code null}
     */
    List<BiConsumer<M, Model>> afterCreating() {
        return afterCreating;
    }

    /**
     * Returns these settings with a bound definition.
     *
     * @param newDefinition the supplier of the base attributes, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code newDefinition} is {@code null}
     */
    FactorySpec<M> withDefinition(Supplier<Map<String, Object>> newDefinition) {
        return new FactorySpec<>(query, Objects.requireNonNull(newDefinition, "definition"), states, count,
                afterMaking, afterCreating, connection, relations);
    }

    /**
     * Returns these settings with one more state layer.
     *
     * @param state the layer, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    FactorySpec<M> withState(State state) {
        return new FactorySpec<>(query, definition, added(states, Objects.requireNonNull(state, "state")), count,
                afterMaking, afterCreating, connection, relations);
    }

    /**
     * Returns these settings with another count.
     *
     * @param newCount the number of models, below 1 for none
     * @return the new settings, never {@code null}
     */
    FactorySpec<M> withCount(int newCount) {
        return new FactorySpec<>(query, definition, states, newCount, afterMaking, afterCreating, connection, relations);
    }

    /**
     * Returns these settings with one more hook run after a model is made.
     *
     * @param hook the hook, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code hook} is {@code null}
     */
    FactorySpec<M> withAfterMaking(BiConsumer<M, Model> hook) {
        return new FactorySpec<>(query, definition, states, count,
                added(afterMaking, Objects.requireNonNull(hook, "hook")), afterCreating, connection, relations);
    }

    /**
     * Returns these settings with one more hook run after a model is created.
     *
     * @param hook the hook, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code hook} is {@code null}
     */
    FactorySpec<M> withAfterCreating(BiConsumer<M, Model> hook) {
        return new FactorySpec<>(query, definition, states, count, afterMaking,
                added(afterCreating, Objects.requireNonNull(hook, "hook")), connection, relations);
    }

    /**
     * Returns these settings with no after-making hook.
     *
     * @return the new settings, never {@code null}
     */
    FactorySpec<M> withoutAfterMaking() {
        return new FactorySpec<>(query, definition, states, count, Collections.emptyList(), afterCreating,
                connection, relations);
    }

    /**
     * Returns these settings with no after-creating hook.
     *
     * @return the new settings, never {@code null}
     */
    FactorySpec<M> withoutAfterCreating() {
        return new FactorySpec<>(query, definition, states, count, afterMaking, Collections.emptyList(),
                connection, relations);
    }

    /**
     * Returns these settings bound to a connection.
     *
     * @param newConnection the connection, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code newConnection} is {@code null}
     */
    FactorySpec<M> withConnection(Connection newConnection) {
        return new FactorySpec<>(query, definition, states, count, afterMaking, afterCreating,
                Objects.requireNonNull(newConnection, "connection"), relations);
    }

    /**
     * Returns the relation settings: links, recycle pool and excluded parents.
     *
     * @return the settings, never {@code null}
     */
    FactoryRelations relations() {
        return relations;
    }

    /**
     * Returns these settings with other relation settings.
     *
     * @param newRelations the relation settings, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code newRelations} is {@code null}
     */
    FactorySpec<M> withRelations(FactoryRelations newRelations) {
        return new FactorySpec<>(query, definition, states, count, afterMaking, afterCreating, connection,
                Objects.requireNonNull(newRelations, "relations"));
    }

    /**
     * Returns the connection set on these settings.
     *
     * @return the connection, or {@code null} when none was set
     */
    Connection connection() {
        return connection;
    }

    /**
     * Returns whether a count was set.
     *
     * @return {@code true} when {@link #withCount(int)} was called
     */
    boolean hasCount() {
        return count != null;
    }

    /**
     * Returns these settings bound to an outer connection unless they already have a connection of
     * their own.
     *
     * @param outer the connection of the factory that nests this one, or {@code null} for none
     * @return these settings when they have a connection or {@code outer} is {@code null},
     *     otherwise the new settings, never {@code null}
     */
    FactorySpec<M> withConnectionIfAbsent(Connection outer) {
        if (connection != null || outer == null) {
            return this;
        }
        return withConnection(outer);
    }

    /**
     * Returns the model entry to build with, on the set connection when there is one.
     *
     * @return the entry, never {@code null}
     */
    ModelQuery<M> effectiveQuery() {
        return connection == null ? query : query.on(connection);
    }

    /**
     * Returns the requested count, or one when none was set.
     *
     * @return the count, below 1 for none
     */
    int effectiveCount() {
        return count == null ? DEFAULT_COUNT : count;
    }

    private static <T> List<T> frozen(List<T> items) {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    private static <T> List<T> added(List<T> items, T item) {
        List<T> out = new ArrayList<>(items);
        out.add(item);
        return out;
    }
}
