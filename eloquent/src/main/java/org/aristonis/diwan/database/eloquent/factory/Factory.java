package org.aristonis.diwan.database.eloquent.factory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
 * is not resolved again. A {@link ClassCastException} thrown while a value is resolved is rethrown
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
        return withSpec(bound().withState((s, soFar, index) -> copy));
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
        return withSpec(bound().withState((s, soFar, index) -> state.apply(soFar)));
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
        return withSpec(bound().withState((s, soFar, index) -> Collections.singletonMap(name, value)));
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
        return withSpec(bound().withState((s, soFar, index) -> sequence.at(index)));
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
        return sequence(Sequence.of(entries));
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
        return sequence(Sequence.of(entries)).count(entries.length);
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
        return withSpec(bound().withAfterMaking(hook));
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
        return withSpec(bound().withAfterCreating(hook));
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
     * Makes one unsaved model, ignoring the count.
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
     * Builds the attributes of one model, ignoring the count; no model is made.
     *
     * @return a new mutable map the caller owns, never {@code null}
     */
    public Map<String, Object> raw() {
        return FactoryRows.row(bound(), 0);
    }

    /**
     * Builds the attributes of as many models as the count says, or one.
     *
     * @return a new mutable list of new mutable maps, empty when the count is below 1
     */
    public List<Map<String, Object>> rawMany() {
        FactorySpec<M> current = bound();
        return FactoryRows.rows(current, current.effectiveCount());
    }

    /**
     * Builds the attributes of a number of models.
     *
     * @param count the number of models, below 1 for none
     * @return a new mutable list of new mutable maps, empty when {@code count} is below 1
     */
    public List<Map<String, Object>> rawMany(int count) {
        return FactoryRows.rows(bound(), count);
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

    private FactorySpec<M> bound() {
        return spec.hasDefinition() ? spec : spec.withDefinition(this::definition);
    }

    private Factory<M> withSpec(FactorySpec<M> next) {
        return new Fixed<>(next);
    }

    private List<M> makeList(int count) {
        FactorySpec<M> current = bound();
        ModelQuery<M> query = current.effectiveQuery();
        List<M> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(makeOne(query, current, i));
        }
        return out;
    }

    private List<M> createList(int count) {
        FactorySpec<M> current = bound();
        ModelQuery<M> query = current.effectiveQuery();
        List<M> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(createOne(query, current, i));
        }
        return out;
    }

    private List<M> createListQuietly(int count) {
        Supplier<List<M>> work = () -> createList(count);
        return Model.withoutEvents(work);
    }

    private void insertRows(int count) {
        FactorySpec<M> current = bound();
        List<Map<String, Object>> rows = FactoryRows.rows(current, count);
        if (rows.isEmpty()) {
            return;
        }
        current.effectiveQuery().query().fillAndInsert(rows);
    }

    private static <M extends Model> M makeOne(ModelQuery<M> query, FactorySpec<M> spec, int index) {
        M model = query.newModel();
        model.forceFill(FactoryRows.row(spec, index));
        for (Consumer<M> hook : spec.afterMaking()) {
            hook.accept(model);
        }
        return model;
    }

    private static <M extends Model> M createOne(ModelQuery<M> query, FactorySpec<M> spec, int index) {
        M model = makeOne(query, spec, index);
        if (!model.save() || !model.exists()) {
            throw new IllegalStateException("Saving [" + model.getClass().getName()
                    + "] was stopped by a model event hook or stored no row");
        }
        for (Consumer<M> hook : spec.afterCreating()) {
            hook.accept(model);
        }
        return model;
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
