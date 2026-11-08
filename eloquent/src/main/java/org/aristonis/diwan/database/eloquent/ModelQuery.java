package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.ModelEvents;
import org.aristonis.diwan.database.eloquent.exception.MassAssignmentException;
import org.aristonis.diwan.database.eloquent.exception.ModelNotFoundException;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.ClassCache;
import org.aristonis.diwan.database.eloquent.exception.UnregisteredModelException;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * The entry point of one model class: makes instances and starts {@link EloquentBuilder} queries,
 * as Laravel's static calls on a model do.
 *
 * <p>Each model class declares one as a constant, which also registers the class's constructor so
 * that {@link Model#fresh()} and {@link Model#replicate(String...)} can make instances without
 * reflection:
 *
 * <pre>{@code
 * public static final ModelQuery<User> query = ModelQuery.of(User.class, User::new);
 *
 * User user = User.query.on(db).find(1).orElseThrow(...);
 * }</pre>
 *
 * <p>Safe to share across threads; {@link #on}, {@link #using} and {@link #withClock} return new
 * entries. The global scope registry is the one mutable part, and every entry of a class shares it.
 *
 * @param <T> the model class
 */
public final class ModelQuery<T extends Model> extends ModelQueryScopes<T> {

    /**
     * The model classes seen by {@link #of}, each with the entry first registered for it. The
     * entry lives on the class where the platform allows it, so a dropped class loader is
     * collected with its models; a later {@code of} for the same class with another constructor
     * does not replace the first. A lookup attaches an empty slot to the probed class, freed with
     * the class.
     */
    private static final ClassCache<AtomicReference<ModelQuery<?>>> REGISTRY =
            ClassCache.of(type -> new AtomicReference<>());

    /**
     * Whether lazy loading is prevented: a process-wide boot setting, read on every
     * lazy relation read. It is the only static mutable state the relation feature adds.
     */
    private static volatile boolean preventLazyLoading;

    // Registers the model package's bridge for the relation package: every model has an
    // entry, so this class is initialised before any relation code asks for the bridge.
    static {
        Access.setEloquent(new EloquentAccessImpl());
    }

    private final Supplier<T> constructor;
    private final QueryFactory factory;
    private final ConnectionResolver resolver;
    private final ModelClock clock;

    /**
     * The checked relation registry of the model class: {@code null} until the
     * relation package checks the class's {@code relations()} map, then written once by
     * {@code compareAndSet} and read without a lock. Typed {@code Object} because the registry
     * type is package-private in the relation package, the only code that reads or writes it.
     * Entries derived by {@link #on}, {@link #using} and {@link #withClock} share their origin's
     * slot, but the cache is read only through {@link #registered(Class)}, so a class has one.
     */
    private final AtomicReference<Object> relations;

    /**
     * The checked {@code touches()} configuration of the model class: {@code null} until the
     * relation package checks it on first save or delete, then written once by
     * {@code compareAndSet} and read without a lock. Typed {@code Object} for the same reason as
     * {@link #relations}, and shared by derived entries the same way.
     */
    private final AtomicReference<Object> touches;

    /**
     * The model event listeners of the class, replaced by compare-and-set, never changed. Reached
     * only through {@link #eventSlot()}, which finds the registered entry's, so derived entries
     * share the class's listeners.
     */
    private final AtomicReference<EventListeners> events = new AtomicReference<>(EventListeners.EMPTY);

    private ModelQuery(Class<T> type, Supplier<T> constructor, QueryFactory factory, ConnectionResolver resolver,
            ModelClock clock, AtomicReference<Object> relations, AtomicReference<Object> touches) {
        super(type);
        this.constructor = constructor;
        this.factory = factory;
        this.resolver = resolver;
        this.clock = clock;
        this.relations = relations;
        this.touches = touches;
    }

    /**
     * Returns the entry of a model class, not bound to any connection, and registers the class's
     * constructor. A class registers once; later calls for it keep the first constructor.
     *
     * <p>A model's relations need its entry: {@code relation(name)}, eager loading and existence
     * queries find the class's checked relation map through the entry registered here, and a
     * relation to a class without one fails. The class's static initialiser registers it, so a
     * relation to a class whose entry no code has touched yet fails the same way.
     *
     * @param <T> the model class
     * @param type the model class, never {@code null}
     * @param constructor makes a new instance, such as {@code User::new}, never {@code null}
     * @return the entry, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T extends Model> ModelQuery<T> of(Class<T> type, Supplier<T> constructor) {
        ModelQuery<T> query = new ModelQuery<>(Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(constructor, "constructor"), null, null, null, new AtomicReference<>(),
                new AtomicReference<>());
        REGISTRY.get(type).compareAndSet(null, query);
        return query;
    }

    static ModelQuery<?> registered(Class<?> type) {
        return REGISTRY.get(type).get();
    }

    /**
     * Returns the entry of a model class, loading the class once when no entry is registered yet.
     *
     * <p>A class literal does not run the static initialiser that registers the entry, so a class
     * named only by its literal can miss the registry until something initialises it. On a miss this
     * initialises the class by its own name and reads the registry again. It is the only class
     * loading in the module.
     *
     * @param type the model class, never {@code null}
     * @return the entry, never {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws UnregisteredModelException if the class still has no entry after loading, or cannot be
     *     loaded or linked; the load error is the cause
     */
    static ModelQuery<?> registeredOrLoad(Class<?> type) {
        Objects.requireNonNull(type, "type");
        ModelQuery<?> found = registered(type);
        if (found != null) {
            return found;
        }
        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new UnregisteredModelException(type, e);
        }
        found = registered(type);
        if (found == null) {
            throw new UnregisteredModelException(type);
        }
        return found;
    }

    /**
     * Turns lazy-loading prevention on or off for the whole process, a port of Laravel's
     * {@code Model::preventLazyLoading}.
     *
     * <p>While on, reading a relation that is not loaded from a model that came in a result of more
     * than one row throws {@link org.aristonis.diwan.database.eloquent.exception.LazyLoadingViolationException}
     * before any query runs. Models from single-row results, new models, loaded relations and typed
     * relation queries such as {@code user.posts().get()} are never blocked. Only models hydrated
     * while the switch is on are marked, and a marked model is blocked only while it stays on.
     *
     * <p>This is a boot-time setting for every entry and every thread, not a per-request one: set it
     * once at start-up (for example in development and tests) rather than toggling it around a call.
     *
     * @param prevent whether to prevent lazy loading
     */
    public static void preventLazyLoading(boolean prevent) {
        preventLazyLoading = prevent;
    }

    /**
     * Returns whether lazy loading is prevented, as set by {@link #preventLazyLoading(boolean)}.
     *
     * @return whether lazy loading is prevented; {@code false} until it is turned on
     */
    public static boolean preventsLazyLoading() {
        return preventLazyLoading;
    }

    AtomicReference<Object> relationRegistry() {
        return relations;
    }

    AtomicReference<Object> touchesSlot() {
        return touches;
    }

    /**
     * Returns the event listener slot of the model class: the registered entry's, else this
     * entry's when the class has none registered.
     *
     * @return the slot, never {@code null}
     */
    AtomicReference<EventListeners> eventSlot() {
        ModelQuery<?> registered = REGISTRY.get(type).get();
        return registered != null ? registered.events : events;
    }

    /**
     * Returns the listener registrar of the model class. Listeners registered here apply to every
     * instance of the class and to every entry derived from this one; the registrar of any entry of
     * the class edits the same listeners.
     *
     * @return the registrar, never {@code null}
     */
    public ModelEvents<T> events() {
        return new ModelEvents<>(eventSlot());
    }

    /**
     * Returns an entry whose models use the given query factory.
     *
     * @param queryFactory the factory, never {@code null}
     * @return a new entry, never {@code null}
     * @throws NullPointerException if {@code queryFactory} is {@code null}
     */
    public ModelQuery<T> on(QueryFactory queryFactory) {
        return new ModelQuery<>(type, constructor, Objects.requireNonNull(queryFactory, "queryFactory"), null, clock,
                relations, touches);
    }

    /**
     * Returns an entry whose models run on the given connection, with the grammar and processor of
     * the factory this entry's models use now: the bound factory, else the one their resolver (or
     * the default resolver) gives. Its main use is inside a transaction, where the callback's
     * connection must carry every statement:
     *
     * <pre>{@code
     * db.connection().transaction(c -> User.query.on(c).find(1));
     * }</pre>
     *
     * @param connection the connection, never {@code null}
     * @return a new entry, never {@code null}
     * @throws NullPointerException if {@code connection} is {@code null}
     * @throws IllegalStateException if this entry's models have no factory to take the grammar from
     */
    public ModelQuery<T> on(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        QueryFactory current = factory != null ? factory : newModel().getQueryFactory();
        return on(QueryFactory.of(connection, current.grammar(), current.processor()));
    }

    /**
     * Returns an entry whose models find their factory by connection name in a resolver.
     *
     * @param connections the resolver, never {@code null}
     * @return a new entry, never {@code null}
     * @throws NullPointerException if {@code connections} is {@code null}
     */
    public ModelQuery<T> using(ConnectionResolver connections) {
        return new ModelQuery<>(type, constructor, null, Objects.requireNonNull(connections, "connections"), clock,
                relations, touches);
    }

    /**
     * Returns an entry whose models read timestamps from the given clock.
     *
     * @param modelClock the clock, never {@code null}
     * @return a new entry, never {@code null}
     * @throws NullPointerException if {@code modelClock} is {@code null}
     */
    public ModelQuery<T> withClock(ModelClock modelClock) {
        return new ModelQuery<>(type, constructor, factory, resolver, Objects.requireNonNull(modelClock, "modelClock"),
                relations, touches);
    }

    /**
     * Returns the model class.
     *
     * @return the class, never {@code null}
     */
    public Class<T> getModelClass() {
        return type;
    }

    T newBareModel() {
        T model = constructor.get();
        if (!type.isInstance(model)) {
            throw new IllegalStateException("The constructor of " + type.getName() + " made "
                    + (model == null ? "null" : model.getClass().getName()));
        }
        return model;
    }

    /**
     * Returns a new, empty model bound to this entry's connection and clock.
     *
     * @return the model, never {@code null}
     * @throws IllegalStateException if the constructor returns {@code null} or another class
     */
    public T newModel() {
        T model = newBareModel();
        model.bind(factory, resolver, clock, this);
        return model;
    }

    /**
     * Returns a new, unsaved model filled from a map.
     *
     * @param values the attributes, as for {@link Model#fill(Map)}
     * @return the model, never {@code null}
     * @throws MassAssignmentException as {@link Model#fill(Map)}
     */
    @SuppressWarnings("unchecked")
    public T make(Map<String, ?> values) {
        return (T) newModel().fill(values);
    }

    /**
     * Starts a query on the model's table.
     *
     * @return the builder, never {@code null}
     * @throws IllegalStateException if the model has no connection
     */
    public EloquentBuilder<T> query() {
        return new EloquentBuilder<>(this, newModel());
    }

    /**
     * Returns existing models built from rows, without a query.
     *
     * @param rows the rows, never {@code null}
     * @return the models, never {@code null}
     * @throws IllegalStateException if the model has no connection
     */
    public ModelCollection<T> hydrate(List<? extends Map<String, ?>> rows) {
        return query().hydrate(rows);
    }

    /**
     * Returns every model of the table.
     *
     * @return the models, never {@code null}
     */
    public ModelCollection<T> all() {
        return query().get();
    }

    /**
     * Finds a model by its key, as {@link EloquentBuilder#find(Object)}.
     *
     * @param id the key, never {@code null}
     * @return the model, or empty
     * @throws IllegalArgumentException if {@code id} is a {@link Collection}
     */
    public Optional<T> find(Object id) {
        return query().find(id);
    }

    /**
     * Finds models by their keys, as {@link EloquentBuilder#find(Collection)}.
     *
     * @param ids the keys, never {@code null}
     * @return the models found, never {@code null}
     */
    public ModelCollection<T> find(Collection<?> ids) {
        return query().find(ids);
    }

    /**
     * Finds models by their keys, as {@link EloquentBuilder#findMany(Collection)}.
     *
     * @param ids the keys, never {@code null}
     * @return the models found, never {@code null}
     */
    public ModelCollection<T> findMany(Collection<?> ids) {
        return query().findMany(ids);
    }

    /**
     * Finds a model by its key or throws, as {@link EloquentBuilder#findOrFail(Object)}.
     *
     * @param id the key, never {@code null}
     * @return the model, never {@code null}
     * @throws ModelNotFoundException if there is none
     */
    public T findOrFail(Object id) {
        return query().findOrFail(id);
    }

    /**
     * Starts a query with a where clause, as {@link EloquentBuilder#where(String, Object)}.
     *
     * @param column the column
     * @param value the value
     * @return the builder, never {@code null}
     */
    public EloquentBuilder<T> where(String column, Object value) {
        return query().where(column, value);
    }

    /**
     * Starts a query with a where clause, as {@link EloquentBuilder#where(String, String, Object)}.
     *
     * @param column the column
     * @param operator the operator
     * @param value the value
     * @return the builder, never {@code null}
     */
    public EloquentBuilder<T> where(String column, String operator, Object value) {
        return query().where(column, operator, value);
    }

    /**
     * Starts a query that eager loads relations, as {@link EloquentBuilder#with(String...)}.
     *
     * @param relations the relation paths, none {@code null}
     * @return the builder, never {@code null}
     * @throws NullPointerException if a path is {@code null}
     * @throws IllegalArgumentException if a {@code name:columns} path lists an empty column
     */
    public EloquentBuilder<T> with(String... relations) {
        return query().with(relations);
    }

    /**
     * Starts a query that eager loads one constrained relation, as
     * {@link EloquentBuilder#with(String, Consumer)}.
     *
     * @param relation the relation path, never {@code null}
     * @param constraint the constraint on the relation's query, never {@code null}
     * @return the builder, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public EloquentBuilder<T> with(String relation, Consumer<EloquentBuilder<?>> constraint) {
        return query().with(relation, constraint);
    }

    /**
     * Starts a query that eager loads constrained relations, as
     * {@link EloquentBuilder#with(Map)}.
     *
     * @param relations the constraints by relation path, no key or value {@code null}
     * @return the builder, never {@code null}
     * @throws NullPointerException if the map, a path or a constraint is {@code null}
     */
    public EloquentBuilder<T> with(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        return query().with(relations);
    }

    /**
     * Fills a new model and saves it, as {@link EloquentBuilder#create(Map)}.
     *
     * @param values the attributes
     * @return the saved model, never {@code null}
     */
    public T create(Map<String, ?> values) {
        return query().create(values);
    }

    /**
     * Fills a new model ignoring the guards and saves it, as {@link EloquentBuilder#forceCreate(Map)}.
     *
     * @param values the attributes
     * @return the saved model, never {@code null}
     */
    public T forceCreate(Map<String, ?> values) {
        return query().forceCreate(values);
    }

    /**
     * Returns the first model matching the attributes, or a new unsaved one, as
     * {@link EloquentBuilder#firstOrNew(Map, Map)}.
     *
     * @param attributes the columns to match
     * @param values more attributes for a new model
     * @return the model, never {@code null}
     */
    public T firstOrNew(Map<String, ?> attributes, Map<String, ?> values) {
        return query().firstOrNew(attributes, values);
    }

    /**
     * Returns the first model matching the attributes, or creates one, as
     * {@link EloquentBuilder#firstOrCreate(Map, Map)}.
     *
     * @param attributes the columns to match
     * @return the model, never {@code null}
     */
    public T firstOrCreate(Map<String, ?> attributes) {
        return query().firstOrCreate(attributes);
    }

    /**
     * Returns the first model matching the attributes, or creates one, as
     * {@link EloquentBuilder#firstOrCreate(Map, Map)}.
     *
     * @param attributes the columns to match
     * @param values more attributes for a new model
     * @return the model, never {@code null}
     */
    public T firstOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        return query().firstOrCreate(attributes, values);
    }

    /**
     * Updates the first model matching the attributes, or creates one, as
     * {@link EloquentBuilder#updateOrCreate(Map, Map)}.
     *
     * @param attributes the columns to match
     * @param values the attributes to set
     * @return the model, never {@code null}
     */
    public T updateOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        return query().updateOrCreate(attributes, values);
    }

    /**
     * Loads the models with the given keys and deletes each one, so each runs its hooks. The load
     * goes through the model's global scopes, so a soft-deleting model skips trashed rows.
     *
     * @param ids the keys
     * @return the number of models deleted
     */
    public int destroy(Object... ids) {
        return destroy(Arrays.asList(ids));
    }

    /**
     * Loads the models with the given keys and deletes each one, so each runs its hooks. The load
     * goes through the model's global scopes, so a soft-deleting model skips trashed rows.
     *
     * @param ids the keys, never {@code null}
     * @return the number of models deleted
     */
    public int destroy(Collection<?> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (T model : query().whereKey(ids).get()) {
            if (model.delete()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Loads the models with the given keys, trashed ones included, and force deletes each one.
     *
     * @param ids the keys
     * @return the number of models deleted
     * @throws IllegalStateException if the model does not use soft deletes
     */
    public int forceDestroy(Object... ids) {
        return forceDestroy(Arrays.asList(ids));
    }

    /**
     * Loads the models with the given keys, trashed ones included, and force deletes each one.
     *
     * @param ids the keys, never {@code null}
     * @return the number of models deleted
     * @throws IllegalStateException if the model does not use soft deletes
     */
    public int forceDestroy(Collection<?> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (T model : query().withoutGlobalScope(SoftDeletingScope.class).whereKey(ids).get()) {
            if (model.forceDelete()) {
                count++;
            }
        }
        return count;
    }
}
