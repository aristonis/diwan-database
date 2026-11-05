package org.aristonis.diwan.database.eloquent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * The base class of every model: one row of one table, with Laravel's Eloquent persistence.
 *
 * <p>A model class declares a {@link ModelQuery} entry and overrides the protected configuration
 * methods it needs ({@code table()}, {@code fillable()}, {@code timestamps()} and the rest). Every
 * statement goes through the core query builder of the model's {@link QueryFactory}, found in this
 * order: the factory the model was bound to, the {@link ConnectionResolver} it was bound to, then
 * the process-wide default resolver.
 *
 * <p>Saving writes only dirty attributes, finds the row by the key as it was last read, and keeps
 * {@code created_at}/{@code updated_at} as {@link TimestampFormat} says. Before-hooks
 * ({@code saving()}, {@code creating()}, {@code updating()}, {@code deleting()}) can veto the
 * operation by returning {@code false}: no SQL runs and the method returns {@code false}. An
 * exception from a hook propagates; one thrown before the statement leaves the model unsaved.
 *
 * <p>Instances are not safe to share across threads.
 */
public abstract class Model extends ModelSoftDeletes {

    private static final ThreadLocal<int[]> TIMESTAMPS_OFF = ThreadLocal.withInitial(() -> new int[1]);

    private boolean exists;
    private boolean wasRecentlyCreated;
    private QueryFactory factory;
    private ConnectionResolver resolver;
    private ModelClock clock;
    private ModelQuery<?> source;

    /**
     * Creates a model that is not yet in the database.
     */
    protected Model() {
    }

    // ---- connection ----

    /**
     * Binds this model to a query factory, which then wins over any resolver.
     *
     * @param queryFactory the factory, never {@code null}
     * @return this model
     * @throws NullPointerException if {@code queryFactory} is {@code null}
     */
    public Model setConnection(QueryFactory queryFactory) {
        this.factory = Objects.requireNonNull(queryFactory, "queryFactory");
        return this;
    }

    /**
     * Sets the clock this model reads timestamps from.
     *
     * @param modelClock the clock, never {@code null}
     * @return this model
     * @throws NullPointerException if {@code modelClock} is {@code null}
     */
    public Model setClock(ModelClock modelClock) {
        this.clock = Objects.requireNonNull(modelClock, "modelClock");
        return this;
    }

    final QueryFactory swapFactory(QueryFactory next) {
        QueryFactory prior = factory;
        factory = next;
        return prior;
    }

    final void bind(QueryFactory queryFactory, ConnectionResolver connections, ModelClock modelClock,
            ModelQuery<?> query) {
        this.factory = queryFactory;
        this.resolver = connections;
        this.clock = modelClock;
        this.source = query;
    }

    /**
     * Returns the query factory this model uses.
     *
     * @return the factory, never {@code null}
     * @throws IllegalStateException if the model is bound to no factory and no resolver and no
     *     default resolver is installed, or the resolver has no connection of the model's name
     */
    public QueryFactory getQueryFactory() {
        if (factory != null) {
            return factory;
        }
        if (resolver != null) {
            return resolver.resolve(getConnectionName());
        }
        Optional<ConnectionResolver> fallback = ConnectionResolver.getDefault();
        if (!fallback.isPresent()) {
            throw new IllegalStateException("No connection for model [" + getClass().getName()
                    + "]: use ModelQuery.on(...) or ModelQuery.using(...), or install ConnectionResolver.setDefault(...)");
        }
        return fallback.get().resolve(getConnectionName());
    }

    /**
     * Returns the connection this model uses.
     *
     * @return the connection, never {@code null}
     * @throws IllegalStateException as {@link #getQueryFactory()}
     */
    public Connection getConnection() {
        return getQueryFactory().connection();
    }

    /**
     * Returns a new query builder on this model's table, outside any model mapping.
     *
     * @return the builder, never {@code null}
     * @throws IllegalStateException as {@link #getQueryFactory()}
     */
    public Builder newBaseQuery() {
        return getQueryFactory().table(getTable());
    }

