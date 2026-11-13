package org.aristonis.diwan.database.eloquent.factory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;

/**
 * Builds models, or raw attribute rows, from a base definition and layered states.
 *
 * <p>Build one with {@link #of(ModelQuery, Supplier)}, or extend this class and implement
 * {@link #definition()}; a subclass names its states in its own methods that return
 * {@code Factory<M>}. Every fluent call returns a new factory and leaves the receiver unchanged,
 * so a factory is immutable and safe to share across threads.
 *
 * <p>The definition and each state are maps from attribute names to values. A value that is a
 * {@link Supplier} is called with no argument, and a value that is a {@link java.util.function.Function}
 * is called with a read-only snapshot of the attributes built so far; the result is used as is and
 * is not resolved again, except that a result that is a factory or a model is expanded as a nested
 * value. A {@link ClassCastException} thrown while a value is resolved is rethrown
 * as an {@link IllegalArgumentException} that names the attribute and the model class, never the
 * value, with the original as its cause.
 *
 * <p>{@code make}, {@code create}, {@code createQuietly} and {@code raw} build exactly one and
 * ignore {@link #count(int)}. The {@code *Many()} methods and {@code insert()} build as many as
 * {@code count} says, or one when it was not set; the variants taking a number use that number.
 * Each model's position starts at zero on every call.
 *
 * <p>Diwan has no fake data: every value comes from the definition, a state or a sequence.
 * {@code create} throws when a save is stopped or stores no row, where
 * {@link ModelQuery#create(Map)} returns the unsaved model. {@code insert} sends one statement, so
 * the binding limit of the database applies (999 on SQLite before 3.32), and rows with different
 * key sets make it throw an {@link IllegalArgumentException}.
 *
 * <p>Relations: {@link #has(Factory, String)} and {@link #hasAttached(Factory, String)} create
 * children after each created model is saved, and {@link #forParent(Factory, String)} gives a model
 * its parent first. A value in the attributes that is a factory creates its model and stores the key,
 * and a value that is a model stores its key. {@link #recycle(Model...)} lets a call reuse saved
 * models instead of creating parents, and {@link #withoutParents()} turns nested factory values
 * into {@code null}. Every terminal call builds one run context that holds the recycle position and
 * the random generator, so two calls on one factory share nothing and the factory stays immutable.
 * {@code make} creates the parents but never the children, {@code insert} creates the parents and
 * skips the children and the hooks, and {@code raw} never touches the database. There is no
 * transaction: rows already written stay when a later one fails.
 *
 * @param <M> the model type
 */
public abstract class Factory<M extends Model> {

    private final FactorySpec<M> spec;

    /**
     * Creates a factory for the models of an entry, with its own {@link #definition()}.
     *
     * @param query the model entry, never {@code null}
     * @throws NullPointerException if {@code query} is {@code null}
     */
    protected Factory(ModelQuery<M> query) {
        Objects.requireNonNull(query, "query");
        this.spec = FactorySpec.start(query, null);
    }

    private Factory(FactorySpec<M> spec) {
        this.spec = spec;
    }

    /**
     * Returns the base attributes of one model, called once per model.
     *
     * @return the attributes, in the order they are set, never {@code null}
     */
    protected abstract Map<String, Object> definition();

    /**
     * Returns a factory whose base attributes come from a supplier.
     *
     * @param query the model entry, never {@code null}
     * @param definition the supplier of the base attributes, called once per model, never
     *     {@code null}
     * @param <M> the model type
     * @return the factory, never {@code null}
     * @throws NullPointerException if {@code query} or {@code definition} is {@code null}
     */
    public static <M extends Model> Factory<M> of(ModelQuery<M> query, Supplier<Map<String, Object>> definition) {
        return new Fixed<>(FactorySpec.start(query, definition));
    }

