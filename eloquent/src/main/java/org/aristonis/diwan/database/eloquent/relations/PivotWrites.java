package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.support.Inflector;

/**
 * The join table writes of a belongsToMany relation: attach, detach, update of existing rows and
 * touch. The sync and related-side writes are built on these in the layers above.
 *
 * <p>A write that needs more than one statement runs them in one transaction, so it applies fully or
 * not at all. Touches run after the relation's own transaction ends, so a touch that fails leaves the
 * link change committed. Inside a caller's outer transaction they run inside it.
 *
 * <p>A relation is single use: the transaction's connection is kept in a field of the relation for
 * the length of the call, so do not share one relation object between threads. The pivots of a
 * {@code using} class are rebound to the transaction's connection, so their listeners run inside it;
 * the listeners of any other model run on that model's own connection, outside the transaction's
 * factory.
 *
 * <p>Attaching an id that is already linked adds a second row unless the join table has a unique index,
 * as in Laravel.
 *
 * <p>Attribute maps name join table columns. The names are trusted like any column name given to the
 * query builder; a dotted name is refused. Values are always bound.
 *
 * @param <R> the related model class
 */
abstract class PivotWrites<R extends Model> extends PivotReads<R> {

    /** Bindings the touch update adds to the id list: the new timestamp. */
    private static final int TOUCH_SET_BINDINGS = 1;

    /** The factory of the open transaction, or {@code null} outside one or when the parent's own is in use. */
    private QueryFactory active;

    /**
     * Builds the relation over the given query and pivot configuration.
     *
     * @param query the related model's query, never {@code null}
     * @param parent the model the relation is read from, never {@code null}
     * @param table the pivot table, never {@code null}
     * @param foreignPivotKey the pivot column that holds the parent's key, never {@code null}
     * @param relatedPivotKey the pivot column that holds the related key, never {@code null}
     * @param parentKey the parent's column the pivot points at, never {@code null}
     * @param relatedKey the related model's column the pivot points at, never {@code null}
     * @param relation the relation's name, or {@code null} when none is known
     */
    PivotWrites(EloquentBuilder<R> query, Model parent, String table, String foreignPivotKey,
            String relatedPivotKey, String parentKey, String relatedKey, String relation) {
        super(query, parent, table, foreignPivotKey, relatedPivotKey, parentKey, relatedKey, relation);
    }

    // ---- attach

    /**
     * Links the related models to the parent and touches the owners afterwards.
     *
     * @param ids an id, a related model, a collection or array of either, or a map from id to the pivot
     *     attributes of that id; an empty collection writes nothing
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an id is {@code null}, a model has no related key, rows name
     *     different columns, or an attribute name contains a dot
     * @throws IllegalStateException if the parent has no key value
     */
    public void attach(Object ids) {
        attach(ids, Collections.<String, Object>emptyMap(), true);
    }

    /**
     * Links the related models to the parent with the same pivot attributes for each, and touches the
     * owners afterwards.
     *
     * @param ids the ids, as for {@link #attach(Object)}
     * @param attributes the pivot attributes written for every id, never {@code null}; they win over the
     *     attributes given per id
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException as {@link #attach(Object)}
     * @throws IllegalStateException if the parent has no key value
     */
    public void attach(Object ids, Map<String, ?> attributes) {
        attach(ids, attributes, true);
    }

    /**
     * Links the related models to the parent. Rows go in chunks sized by the grammar's binding budget;
     * more than one statement runs in a transaction. With a {@code using} class each row is a pivot
     * saved on its own, so the pivot's events fire.
     *
     * @param ids the ids, as for {@link #attach(Object)}
     * @param attributes the pivot attributes written for every id, never {@code null}
     * @param touch whether to touch the owners afterwards, after the commit
     * @throws NullPointerException if {@code ids} or {@code attributes} is {@code null}
     * @throws IllegalArgumentException as {@link #attach(Object)}
     * @throws IllegalStateException if the parent has no key value
     * @throws org.aristonis.diwan.database.QueryException if the database refuses an insert, such as a
     *     unique index on an id already linked
     */
    public void attach(Object ids, Map<String, ?> attributes, boolean touch) {
        Objects.requireNonNull(attributes, "attributes");
        List<Map<String, Object>> rows = attachRecords(ids, attributes);
        attachInternal(rows);
        touchOwners(touch, !rows.isEmpty());
    }