    // ---- state ----

    /**
     * Returns whether this model's row is in the database, as far as the model knows.
     *
     * @return whether it exists
     */
    public boolean exists() {
        return exists;
    }

    /**
     * Returns whether this model was inserted by its last save.
     *
     * @return whether it was just created
     */
    public boolean wasRecentlyCreated() {
        return wasRecentlyCreated;
    }

    final void markExisting() {
        exists = true;
    }

    // Whether getQueryFactory() has something to resolve from; it does not check the name resolves.
    @Override
    final boolean hasConnection() {
        return isBound() || ConnectionResolver.getDefault().isPresent();
    }

    final boolean isBound() {
        return factory != null || resolver != null;
    }

    final void hydrateFrom(Map<String, Object> row) {
        setRawAttributes(row, true);
        exists = true;
        fire(ModelEvent.RETRIEVED);
    }

    /**
     * Returns the value of the primary key.
     *
     * @return the key, or {@code null} when not set or the model has no key column
     */
    public Object getKey() {
        String key = getKeyName();
        return key == null ? null : getAttribute(key);
    }

    private Object getKeyForSaveQuery() {
        String key = getKeyName();
        return hasOriginal(key) ? getRawOriginal(key) : getKey();
    }

    private boolean hasOriginal(String key) {
        return hasRawOriginal(key);
    }

    /**
     * Adds the where clauses that select this model's row for an update, delete or refresh.
     * By default the primary key, using its original value; a model keyed otherwise overrides it.
     *
     * @param query the base query on the model's table, never {@code null}
     * @return the same query with the key clauses added
     */
    protected Builder setKeysForSaveQuery(Builder query) {
        return query.where(getKeyName(), "=", getKeyForSaveQuery());
    }

    // ---- saving ----

    /**
     * Saves the model: inserts it when new, else updates its dirty attributes, then makes every
     * attribute original. A clean existing model runs no SQL.
     *
     * <p><strong>Warning:</strong> as in Laravel, a new non-incrementing model with no attributes
     * at all inserts nothing, stays non-existent, and still returns {@code true}. Use
     * {@link #saveOrFail()} to have that case fail.
     *
     * @return {@code true} when saved or already clean, {@code false} when a hook vetoed it
     * @throws IllegalStateException if the model has no connection
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the statement
     */
    public boolean save() {
        return save(null);
    }