    /**
     * Returns a factory with a state that puts attributes over the earlier ones.
     *
     * @param attributes the attributes, copied, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public Factory<M> state(Map<String, Object> attributes) {
        Map<String, Object> copy = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes")));
        return withSpec(bound().withState((s, soFar, index, parent) -> copy));
    }

    /**
     * Returns a factory with a state computed from the attributes built so far.
     *
     * @param state the function, given a read-only snapshot and returning the attributes to put
     *     over it, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public Factory<M> state(UnaryOperator<Map<String, Object>> state) {
        Objects.requireNonNull(state, "state");
        return withSpec(bound().withState((s, soFar, index, parent) -> state.apply(soFar)));
    }

    /**
     * Returns a factory with one attribute set over the earlier ones.
     *
     * @param name the attribute name, never {@code null}
     * @param value the value, a {@link Supplier} or {@link java.util.function.Function} is resolved,
     *     may be {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Factory<M> set(String name, Object value) {
        Objects.requireNonNull(name, "name");
        return withSpec(bound().withState((s, soFar, index, parent) -> Collections.singletonMap(name, value)));
    }

    /**
     * Returns a factory whose models take their attributes from a sequence.
     *
     * @param sequence the sequence, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code sequence} is {@code null}
     */
    public Factory<M> sequence(Sequence sequence) {
        Objects.requireNonNull(sequence, "sequence");
        return withSpec(bound().withState((s, soFar, index, parent) -> sequence.at(index)));
    }

    /**
     * Returns a factory whose models cycle through fixed entries.
     *
     * @param entries the entries, as for {@link Sequence#of(Map[])}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code entries} or an entry is {@code null}
     * @throws IllegalArgumentException if there are no entries
     */
    @SafeVarargs
    public final Factory<M> sequence(Map<String, Object>... entries) {
        Objects.requireNonNull(entries, "entries");
        List<Map<String, Object>> copy = new ArrayList<>(entries.length);
        for (Map<String, Object> entry : entries) {
            copy.add(entry);
        }
        return sequence(fixedSequence(copy));
    }

    /**
     * Returns a factory that cycles through fixed entries and builds one model for each.
     *
     * @param entries the entries, as for {@link Sequence#of(Map[])}
     * @return a new factory with its count set to the number of entries, never {@code null}
     * @throws NullPointerException if {@code entries} or an entry is {@code null}
     * @throws IllegalArgumentException if there are no entries
     */
    @SafeVarargs
    public final Factory<M> forEachSequence(Map<String, Object>... entries) {
        return sequence(entries).count(entries.length);
    }

    private static Sequence fixedSequence(List<Map<String, Object>> entries) {
        if (entries.isEmpty()) {
            return Sequence.of();
        }
        Sequence sequence = Sequence.of(entries.get(0));
        for (int i = 1; i < entries.size(); i++) {
            sequence = sequence.and(entries.get(i));
        }
        return sequence;
    }

    /**
     * Returns a factory whose models are soft-deleted one day before now, by the model's clock.
     * The check that the model soft-deletes runs when a model is built.
     *
     * @return a new factory, never {@code null}
     */
    public Factory<M> trashed() {
        return trashed(null);
    }

    /**
     * Returns a factory whose models are soft-deleted at a moment, or one day before now when the
     * moment is {@code null}. The check that the model soft-deletes runs when a model is built.
     *
     * @param moment the moment of deletion, or {@code null} for one day before now
     * @return a new factory, never {@code null}
     */
    public Factory<M> trashed(Instant moment) {
        return withSpec(bound().withState(FactoryRows.trashedState(moment)));
    }

    /**
     * Returns a factory with a count for the {@code *Many()} methods and {@code insert()}.
     *
     * @param count the number of models, below 1 for none
     * @return a new factory, never {@code null}
     */
    public Factory<M> count(int count) {
        return withSpec(bound().withCount(count));
    }

    /**
     * Returns a factory with a hook run on each made model, after the earlier hooks.
     *
     * @param hook the hook, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code hook} is {@code null}
     */
    public Factory<M> afterMaking(Consumer<M> hook) {
        Objects.requireNonNull(hook, "hook");
        return withSpec(bound().withAfterMaking((model, parent) -> hook.accept(model)));
    }

    /**
     * Returns a factory with a hook run on each created model once it is saved, after the earlier
     * hooks.
     *
     * @param hook the hook, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code hook} is {@code null}
     */
    public Factory<M> afterCreating(Consumer<M> hook) {
        Objects.requireNonNull(hook, "hook");
        return withSpec(bound().withAfterCreating((model, parent) -> hook.accept(model)));
    }

    /**
     * Returns a factory with no after-making hook.
     *
     * @return a new factory, never {@code null}
     */
    public Factory<M> withoutAfterMaking() {
        return withSpec(bound().withoutAfterMaking());
    }

    /**
     * Returns a factory with no after-creating hook.
     *
     * @return a new factory, never {@code null}
     */
    public Factory<M> withoutAfterCreating() {
        return withSpec(bound().withoutAfterCreating());
    }