    // ---- detach

    /**
     * Unlinks every related model of the parent that matches the relation's pivot conditions, and
     * touches the owners afterwards.
     *
     * @return the number of rows deleted
     * @throws IllegalStateException if the parent has no key value
     */
    public int detach() {
        int removed = usingClass() == null ? newPivotQuery().delete() : deletePivots(newPivotQuery());
        touchOwners(true, removed > 0);
        return removed;
    }

    /**
     * Unlinks the given related models and touches the owners afterwards.
     *
     * @param ids the ids, as for {@link #attach(Object)}
     * @return the number of rows deleted
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an id is {@code null} or a model has no related key
     * @throws IllegalStateException if the parent has no key value
     */
    public int detach(Object ids) {
        return detach(ids, true);
    }

    /**
     * Unlinks the given related models. Ids go in chunks sized by the grammar's binding budget; more
     * than one statement runs in a transaction. An empty collection runs no query, also with a
     * {@code using} class, where Laravel would detach every row.
     *
     * @param ids the ids, as for {@link #attach(Object)}
     * @param touch whether to touch the owners afterwards, after the commit
     * @return the number of rows deleted
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an id is {@code null} or a model has no related key
     * @throws IllegalStateException if the parent has no key value
     */
    public int detach(Object ids, boolean touch) {
        int removed = detachInternal(PivotRecords.parse(ids, getRelatedKeyName()).ids());
        touchOwners(touch, removed > 0);
        return removed;
    }

    // ---- update

    /**
     * Updates the pivot attributes of linked rows and touches the owners afterwards.
     *
     * @param id an id, a related model or a collection of either
     * @param attributes the columns and values to set, never {@code null}
     * @return the number of rows updated; with a {@code using} class the number of pivots that changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if an attribute name contains a dot or an id is invalid
     * @throws IllegalStateException if the parent has no key value
     */
    public int updateExistingPivot(Object id, Map<String, ?> attributes) {
        return updateExistingPivot(id, attributes, true);
    }

    /**
     * Updates the pivot attributes of linked rows. When the relation keeps an update timestamp on the
     * pivot it is set too. Ids go in chunks that count the values being set against the binding
     * budget; more than one statement runs in a transaction.
     *
     * @param id an id, a related model or a collection of either
     * @param attributes the columns and values to set, never {@code null}
     * @param touch whether to touch the owners afterwards, after the commit
     * @return the number of rows updated; with a {@code using} class the number of pivots that changed
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if an attribute name contains a dot or an id is invalid
     * @throws IllegalStateException if the parent has no key value
     */
    public int updateExistingPivot(Object id, Map<String, ?> attributes, boolean touch) {
        int updated = updateInternal(id, attributes);
        touchOwners(touch, updated > 0);
        return updated;
    }

    // ---- touch

    /**
     * Sets the update timestamp of the related rows that are linked to the parent, with no model read
     * and no event. Nothing runs when the related class keeps no timestamps, has no update column, has
     * its touches switched off on this thread, or the parent has no key value.
     *
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    @Override
    public void touch() {
        String column = touchColumn();
        if (column == null || getParent().getAttribute(getParentKeyName()) == null) {
            return;
        }
        List<Object> keys = newPivotQuery().pluck(getRelatedPivotKeyName());
        int size = Math.max(1, activeFactory().grammar().eagerKeyChunkSize() - TOUCH_SET_BINDINGS);
        // Bindings added by the related model's global scopes are not counted in the chunk size.
        // Not in one transaction: each chunk only sets a timestamp, so a failure part way is harmless
        // and a repeat heals it, and the related model may live on another connection.
        for (List<Object> chunk : EagerKeys.chunks(keys, size)) {
            Access.eloquent().newQueryOn(getRelated())
                    .whereIn(getQualifiedRelatedKeyName(), chunk)
                    .update(Collections.<String, Object>emptyMap());
        }
    }

    // ---- pivots and pivot queries

    /**
     * Creates a pivot of this relation.
     *
     * @param attributes the pivot columns, never {@code null}; the values of {@code withPivotValue}
     *     sit under them
     * @param exists whether the row is in the database
     * @return the new pivot, never {@code null}
     */
    public abstract Pivot newPivot(Map<String, ?> attributes, boolean exists);

