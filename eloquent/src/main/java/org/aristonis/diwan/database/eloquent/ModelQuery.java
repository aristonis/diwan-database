package org.aristonis.diwan.database.eloquent;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
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
 * <p>Immutable and safe to share across threads; {@link #on}, {@link #using} and
 * {@link #withClock} return new entries.
 *
 * @param <T> the model class
 */
public final class ModelQuery<T extends Model> {

    /**
     * The model classes seen by {@link #of}, each with the constructor first registered for it.
     * Entries are never removed: a class registered here stays reachable for the life of the
     * process (and pins its class loader), and a later {@code of} for the same class with another
     * constructor does not replace the first.
     */
    private static final ConcurrentMap<Class<?>, ModelQuery<?>> REGISTRY = new ConcurrentHashMap<>();

    private final Class<T> type;
    private final Supplier<T> constructor;
    private final QueryFactory factory;
    private final ConnectionResolver resolver;
    private final ModelClock clock;

    private ModelQuery(Class<T> type, Supplier<T> constructor, QueryFactory factory, ConnectionResolver resolver,
            ModelClock clock) {
        this.type = type;
        this.constructor = constructor;
        this.factory = factory;
        this.resolver = resolver;
        this.clock = clock;
    }

    /**
     * Returns the entry of a model class, not bound to any connection, and registers the class's
     * constructor. A class registers once; later calls for it keep the first constructor.
     *
     * @param <T> the model class
     * @param type the model class, never {@code null}
     * @param constructor makes a new instance, such as {@code User::new}, never {@code null}
     * @return the entry, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T extends Model> ModelQuery<T> of(Class<T> type, Supplier<T> constructor) {
        ModelQuery<T> query = new ModelQuery<>(Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(constructor, "constructor"), null, null, null);
        REGISTRY.putIfAbsent(type, query);
        return query;
    }

    static ModelQuery<?> registered(Class<?> type) {
        return REGISTRY.get(type);
    }

    /**
     * Returns an entry whose models use the given query factory.
     *
     * @param queryFactory the factory, never {@code null}
     * @return a new entry, never {@code null}
     * @throws NullPointerException if {@code queryFactory} is {@code null}
     */
    public ModelQuery<T> on(QueryFactory queryFactory) {
        return new ModelQuery<>(type, constructor, Objects.requireNonNull(queryFactory, "queryFactory"), null, clock);
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
        return new ModelQuery<>(type, constructor, null, Objects.requireNonNull(connections, "connections"), clock);
    }

    /**
     * Returns an entry whose models read timestamps from the given clock.
     *
     * @param modelClock the clock, never {@code null}
     * @return a new entry, never {@code null}
     * @throws NullPointerException if {@code modelClock} is {@code null}
     */
    public ModelQuery<T> withClock(ModelClock modelClock) {
        return new ModelQuery<>(type, constructor, factory, resolver, Objects.requireNonNull(modelClock, "modelClock"));
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
     * Loads the models with the given keys and deletes each one, so each runs its hooks.
     *
     * @param ids the keys
     * @return the number of models deleted
     */
    public int destroy(Object... ids) {
        return destroy(Arrays.asList(ids));
    }

    /**
     * Loads the models with the given keys and deletes each one, so each runs its hooks.
     *
     * @param ids the keys, never {@code null}
     * @return the number of models deleted
     */
    public int destroy(Collection<?> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        T model = newModel();
        int count = 0;
        for (Map<String, Object> row : model.newBaseQuery().whereIn(model.getKeyName(), ids).get()) {
            Model loaded = newModel();
            loaded.hydrateFrom(row);
            if (loaded.delete()) {
                count++;
            }
        }
        return count;
    }
}