    /**
     * Saves the model as {@link #save()} does, inside a transaction on the model's connection, so
     * a failure in a hook rolls back every statement the save ran. Unlike {@link #save()} (and
     * Laravel), a new non-incrementing model with nothing to insert fails instead of reporting
     * success.
     *
     * <p>When anything throws, a hook after the SQL or the commit itself, the model is put back
     * as it was before the call: its attributes, originals, changes, {@link #exists()} and
     * {@link #wasRecentlyCreated()}, so a generated key or timestamp does not outlive the rolled
     * back row. Only this model is restored: another model saved by a nested {@code saveOrFail}
     * from a hook keeps its saved state when the outer transaction later rolls its row back. That
     * nested model must be bound to {@link #getConnection()} as seen inside the hook; one bound to
     * the root connection is refused while strict transactions are on.
     *
     * <p>A failure after the commit, such as an after-commit listener that throws, is rethrown
     * without that restore: the row is committed, so the model keeps its key, attributes and
     * {@link #exists()}, and a later {@link #save()} updates the row instead of inserting it again.
     * When the connection cannot report its commit, the failure is treated as a rollback.
     *
     * @return {@code true} when saved or already clean, {@code false} when a hook vetoed it
     * @throws IllegalStateException if the model has no connection, or it is a new
     *     non-incrementing model with no attributes and no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public boolean saveOrFail() {
        QueryFactory outer = getQueryFactory();
        QueryFactory bound = factory;
        boolean existed = exists;
        boolean wasCreated = wasRecentlyCreated;
        Snapshot before = snapshotAttributes();
        AtomicBoolean isCommitted = new AtomicBoolean();
        try {
            return outer.connection().transaction(c -> {
                // Registered before the save, so it runs ahead of every listener callback that
                // follows the commit and tells the catch below which of the two failed.
                if (c.canDeferUntilCommit()) {
                    c.afterCommit(() -> isCommitted.set(true));
                }
                // Strict transactions refuse the root connection, so the save runs on the callback's.
                factory = QueryFactory.of(c, outer.grammar(), outer.processor());
                try {
                    return save(outer);
                } finally {
                    factory = bound;
                }
            });
        } catch (RuntimeException | Error e) {
            // A committed row stays backed by the database, so only a rollback undoes the model:
            // it must not keep a key, timestamps or an exists flag the database no longer backs.
            if (!isCommitted.get()) {
                exists = existed;
                wasRecentlyCreated = wasCreated;
                restoreAttributes(before);
            }
            throw e;
        }
    }

    // A non-null outer is the factory a saveOrFail() transaction was opened on.
    private boolean save(QueryFactory outer) {
        requireDeferrable();
        if (!fireHalting(ModelEvent.SAVING)) {
            return false;
        }
        boolean saved;
        if (exists) {
            saved = !isDirty() || performUpdate();
        } else {
            saved = performInsert(outer != null);
        }
        if (saved) {
            fire(ModelEvent.SAVED);
            touchOwnersAfterSave(outer);
            syncOriginal();
        }
        return saved;
    }

    private boolean performUpdate() {
        if (!fireHalting(ModelEvent.UPDATING)) {
            return false;
        }
        if (usesTimestamps()) {
            updateTimestamps();
        }
        Map<String, Object> dirty = getDirty();
        if (!dirty.isEmpty()) {
            setKeysForSaveQuery(newBaseQuery()).update(dirty);
            syncChanges();
            fire(ModelEvent.UPDATED);
        }
        return true;
    }

    private boolean performInsert(boolean strict) {
        if (getUniqueIdKind() != UniqueIdKind.NONE) {
            setUniqueIds();
        }
        if (!fireHalting(ModelEvent.CREATING)) {
            return false;
        }
        if (usesTimestamps()) {
            updateTimestamps();
        }
        Map<String, Object> attributes = getAttributes();
        if (getIncrementing()) {
            Object id = newBaseQuery().insertGetId(attributes, getKeyName());
            setAttribute(getKeyName(), id);
        } else {
            if (attributes.isEmpty()) {
                if (strict) {
                    throw new IllegalStateException("Cannot insert model [" + getClass().getName()
                            + "]: it has no attributes and no key");
                }
                return true;
            }
            newBaseQuery().insert(attributes);
        }
        exists = true;
        wasRecentlyCreated = true;
        fire(ModelEvent.CREATED);
        return true;
    }

    private void setUniqueIds() {
        String key = getKeyName();
        Object value = getAttribute(key);
        // Only a missing or blank key is replaced; "0" is a key, unlike Laravel's empty() check.
        if (value == null || value.toString().trim().isEmpty()) {
            setAttribute(key, newUniqueId());
        }
    }

    /**
     * Returns a new unique id for the primary key, of the model's {@link UniqueIdKind}.
     *
     * @return the id, never {@code null}
     * @throws IllegalStateException if the model's kind is {@link UniqueIdKind#NONE}
     */
    protected String newUniqueId() {
        switch (getUniqueIdKind()) {
            case UUID:
                return uniqueIdGenerator().uuid7();
            case ULID:
                return uniqueIdGenerator().ulid();
            default:
                throw new IllegalStateException("The model generates no unique ids");
        }
    }

    /**
     * Returns the generator of unique ids; the shared standard generator by default.
     *
     * @return the generator, never {@code null}
     */
    protected UniqueIds uniqueIdGenerator() {
        return UniqueIds.standard();
    }

    /**
     * Fills the model and saves it, when it exists.
     *
     * @param values the attributes, as for {@link #fill(Map)}
     * @return {@code false} when the model does not exist or a hook vetoed the save
     * @throws MassAssignmentException as {@link #fill(Map)}
     */
    public boolean update(Map<String, ?> values) {
        if (!exists) {
            return false;
        }
        return fill(values).save();
    }