    /**
     * Creates a pivot of this relation for a row that is in the database, with its keys and related
     * model set.
     *
     * @param attributes the pivot columns, never {@code null}
     * @return the pivot, marked as existing, never {@code null}
     */
    public final Pivot newExistingPivot(Map<String, ?> attributes) {
        return keyed(newPivot(attributes, true));
    }

    /**
     * Returns a query on the pivot table for the parent: the relation's pivot conditions in one group,
     * in the order they were declared, then the parent's key.
     *
     * @return a new core query, never {@code null}
     * @throws IllegalStateException if the parent has no key value
     */
    public final Builder newPivotQuery() {
        Builder query = newPivotStatement();
        List<PivotClause> clauses = pivotClauses();
        if (!clauses.isEmpty()) {
            query.where(group -> {
                for (PivotClause clause : clauses) {
                    clause.applyTo(group);
                }
            });
        }
        query.where(getQualifiedForeignPivotKeyName(), "=", requireParentKey());
        scopePivotQuery(query);
        return query;
    }

    /**
     * Narrows the pivot query a subclass owns, so a relation over a shared pivot table reads and
     * writes only its own rows; the default adds nothing.
     *
     * @param query the pivot query being returned, never {@code null}
     */
    void scopePivotQuery(Builder query) {
    }

    /**
     * Refuses an insert a subclass cannot type, so a relation that cannot fill the pivot's
     * discriminating columns fails before any row is written; the default accepts every insert.
     *
     * @throws UnsupportedOperationException if the relation cannot write a typed pivot row
     */
    void requirePivotWritable() {
    }

    /**
     * Returns a query on the pivot table with no condition, not even the parent's key.
     *
     * @return a new core query, never {@code null}
     */
    public final Builder newPivotStatement() {
        return activeFactory().table(getTable());
    }

    // ---- members for the layers above

    /**
     * Runs work in a transaction on the parent's connection, or on the open transaction's when called
     * inside one. Statements built by this class meanwhile use the transaction's own factory.
     *
     * @param <T> the result type
     * @param work the work, never {@code null}
     * @return the work's result
     */
    <T> T inTransaction(Supplier<T> work) {
        QueryFactory base = activeFactory();
        return base.connection().transaction(connection -> {
            QueryFactory prior = active;
            if (connection != base.connection()) {
                active = QueryFactory.of(connection, base.grammar(), base.processor());
            }
            try {
                return work.get();
            } finally {
                active = prior;
            }
        });
    }

    /**
     * Returns the factory statements run on: the open transaction's, or the parent's own.
     *
     * @return the factory, never {@code null}
     */
    QueryFactory activeFactory() {
        return active != null ? active : getParent().getQueryFactory();
    }

    /**
     * Returns the parent's key value.
     *
     * @return the value, never {@code null}
     * @throws IllegalStateException if the parent has no key value
     */
    Object requireParentKey() {
        Object key = getParent().getAttribute(getParentKeyName());
        if (key == null) {
            String name = getRelationName() == null ? getTable() : getRelationName();
            throw new IllegalStateException("Cannot write through relation [" + name + "] of ["
                    + getParent().getClass().getName() + "]: the parent has no [" + getParentKeyName()
                    + "] value; save the parent first");
        }
        return key;
    }

    /**
     * Returns the columns every new row carries after the related key: the foreign key, the timestamps
     * the relation keeps, and the values of {@code withPivotValue}, in write order.
     *
     * @return a new map in column order
     * @throws IllegalStateException if the parent has no key value
     */
    Map<String, Object> recordTail() {
        Map<String, Object> tail = new LinkedHashMap<>();
        tail.put(getForeignPivotKeyName(), requireParentKey());
        if (createdAt() != null || updatedAt() != null) {
            Object now = timestampValue();
            if (createdAt() != null) {
                tail.put(createdAt(), now);
            }
            if (updatedAt() != null) {
                tail.put(updatedAt(), now);
            }
        }
        tail.putAll(pivotValues());
        return tail;
    }

    /**
     * Casts a related key the way a sync result reports it.
     *
     * @param id the key, in any spelling
     * @return a {@code Long} for an integer related key, text otherwise
     */
    Object castKey(Object id) {
        return PivotRecords.castKey(id, isIntegerKey(getRelated(), getRelatedKeyName()));
    }