    /**
     * Returns a factory whose models use a connection.
     *
     * @param connection the connection, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code connection} is {@code null}
     */
    public Factory<M> connection(Connection connection) {
        return withSpec(bound().withConnection(connection));
    }

    /**
     * Returns a factory that creates children under each created model, through a has-one,
     * has-many, morph-one or morph-many relation.
     *
     * <p>The children are created after the model is saved, with the key column (and the type column
     * of a morph relation) set last, so it wins over the child's own states. The child factory keeps
     * its own count, states and hooks, and its parent-aware states and hooks receive the saved model.
     * Nothing is created by {@code make}, {@code insert} or {@code raw}.
     *
     * @param child the factory of the children, never {@code null}
     * @param relation the relation name on this model, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException when a create call runs, if the relation is not one of the four kinds
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException when a create
     *     call runs, if the model has no relation of that name
     * @throws IllegalStateException when a create call runs, if the saved model has no value for the
     *     local key
     */
    public Factory<M> has(Factory<? extends Model> child, String relation) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(relation, "relation");
        return relate(r -> r.withHas(HasLink.has(child, relation)));
    }

    /**
     * Returns a factory that creates related models under each created model and attaches them
     * through a many-to-many relation with no pivot values.
     *
     * @param related the factory of the related models, never {@code null}
     * @param relation the many-to-many relation name on this model, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException when a create call runs, if the relation is not many-to-many
     * @throws IllegalStateException when a create call runs, if the saved model has no key
     */
    public Factory<M> hasAttached(Factory<? extends Model> related, String relation) {
        Objects.requireNonNull(related, "related");
        Objects.requireNonNull(relation, "relation");
        return relate(r -> r.withHas(HasLink.attachedFactory(related, relation)));
    }

    /**
     * Returns a factory that creates related models and attaches each with the same pivot values.
     *
     * @param related the factory of the related models, never {@code null}
     * @param pivot the pivot values, copied and passed as given, never {@code null}
     * @param relation the many-to-many relation name on this model, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException when a create call runs, if the relation is not many-to-many
     * @throws IllegalStateException when a create call runs, if the saved model has no key
     */
    public Factory<M> hasAttached(Factory<? extends Model> related, Map<String, Object> pivot, String relation) {
        Objects.requireNonNull(related, "related");
        Objects.requireNonNull(relation, "relation");
        return relate(r -> r.withHas(HasLink.attachedFactory(related, pivot, relation)));
    }

    /**
     * Returns a factory that creates related models and attaches each with pivot values computed
     * from the saved model, once per related model.
     *
     * @param related the factory of the related models, never {@code null}
     * @param pivot the function from the saved model to the pivot values; a {@code null} result
     *     means none, never {@code null} itself
     * @param relation the many-to-many relation name on this model, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException when a create call runs, if the relation is not many-to-many
     * @throws IllegalStateException when a create call runs, if the saved model has no key
     */
    public Factory<M> hasAttached(
            Factory<? extends Model> related, Function<Model, Map<String, Object>> pivot, String relation) {
        Objects.requireNonNull(related, "related");
        Objects.requireNonNull(relation, "relation");
        return relate(r -> r.withHas(HasLink.attachedFactory(related, pivot, relation)));
    }

    /**
     * Returns a factory that creates related models and attaches the n-th with the n-th pivot
     * entry, cycling the list when there are more models than entries. When the related factory has
     * no count, its count becomes the size of the list.
     *
     * @param related the factory of the related models, never {@code null}
     * @param pivots the pivot entries, each copied, never {@code null} and never empty
     * @param relation the many-to-many relation name on this model, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code pivots} is empty, or when a create call runs, if
     *     the relation is not many-to-many
     * @throws IllegalStateException when a create call runs, if the saved model has no key
     */
    public Factory<M> hasAttached(
            Factory<? extends Model> related, List<Map<String, Object>> pivots, String relation) {
        Objects.requireNonNull(related, "related");
        Objects.requireNonNull(pivots, "pivots");
        Objects.requireNonNull(relation, "relation");
        Factory<? extends Model> sized = related.bound().hasCount() || pivots.isEmpty()
                ? related : related.count(pivots.size());
        return relate(r -> r.withHas(HasLink.attachedFactory(sized, pivots, relation)));
    }

    /**
     * Returns a factory that attaches models that already exist, all with the same pivot values,
     * once per created model.
     *
     * @param existing the saved models to attach, copied, never {@code null}
     * @param pivot the pivot values, copied and passed as given, never {@code null}
     * @param relation the many-to-many relation name on this model, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException when a create call runs, if the relation is not many-to-many
     *     or an attached model is not saved
     * @throws IllegalStateException when a create call runs, if the saved model has no key
     */
    public Factory<M> hasAttached(List<? extends Model> existing, Map<String, Object> pivot, String relation) {
        Objects.requireNonNull(relation, "relation");
        return relate(r -> r.withHas(HasLink.attachedExisting(existing, pivot, relation)));
    }

    /**
     * Returns a factory whose models belong to a parent that a factory creates, through a belongs-to
     * or morph-to relation. This is the relation's "for", named {@code forParent} because {@code for}
     * is a Java keyword.
     *
     * <p>The parent is created before the model, once for the whole call whatever the count, unless
     * the recycle pool holds a model of its class. The key column, and the type column of a morph-to
     * relation, are set before the states, so a state can override them. A recycled model must be
     * saved. {@code raw} throws, because it never touches the database.
     *
     * @param parent the factory of the parent, never {@code null}
     * @param relation the belongs-to or morph-to relation name on this model, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException when a terminal call runs, if the relation is not belongs-to
     *     or morph-to
     * @throws IllegalStateException when {@code raw} or {@code rawMany} runs
     */
    public Factory<M> forParent(Factory<? extends Model> parent, String relation) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(relation, "relation");
        return relate(r -> r.withFor(ForLink.of(parent, relation)));
    }

    /**
     * Returns a factory whose models belong to a parent that is already saved, through a belongs-to
     * or morph-to relation. The model keeps its own connection, and {@code raw} accepts it.
     *
     * @param parent the saved parent, never {@code null}
     * @param relation the belongs-to or morph-to relation name on this model, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException when a terminal call runs, if the relation is not belongs-to
     *     or morph-to
     * @throws IllegalStateException when a terminal call runs, if the parent is not saved
     */
    public Factory<M> forParent(Model parent, String relation) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(relation, "relation");
        return relate(r -> r.withFor(ForLink.of(parent, relation)));
    }

    /**
     * Returns a factory with a state computed from the attributes built so far and from the parent.
     *
     * @param state the function, given a read-only snapshot and the model the run creates this one
     *     under, or {@code null} at the top level, and returning the attributes to put over it,
     *     never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public Factory<M> state(BiFunction<Map<String, Object>, Model, Map<String, Object>> state) {
        Objects.requireNonNull(state, "state");
        return withSpec(bound().withState((s, soFar, index, parent) -> state.apply(soFar, parent)));
    }

    /**
     * Returns a factory with a hook run on each made model and its parent, after the earlier hooks.
     *
     * @param hook the hook, given the model and the model it is created under or {@code null} at the
     *     top level, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code hook} is {@code null}
     */
    public Factory<M> afterMaking(BiConsumer<M, Model> hook) {
        return withSpec(bound().withAfterMaking(hook));
    }

    /**
     * Returns a factory with a hook run on each created model and its parent once it is saved and
     * its children exist, after the earlier hooks.
     *
     * @param hook the hook, given the model and the model it is created under or {@code null} at the
     *     top level, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code hook} is {@code null}
     */
    public Factory<M> afterCreating(BiConsumer<M, Model> hook) {
        return withSpec(bound().withAfterCreating(hook));
    }

    /**
     * Returns a factory that reuses saved models instead of creating parents and nested values of
     * their exact class. Models are added to the pool, one entry per instance.
     *
     * <p>Only the pool of the factory a terminal call is made on counts, and a recycled model must
     * be saved or the call throws {@link IllegalStateException}.
     *
     * @param models the saved models, never {@code null} and without {@code null} entries
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code models} or an entry is {@code null}
     */
    public Factory<M> recycle(Model... models) {
        Objects.requireNonNull(models, "models");
        List<Model> copy = new ArrayList<>(Arrays.asList(models));
        return recycle(copy);
    }

    /**
     * Returns a factory that reuses saved models instead of creating parents and nested values of
     * their exact class. Models are added to the pool, one entry per instance.
     *
     * @param models the saved models, never {@code null} and without {@code null} entries; an empty
     *     list changes nothing
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code models} or an entry is {@code null}
     * @see #recycle(Model...)
     */
    public Factory<M> recycle(List<? extends Model> models) {
        return relate(r -> r.withRecycled(models));
    }

    /**
     * Returns a factory that picks recycled models in a mode, keeping a seed that is already set.
     *
     * @param mode the mode, {@link RecycleMode#IN_TURN} by default, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code mode} is {@code null}
     */
    public Factory<M> recycleMode(RecycleMode mode) {
        return relate(r -> r.withMode(mode));
    }

    /**
     * Returns a factory that picks recycled models at random, with no seed, so two calls differ.
     * A seed set earlier is cleared.
     *
     * @return a new factory, never {@code null}
     */
    public Factory<M> recycleRandom() {
        return relate(r -> r.withRandom(null));
    }

    /**
     * Returns a factory that picks recycled models at random from a seed, so two calls on it pick
     * the same models.
     *
     * @param seed the seed of the generator
     * @return a new factory, never {@code null}
     */
    public Factory<M> recycleRandom(long seed) {
        return relate(r -> r.withRandom(seed));
    }

    /**
     * Returns a factory whose nested factory values become {@code null} and create nothing. A
     * parent given to {@code forParent} and a model value are not affected.
     *
     * @return a new factory, never {@code null}
     */
    public Factory<M> withoutParents() {
        return relate(FactoryRelations::withExcludedAll);
    }

    /**
     * Returns a factory whose nested factory values that build one of these exact model classes
     * become {@code null} and create nothing. Repeated calls add to the earlier ones.
     *
     * @param models the exact model classes, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code models} or an entry is {@code null}
     */
    @SafeVarargs
    public final Factory<M> withoutParents(Class<? extends Model>... models) {
        Objects.requireNonNull(models, "models");
        List<Class<? extends Model>> copy = new ArrayList<>(models.length);
        for (Class<? extends Model> modelClass : models) {
            copy.add(modelClass);
        }
        return relate(r -> r.withExcludedClasses(copy));
    }

    /**
     * Returns a factory whose nested factory values held by these attributes become {@code null}
     * and create nothing. Repeated calls add to the earlier ones.
     *
     * @param attributeNames the attribute names, never {@code null}
     * @return a new factory, never {@code null}
     * @throws NullPointerException if {@code attributeNames} or an entry is {@code null}
     */
    public final Factory<M> withoutParents(String... attributeNames) {
        Objects.requireNonNull(attributeNames, "attributeNames");
        List<String> copy = new ArrayList<>(Arrays.asList(attributeNames));
        return relate(r -> r.withExcludedNames(copy));
    }

    /**
     * Makes one unsaved model, ignoring the count. The parents of {@code forParent} and nested
     * factory values are created and saved, but no child of {@code has} or {@code hasAttached} is.
     *
     * @return the model, never {@code null}
     */
    public M make() {
        return makeList(1).get(0);
    }

    /**
     * Makes as many unsaved models as the count says, or one.
     *
     * @return a new mutable list the caller owns, empty when the count is below 1
     */
    public List<M> makeMany() {
        return makeList(bound().effectiveCount());
    }

    /**
     * Makes a number of unsaved models.
     *
     * @param count the number of models, below 1 for none
     * @return a new mutable list the caller owns, empty when {@code count} is below 1
     */
    public List<M> makeMany(int count) {
        return makeList(count);
    }

    /**
     * Makes, saves and returns one model, ignoring the count.
     *
     * @return the model, never {@code null}
     * @throws IllegalStateException if a model event hook stopped the save or no row was stored
     */
    public M create() {
        return createList(1).get(0);
    }

    /**
     * Creates as many models as the count says, or one.
     *
     * @return a new mutable list the caller owns, empty when the count is below 1
     * @throws IllegalStateException if a model event hook stopped a save or no row was stored
     */
    public List<M> createMany() {
        return createList(bound().effectiveCount());
    }

    /**
     * Creates a number of models.
     *
     * @param count the number of models, below 1 for none
     * @return a new mutable list the caller owns, empty when {@code count} is below 1
     * @throws IllegalStateException if a model event hook stopped a save or no row was stored
     */
    public List<M> createMany(int count) {
        return createList(count);
    }

    /**
     * Creates one model with the registered listeners silenced, ignoring the count.
     *
     * @return the model, never {@code null}
     * @throws IllegalStateException if a model's own hook stopped the save or no row was stored
     */
    public M createQuietly() {
        return createListQuietly(1).get(0);
    }

    /**
     * Creates as many models as the count says, or one, with the registered listeners silenced
     * for the whole batch.
     *
     * @return a new mutable list the caller owns, empty when the count is below 1
     * @throws IllegalStateException if a model's own hook stopped a save or no row was stored
     */
    public List<M> createManyQuietly() {
        return createListQuietly(bound().effectiveCount());
    }

    /**
     * Creates a number of models with the registered listeners silenced for the whole batch.
     *
     * @param count the number of models, below 1 for none
     * @return a new mutable list the caller owns, empty when {@code count} is below 1
     * @throws IllegalStateException if a model's own hook stopped a save or no row was stored
     */
    public List<M> createManyQuietly(int count) {
        return createListQuietly(count);
    }

    /**
     * Builds the attributes of one model, ignoring the count; no model is made and the database is
     * never touched.
     *
     * @return a new mutable map the caller owns, never {@code null}
     * @throws IllegalStateException if a nested factory value or a {@code forParent} factory would
     *     have to create a model; a model value gives its key
     */
    public Map<String, Object> raw() {
        return rawList(1).get(0);
    }

    /**
     * Builds the attributes of as many models as the count says, or one, without touching the
     * database.
     *
     * @return a new mutable list of new mutable maps, empty when the count is below 1
     * @throws IllegalStateException if a nested factory value or a {@code forParent} factory would
     *     have to create a model
     */
    public List<Map<String, Object>> rawMany() {
        return rawList(bound().effectiveCount());
    }

    /**
     * Builds the attributes of a number of models without touching the database.
     *
     * @param count the number of models, below 1 for none
     * @return a new mutable list of new mutable maps, empty when {@code count} is below 1
     * @throws IllegalStateException if a nested factory value or a {@code forParent} factory would
     *     have to create a model
     */
    public List<Map<String, Object>> rawMany(int count) {
        return rawList(count);
    }

    /**
     * Inserts as many rows as the count says, or one, in one statement. No after-making or
     * after-creating hook and no model event runs, though {@code beforeWrite()} runs on each row, and no row is sent when the count is below 1.
     *
     * @throws IllegalArgumentException if the rows do not have the same keys
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the insert
     */
    public void insert() {
        insertRows(bound().effectiveCount());
    }

    /**
     * Inserts a number of rows in one statement. No after-making or after-creating hook and no
     * model event runs, though {@code beforeWrite()} runs on each row, and no row is sent when the
     * number is below 1.
     *
     * @param count the number of rows, below 1 for none
     * @throws IllegalArgumentException if the rows do not have the same keys
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the insert
     */
    public void insert(int count) {
        insertRows(count);
    }

    /**
     * Returns the settings of this factory with its definition bound.
     *
     * @return the settings, never {@code null}
     */
    FactorySpec<M> bound() {
        return spec.hasDefinition() ? spec : spec.withDefinition(this::definition);
    }

    private Factory<M> withSpec(FactorySpec<M> next) {
        return new Fixed<>(next);
    }

    private Factory<M> relate(UnaryOperator<FactoryRelations> change) {
        FactorySpec<M> current = bound();
        return withSpec(current.withRelations(change.apply(current.relations())));
    }

    private List<M> makeList(int count) {
        FactorySpec<M> current = bound();
        FactoryChildren.validate(current, false);
        return FactoryBuild.makeList(current, FactoryRun.start(current.relations(), false), count);
    }

    private List<M> createList(int count) {
        FactorySpec<M> current = bound();
        FactoryChildren.validate(current, true);
        return FactoryBuild.createList(current, FactoryRun.start(current.relations(), false), count);
    }

    private List<M> createListQuietly(int count) {
        Supplier<List<M>> work = () -> createList(count);
        return Model.withoutEvents(work);
    }

    private List<Map<String, Object>> rawList(int count) {
        FactorySpec<M> current = bound();
        FactoryChildren.validate(current, false);
        return FactoryRows.rows(current, FactoryRun.start(current.relations(), true), count);
    }

    private void insertRows(int count) {
        FactorySpec<M> current = bound();
        FactoryChildren.validate(current, false);
        FactoryBuild.insert(current, FactoryRun.start(current.relations(), false), count);
    }

    /**
     * The factory every fluent call and {@code of} returns, fixed to its settings.
     */
    private static final class Fixed<M extends Model> extends Factory<M> {

        private Fixed(FactorySpec<M> spec) {
            super(spec);
        }

        @Override
        protected Map<String, Object> definition() {
            return ((Factory<M>) this).spec.definition().get();
        }
    }
}