    // ---- deleting ----

    /**
     * Deletes the model's row by its key. A soft-deleting model instead sets its deleted-at
     * column and, when the model writes timestamps, its updated-at column, and keeps existing;
     * {@link #forceDelete()} removes the row for good.
     *
     * @return {@code true} when deleted; {@code false} when the model does not exist (Laravel
     *     returns {@code null}) or a hook vetoed it
     * @throws IllegalStateException if the model has no primary key: "No primary key defined on
     *     model."
     */
    public boolean delete() {
        if (getKeyName() == null) {
            throw new IllegalStateException("No primary key defined on model.");
        }
        if (!exists) {
            return false;
        }
        requireDeferrable();
        if (!fireHalting(ModelEvent.DELETING)) {
            return false;
        }
        touchOwnersBeforeDelete();
        if (!runSoftDelete()) {
            setKeysForSaveQuery(newBaseQuery()).delete();
            exists = false;
        }
        fire(ModelEvent.DELETED);
        return true;
    }

    // ---- increment ----

    /**
     * Adds 1 to a column of this model's row, as {@link #increment(String, Number, Map)}.
     *
     * @param column the column, never {@code null}
     * @return the affected rows, 0 when a hook vetoed it
     * @throws IllegalStateException if the model does not exist (Laravel runs a table-wide
     *     update instead)
     */
    public int increment(String column) {
        return increment(column, 1, new LinkedHashMap<String, Object>());
    }

    /**
     * Adds an amount to a column of this model's row, as {@link #increment(String, Number, Map)}.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @return the affected rows, 0 when a hook vetoed it
     * @throws IllegalStateException if the model does not exist (Laravel runs a table-wide
     *     update instead)
     */
    public int increment(String column, Number amount) {
        return increment(column, amount, new LinkedHashMap<String, Object>());
    }

    /**
     * Adds an amount to a column of this model's row in the database and on the model, setting
     * the extra columns and {@code updated_at} too. The changed attributes stay clean.
     *
     * <p>Differs from Laravel on a model that does not exist: Laravel runs the update with no key
     * clause, changing every row of the table; this refuses with an exception and runs no SQL.
     *
     * @param column the column, never {@code null}; its stored value is added to, not its cast
     * @param amount the amount, never {@code null}
     * @param extra other columns to set, never {@code null}
     * @return the affected rows, 0 when the {@code updating()} hook vetoed it, in which case the
     *     attributes are put back
     * @throws IllegalStateException if the model does not exist; Laravel would update every row
     *     of the table instead
     */
    public int increment(String column, Number amount, Map<String, ?> extra) {
        return incrementOrDecrement(column, amount, extra, true);
    }

    /**
     * Subtracts 1 from a column of this model's row, as {@link #increment(String, Number, Map)}.
     *
     * @param column the column, never {@code null}
     * @return the affected rows, 0 when a hook vetoed it
     * @throws IllegalStateException if the model does not exist (Laravel runs a table-wide
     *     update instead)
     */
    public int decrement(String column) {
        return decrement(column, 1, new LinkedHashMap<String, Object>());
    }

    /**
     * Subtracts an amount from a column of this model's row, as
     * {@link #increment(String, Number, Map)}.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @return the affected rows, 0 when a hook vetoed it
     * @throws IllegalStateException if the model does not exist (Laravel runs a table-wide
     *     update instead)
     */
    public int decrement(String column, Number amount) {
        return decrement(column, amount, new LinkedHashMap<String, Object>());
    }

    /**
     * Subtracts an amount from a column of this model's row, as
     * {@link #increment(String, Number, Map)}.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @param extra other columns to set, never {@code null}
     * @return the affected rows, 0 when a hook vetoed it
     * @throws IllegalStateException if the model does not exist (Laravel runs a table-wide
     *     update instead)
     */
    public int decrement(String column, Number amount, Map<String, ?> extra) {
        return incrementOrDecrement(column, amount, extra, false);
    }