    /**
     * Deletes the rows of the given related keys. No query runs for an empty collection.
     *
     * @param ids the related keys, as given
     * @return the number of rows deleted; with a {@code using} class the number of pivots deleted
     */
    int detachInternal(Collection<Object> ids) {
        return runChunked(idChunks(ids, 0), this::deleteChunk);
    }

    /**
     * Inserts rows built by {@link PivotRecords#record}. More than one statement runs in a transaction.
     *
     * @param rows the rows, all with the same columns; an empty list writes nothing
     * @throws IllegalArgumentException if two rows name different columns
     */
    void attachInternal(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        requirePivotWritable();
        PivotRecords.requireSameColumns(rows);
        if (usingClass() != null) {
            inTransactionIf(rows.size() > 1, () -> saveEach(rows));
            return;
        }
        int perStatement = PivotRecords.rowsPerStatement(rows.get(0).size(),
                activeFactory().grammar().eagerKeyChunkSize());
        List<List<Map<String, Object>>> chunks = EagerKeys.chunks(rows, perStatement);
        inTransactionIf(chunks.size() > 1, () -> insertChunks(chunks));
    }

    /**
     * Updates the pivot attributes of linked rows. Writes no touch.
     *
     * @param id an id, a related model or a collection of either
     * @param attributes the columns and values to set, never {@code null}
     * @return the rows updated, or with a {@code using} class the pivots that changed
     */
    int updateInternal(Object id, Map<String, ?> attributes) {
        Map<String, Object> values = checked(attributes);
        List<Object> ids = PivotRecords.parse(id, getRelatedKeyName()).ids();
        if (ids.isEmpty()) {
            return 0;
        }
        if (usingClass() != null) {
            return runChunked(idChunks(ids, 0), chunk -> updatePivots(chunk, values));
        }
        if (updatedAt() != null) {
            values.put(updatedAt(), timestampValue());
        }
        if (values.isEmpty()) {
            return 0;
        }
        return runChunked(idChunks(ids, values.size()), chunk ->
                newPivotQuery().whereIn(getQualifiedRelatedPivotKeyName(), chunk).update(values));
    }

    /**
     * Touches the owners after a write: the parent when the related class lists it in {@code touches()}
     * under the guessed inverse name, and this relation's related rows when the parent lists the
     * relation. The guess is the plural camel case of the parent's class name, so an irregular plural
     * may not match. Call it after the transaction.
     *
     * @param touch whether the caller asked for a touch
     * @param changed whether the write changed any row
     * @return {@code true} when the touch ran
     */
    boolean touchOwners(boolean touch, boolean changed) {
        if (!touch || !changed) {
            return false;
        }
        String inverse = InverseRelations.camel(Inflector.plural(getParent().getClass().getSimpleName()));
        if (RelationTouches.config(getRelated()).names().contains(inverse)) {
            getParent().touch();
        }
        if (isTouchedByParent()) {
            touch();
        }
        return true;
    }

    // A relation built by calling the model's method directly has no name, so the parent's
    // touches() entries are matched by the join they name, as Laravel's guessed name would be.
    private boolean isTouchedByParent() {
        List<String> names = RelationTouches.config(getParent()).names();
        if (getRelationName() != null) {
            return names.contains(getRelationName());
        }
        for (String name : names) {
            Relation<?, ?> named = getParent().relation(name);
            if (named instanceof PivotClauses && isSameJoin((PivotClauses<?>) named)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameJoin(PivotClauses<?> other) {
        return getTable().equals(other.getTable())
                && getForeignPivotKeyName().equals(other.getForeignPivotKeyName())
                && getRelatedPivotKeyName().equals(other.getRelatedPivotKeyName())
                && getRelated().getClass() == other.getRelated().getClass();
    }

    /**
     * Creates a pivot for a write: keys and related model set, rebound to the open transaction.
     *
     * @param attributes the pivot columns, never {@code null}
     * @param exists whether the row is in the database
     * @return the pivot, never {@code null}
     */
    Pivot pivotOn(Map<String, Object> attributes, boolean exists) {
        Pivot pivot = keyed(newPivot(attributes, exists));
        if (active != null) {
            Access.eloquent().swapFactory(pivot, active);
        }
        return pivot;
    }

    /**
     * Tells that this layer keeps the relation touchable, so a model may list it in {@code touches()}.
     *
     * @return {@code true}
     */
    @Override
    final boolean supportsTouch() {
        return true;
    }

    // ---- private

    private List<Map<String, Object>> attachRecords(Object ids, Map<String, ?> shared) {
        PivotRecords.IdSet set = PivotRecords.withSharedAttributes(PivotRecords.parse(ids, getRelatedKeyName()), shared);
        List<Object> keys = ids instanceof Map ? set.ids() : PivotRecords.parseIds(ids, getRelatedKeyName());
        return recordsFor(set, keys);
    }

    /**
     * Builds the join table rows for the ids, each with its own attributes from the set, a dotted
     * column name refused.
     *
     * @param set the parsed ids with their attributes, never {@code null}
     * @param ids the ids to build a row for, in order, never {@code null}
     * @return the rows, never {@code null}
     * @throws IllegalArgumentException if an attribute name is dotted
     */
    final List<Map<String, Object>> recordsFor(PivotRecords.IdSet set, List<Object> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> tail = recordTail();
        List<Map<String, Object>> rows = new ArrayList<>(ids.size());
        for (Object id : ids) {
            rows.add(PivotRecords.record(getRelatedPivotKeyName(), id, tail, checked(set.attributesOf(id))));
        }
        return rows;
    }

    private Map<String, Object> checked(Map<String, ?> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        String label = label();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : attributes.entrySet()) {
            PivotColumns.requireUndotted(entry.getKey(), label);
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private String label() {
        String name = getRelationName() != null ? getRelationName() : getTable();
        return "relation [" + name + "] on model [" + getParent().getClass().getName() + "]";
    }

    // The value of a new row's timestamps: the parent's clock, or the pivot class's own form with a
    // using class, as Laravel's fromDateTime of that class.
    private Object timestampValue() {
        if (usingClass() == null) {
            return getParent().freshTimestampValue();
        }
        return newPivot(Collections.<String, Object>emptyMap(), false).freshTimestampValue();
    }

    private Pivot keyed(Pivot pivot) {
        return pivot.setPivotKeys(getForeignPivotKeyName(), getRelatedPivotKeyName()).setRelatedModel(getRelated());
    }

    // The ids that fit one statement: the budget less what the statement already binds.
    private List<List<Object>> idChunks(Collection<Object> ids, int extraBindings) {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        int used = newPivotQuery().getBindings().size() + extraBindings;
        int size = Math.max(1, activeFactory().grammar().eagerKeyChunkSize() - used);
        return EagerKeys.chunks(new ArrayList<>(ids), size);
    }

    private int runChunked(List<List<Object>> chunks, ToIntFunction<List<Object>> work) {
        if (chunks.isEmpty()) {
            return 0;
        }
        return inTransactionIf(chunks.size() > 1, () -> {
            int total = 0;
            for (List<Object> chunk : chunks) {
                total += work.applyAsInt(chunk);
            }
            return total;
        });
    }

    private <T> T inTransactionIf(boolean isNeeded, Supplier<T> work) {
        return isNeeded ? inTransaction(work) : work.get();
    }

    private int deleteChunk(List<Object> chunk) {
        Builder query = newPivotQuery().whereIn(getQualifiedRelatedPivotKeyName(), chunk);
        return usingClass() == null ? query.delete() : deletePivots(query);
    }

    private int deletePivots(Builder query) {
        int deleted = 0;
        for (Map<String, Object> row : query.get()) {
            if (pivotOn(row, true).delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    private int updatePivots(List<Object> chunk, Map<String, Object> values) {
        int changed = 0;
        for (Map<String, Object> row : newPivotQuery().whereIn(getQualifiedRelatedPivotKeyName(), chunk).get()) {
            Pivot pivot = pivotOn(row, true);
            pivot.fill(values);
            if (pivot.isDirty() && pivot.save()) {
                changed++;
            }
        }
        return changed;
    }

    private int insertChunks(List<List<Map<String, Object>>> chunks) {
        int inserted = 0;
        for (List<Map<String, Object>> chunk : chunks) {
            newPivotStatement().insert(chunk);
            inserted += chunk.size();
        }
        return inserted;
    }

    private int saveEach(List<Map<String, Object>> rows) {
        int saved = 0;
        for (Map<String, Object> row : rows) {
            if (pivotOn(row, false).save()) {
                saved++;
            }
        }
        return saved;
    }
}