    private int incrementOrDecrement(String column, Number amount, Map<String, ?> extra, boolean up) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(extra, "extra");
        if (!exists) {
            throw new IllegalStateException("Cannot increment or decrement a model that is not in the database");
        }
        requireDeferrable();
        Map<String, Object> before = getAttributes();
        Map<String, Object> written = new LinkedHashMap<>(extra);
        if (usesTimestamps() && getUpdatedAtColumn() != null && !written.containsKey(getUpdatedAtColumn())) {
            written.put(getUpdatedAtColumn(), freshTimestampValue());
        }
        putRawAttribute(column, ModelNumbers.add(getRawAttribute(column), amount, up));
        forceFill(written);
        if (!fireHalting(ModelEvent.UPDATING)) {
            setRawAttributes(before);
            return 0;
        }
        Builder query = setKeysForSaveQuery(newBaseQuery());
        int affected = up ? query.increment(column, amount, written) : query.decrement(column, amount, written);
        syncChanges();
        fire(ModelEvent.UPDATED);
        String[] synced = written.keySet().toArray(new String[written.size() + 1]);
        synced[written.size()] = column;
        syncOriginalAttributes(synced);
        return affected;
    }

    // ---- reloading and copying ----

    /**
     * Reloads every attribute from the database, by the key as last read, then the relations
     * that are loaded.
     *
     * @return this model; unchanged when it does not exist
     * @throws ModelNotFoundException if the row is gone
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a loaded
     *     relation was set under a name the model's relation map does not have
     */
    public Model refresh() {
        if (!exists) {
            return this;
        }
        Optional<Map<String, Object>> row = setKeysForSaveQuery(newBaseQuery()).first();
        if (!row.isPresent()) {
            throw new ModelNotFoundException(getClass(), Collections.<Object>emptyList());
        }
        setRawAttributes(row.get(), true);
        return reloadLoadedRelations();
    }

    /**
     * Returns a new instance of this model loaded from the database, by the key as last read.
     *
     * @param <M> the model's own class, inferred from the assignment
     * @return the new instance, or empty when this model does not exist or its row is gone
     * @throws ClassCastException at the call site if {@code M} is not this model's class
     * @throws IllegalStateException if the model class declares no {@link ModelQuery} entry
     */
    @SuppressWarnings("unchecked")
    public <M extends Model> Optional<M> fresh() {
        if (!exists) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> row = setKeysForSaveQuery(newBaseQuery()).first();
        if (!row.isPresent()) {
            return Optional.empty();
        }
        Model copy = newInstanceLike();
        copy.hydrateFrom(row.get());
        return Optional.of((M) copy);
    }

    /**
     * Returns a new, unsaved instance with this model's attributes, except the key, the timestamp
     * columns and the given keys.
     *
     * @param <M> the model's own class, inferred from the assignment
     * @param except further attributes to leave out
     * @return the copy, bound to the same connection, never {@code null}
     * @throws ClassCastException at the call site if {@code M} is not this model's class
     * @throws IllegalStateException if the model class declares no {@link ModelQuery} entry
     */
    @SuppressWarnings("unchecked")
    public <M extends Model> M replicate(String... except) {
        Map<String, Object> attributes = new LinkedHashMap<>(getAttributes());
        attributes.keySet().remove(getKeyName());
        attributes.keySet().remove(getCreatedAtColumn());
        attributes.keySet().remove(getUpdatedAtColumn());
        for (String key : except) {
            attributes.remove(key);
        }
        Model copy = newInstanceLike();
        copy.setRawAttributes(attributes);
        copy.fire(ModelEvent.REPLICATING);
        return (M) copy;
    }

    @Override
    final ModelQuery<?> eventSource() {
        return source;
    }

    final Model newInstanceLike() {
        ModelQuery<?> query = source != null ? source : ModelQuery.registered(getClass());
        if (query == null) {
            throw new IllegalStateException("Model [" + getClass().getName()
                    + "] has no ModelQuery.of(...) entry to create instances with");
        }
        Model copy = query.newBareModel();
        copy.bind(factory, resolver, clock, query);
        return copy;
    }

    /**
     * Returns whether another model is the same row: same key, table and connection name. Whole
     * number keys compare by value, so {@code 1} and {@code 1L} match.
     *
     * @param other the other model, may be {@code null}
     * @return whether both are the same row
     */
    public boolean is(Model other) {
        return other != null && AttributeValues.sameKey(getKey(), other.getKey())
                && getTable().equals(other.getTable())
                && Objects.equals(getConnectionName(), other.getConnectionName());
    }

    /**
     * Returns whether another model is not the same row, as the negation of {@link #is(Model)}.
     *
     * @param other the other model, may be {@code null}
     * @return whether they differ
     */
    public boolean isNot(Model other) {
        return !is(other);
    }

    // ---- timestamps ----

    /**
     * Runs a callback with timestamps off for every model on the calling thread. Calls nest.
     *
     * @param <R> the type of the result
     * @param callback the work to run, never {@code null}
     * @return the callback's result, may be {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public static <R> R withoutTimestamps(Supplier<R> callback) {
        Objects.requireNonNull(callback, "callback");
        int[] depth = TIMESTAMPS_OFF.get();
        depth[0]++;
        try {
            return callback.get();
        } finally {
            depth[0]--;
            if (depth[0] == 0) {
                TIMESTAMPS_OFF.remove();
            }
        }
    }

    /**
     * Returns whether this model writes timestamps now: its {@code timestamps()} setting, unless
     * {@link #withoutTimestamps(Supplier)} is running on this thread.
     *
     * @return whether timestamps are written
     */
    public boolean usesTimestamps() {
        return timestamps() && TIMESTAMPS_OFF.get()[0] == 0;
    }

    /**
     * Sets {@code updated_at}, and {@code created_at} on a new model, to now, unless already
     * dirty.
     *
     * @return this model
     */
    public Model updateTimestamps() {
        Object time = freshTimestampValue();
        String updatedAt = getUpdatedAtColumn();
        if (updatedAt != null && !isDirty(updatedAt)) {
            setAttribute(updatedAt, time);
        }
        String createdAt = getCreatedAtColumn();
        if (!exists && createdAt != null && !isDirty(createdAt)) {
            setAttribute(createdAt, time);
        }
        return this;
    }

    /**
     * Updates the timestamps and saves.
     *
     * @return {@code false} when the model keeps no timestamps or a hook vetoed the save
     */
    public boolean touch() {
        if (!usesTimestamps()) {
            return false;
        }
        updateTimestamps();
        return save();
    }

    /**
     * Sets a column to now and saves, with the usual timestamps.
     *
     * @param column the column, never {@code null}
     * @return {@code false} when a hook vetoed the save
     */
    public boolean touch(String column) {
        setAttribute(column, freshTimestampValue());
        return save();
    }

    /**
     * Returns the current moment from the model's clock, truncated to microseconds.
     *
     * @return the moment, never {@code null}
     */
    public Instant freshTimestamp() {
        return (clock != null ? clock : ModelClock.system()).now();
    }

    /**
     * Returns the current moment as the model stores it: text in the date format in UTC, or an
     * {@code Instant}, as {@code getTimestampFormat()} says.
     *
     * @return the value, never {@code null}
     */
    public Object freshTimestampValue() {
        return fromDateTime(freshTimestamp());
    }

    /**
     * Converts a moment to the form the model stores, as {@link #freshTimestampValue()}.
     *
     * @param moment the moment, never {@code null}
     * @return the stored form, never {@code null}
     */
    public Object fromDateTime(Instant moment) {
        Objects.requireNonNull(moment, "moment");
        if (getTimestampFormat() == TimestampFormat.INSTANT) {
            return moment;
        }
        return DateTimeFormatter.ofPattern(getDateFormat(), Locale.ROOT).withZone(ZoneOffset.UTC).format(moment);
    }
}
