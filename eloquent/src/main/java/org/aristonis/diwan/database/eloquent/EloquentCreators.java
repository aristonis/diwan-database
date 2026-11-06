package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.exception.MassAssignmentException;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.query.Builder;

/**
 * The key clauses, creators and bulk writes of {@link EloquentBuilder}.
 *
 * @param <T> the model class
 */
abstract class EloquentCreators<T extends Model> extends EloquentClauses<T> {

    // Null for every builder a user gets; only relation code sets it, on a copy it owns.
    private Consumer<? super T> rowHook;

    EloquentCreators(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
    }

    /**
     * Sets a hook that runs on every model this builder hydrates, replacing any existing hook.
     * Pass {@code null} to clear the hook.
     *
     * @param hook the per-row hook, or {@code null} to clear
     */
    final void useRowHook(Consumer<? super T> hook) {
        rowHook = hook;
    }

    /**
     * Returns the per-row hook, or {@code null} when none is set.
     *
     * @return the hook, or {@code null}
     */
    final Consumer<? super T> rowHook() {
        return rowHook;
    }

    // ---- key clauses ----

    /**
     * Adds a clause on the qualified primary key: {@code users.id = ?}, or {@code in} for a
     * collection. A model gives its key; a string-key model compares the key as text. Both apply
     * to each element of a collection.
     *
     * @param id the key, a model, or a collection of keys
     * @return this builder
     */
    public final EloquentBuilder<T> whereKey(Object id) {
        if (id instanceof Collection) {
            query.whereIn(model.getQualifiedKeyName(), keyValues((Collection<?>) id));
        } else {
            query.where(model.getQualifiedKeyName(), "=", keyValue(id));
        }
        return self();
    }

    /**
     * Adds a clause excluding a primary key: {@code users.id != ?}, or {@code not in} for a
     * collection.
     *
     * @param id the key, a model, or a collection of keys
     * @return this builder
     */
    public final EloquentBuilder<T> whereKeyNot(Object id) {
        if (id instanceof Collection) {
            query.whereNotIn(model.getQualifiedKeyName(), keyValues((Collection<?>) id));
        } else {
            query.where(model.getQualifiedKeyName(), "!=", keyValue(id));
        }
        return self();
    }

    private List<Object> keyValues(Collection<?> ids) {
        List<Object> keys = new ArrayList<>(ids.size());
        for (Object id : ids) {
            keys.add(keyValue(id));
        }
        return keys;
    }

    private Object keyValue(Object id) {
        Object key = id instanceof Model ? ((Model) id).getKey() : id;
        return key != null && "string".equals(model.getKeyType()) ? key.toString() : key;
    }

    // ---- creating ----

    /**
     * Returns a new, unsaved model filled from a map.
     *
     * @param values the attributes, never {@code null}
     * @return the model, never {@code null}
     * @throws MassAssignmentException as {@link Model#fill(Map)}
     */
    @SuppressWarnings("unchecked")
    public final T make(Map<String, ?> values) {
        return (T) source.newModel().fill(values);
    }

    /**
     * Fills a new model from a map and saves it.
     *
     * @param values the attributes, never {@code null}
     * @return the model; saved unless a hook vetoed it
     * @throws MassAssignmentException as {@link Model#fill(Map)}
     */
    public final T create(Map<String, ?> values) {
        T created = make(values);
        created.save();
        return created;
    }

    /**
     * Fills a new model from a map ignoring the guards, and saves it.
     *
     * @param values the attributes, never {@code null}
     * @return the model; saved unless a hook vetoed it
     */
    public final T forceCreate(Map<String, ?> values) {
        return Model.unguarded(() -> create(values));
    }

    /**
     * Returns the first model matching the attributes, or a new unsaved one filled from them.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     */
    public final T firstOrNew(Map<String, ?> attributes) {
        return firstOrNew(attributes, new LinkedHashMap<String, Object>());
    }

    /**
     * Returns the first model matching the attributes, or a new unsaved one filled from the
     * attributes and the values.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for a new model, never {@code null}
     * @return the model, never {@code null}
     * @throws MassAssignmentException as {@link Model#fill(Map)}, for a new model
     */
    public final T firstOrNew(Map<String, ?> attributes, Map<String, ?> values) {
        Optional<T> found = copy().where(attributes).first();
        return found.isPresent() ? found.get() : make(merge(attributes, values));
    }

    /**
     * Returns the first model matching the attributes, or creates one from them.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     */
    public final T firstOrCreate(Map<String, ?> attributes) {
        return firstOrCreate(attributes, new LinkedHashMap<String, Object>());
    }

    /**
     * Returns the first model matching the attributes, or creates one from the attributes and
     * the values. Creation goes through {@link #createOrFirst(Map, Map)}, so a row inserted by
     * someone else in between is returned when a unique index refuses the duplicate.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for a new model, never {@code null}
     * @return the model, never {@code null}
     * @throws MassAssignmentException as {@link Model#fill(Map)}, for a new model
     */
    public final T firstOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        Optional<T> found = copy().where(attributes).first();
        return found.isPresent() ? found.get() : createOrFirst(attributes, values);
    }

    /**
     * Creates a model from the attributes and values; when a unique index refuses it, returns the
     * existing match instead. Inside a transaction the insert runs in a savepoint so the refusal
     * does not abort the transaction.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for the new model, never {@code null}
     * @return the model, never {@code null}
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final T createOrFirst(Map<String, ?> attributes, Map<String, ?> values) {
        Map<String, Object> merged = merge(attributes, values);
        try {
            Connection connection = model.getConnection();
            if (connection.transactionLevel() > 0) {
                return connection.transaction(c -> create(merged));
            }
            return create(merged);
        } catch (UniqueConstraintViolationException e) {
            Optional<T> found = copy().where(attributes).first();
            if (found.isPresent()) {
                return found.get();
            }
            throw e;
        }
    }

    /**
     * Updates the first model matching the attributes with the values, or creates one from both.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values the attributes to set, never {@code null}
     * @return the model, never {@code null}
     * @throws MassAssignmentException as {@link Model#fill(Map)}
     */
    public final T updateOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        T found = firstOrCreate(attributes, values);
        if (!found.wasRecentlyCreated()) {
            found.fill(values).save();
        }
        return found;
    }

    private static Map<String, Object> merge(Map<String, ?> attributes, Map<String, ?> values) {
        Map<String, Object> merged = new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes"));
        merged.putAll(Objects.requireNonNull(values, "values"));
        return merged;
    }

    // Laravel's clone keeps the eager loads and casts; the alias counter is copied, not shared.
    @Override
    final EloquentBuilder<T> copy() {
        EloquentBuilder<T> copied = new EloquentBuilder<>(source, model, query.clone());
        copied.copyScopesFrom(this);
        self().copyRelationStateTo(copied);
        self().copyAggregateStateTo(copied);
        copied.useRowHook(rowHook);
        return copied;
    }

    // ---- bulk writes ----

    /**
     * Updates every matching row, setting {@code updated_at} too when the model keeps
     * timestamps and the values do not name it. No model hooks run.
     *
     * @param values the columns and values, never {@code null}
     * @return the affected rows
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the update
     */
    public final int update(Map<String, ?> values) {
        return scoped().update(addUpdatedAtColumn(values));
    }

    /**
     * Adds 1 to a column of every matching row, setting {@code updated_at} too.
     *
     * @param column the column, never {@code null}
     * @return the affected rows
     */
    public final int increment(String column) {
        return increment(column, 1);
    }

    /**
     * Adds an amount to a column of every matching row, setting {@code updated_at} too.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @return the affected rows
     */
    public final int increment(String column, Number amount) {
        return increment(column, amount, new LinkedHashMap<String, Object>());
    }

    /**
     * Adds an amount to a column of every matching row, setting the extra columns and
     * {@code updated_at} too.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @param extra other columns to set, never {@code null}
     * @return the affected rows
     */
    public final int increment(String column, Number amount, Map<String, ?> extra) {
        return scoped().increment(column, amount, addUpdatedAtColumn(extra));
    }

    /**
     * Subtracts 1 from a column of every matching row, setting {@code updated_at} too.
     *
     * @param column the column, never {@code null}
     * @return the affected rows
     */
    public final int decrement(String column) {
        return decrement(column, 1);
    }

    /**
     * Subtracts an amount from a column of every matching row, setting {@code updated_at} too.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @return the affected rows
     */
    public final int decrement(String column, Number amount) {
        return decrement(column, amount, new LinkedHashMap<String, Object>());
    }

    /**
     * Subtracts an amount from a column of every matching row, setting the extra columns and
     * {@code updated_at} too.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @param extra other columns to set, never {@code null}
     * @return the affected rows
     */
    public final int decrement(String column, Number amount, Map<String, ?> extra) {
        return scoped().decrement(column, amount, addUpdatedAtColumn(extra));
    }

    /**
     * Deletes every matching row. No model hooks run. A soft-deleting model is not deleted: the
     * rows get the deleted-at column (and {@code updated_at} when the model keeps timestamps) set
     * to one moment, whether or not the soft-deleting scope was removed from this builder.
     *
     * @return the affected rows
     */
    public final int delete() {
        return model.isSoftDeletable() ? softDeleteRows() : scoped().delete();
    }

    /**
     * Deletes every matching row for good, skipping the soft-deleting scope. No model hooks run.
     *
     * @return the affected rows
     * @throws IllegalStateException if the model does not soft delete
     */
    public final int forceDelete() {
        requireSoftDeletes("forceDelete()");
        return copy().withoutGlobalScope(SoftDeletingScope.NAME).scoped().delete();
    }

    /**
     * Restores every matching soft-deleted row by clearing the deleted-at column, also when the
     * soft-deleting scope would hide it. No model hooks run.
     *
     * @return the affected rows
     * @throws IllegalStateException if the model does not soft delete
     */
    public final int restore() {
        requireSoftDeletes("restore()");
        Map<String, Object> cleared = new LinkedHashMap<>();
        cleared.put(deletedAtSetColumn(), null);
        return copy().withTrashed().update(cleared);
    }

    /**
     * Returns the first model matching the attributes, trashed or not, creating one when none
     * matches; a trashed match is restored.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     * @throws IllegalStateException if the model does not soft delete
     */
    public final T restoreOrCreate(Map<String, ?> attributes) {
        return restoreOrCreate(attributes, new LinkedHashMap<String, Object>());
    }

    /**
     * Returns the first model matching the attributes, trashed or not, creating one from the
     * attributes and the values when none matches; a trashed match is restored.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for a new model, never {@code null}
     * @return the model, never {@code null}
     * @throws IllegalStateException if the model does not soft delete
     * @throws MassAssignmentException as {@link Model#fill(Map)}, for a new model
     */
    public final T restoreOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        requireSoftDeletes("restoreOrCreate()");
        return restoredIfTrashed(copy().withTrashed().firstOrCreate(attributes, values));
    }

    /**
     * Creates a model from the attributes, or returns the existing match, trashed or not, when a
     * unique index refuses it; a trashed match is restored.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     * @throws IllegalStateException if the model does not soft delete
     */
    public final T createOrRestore(Map<String, ?> attributes) {
        return createOrRestore(attributes, new LinkedHashMap<String, Object>());
    }

    /**
     * Creates a model from the attributes and values, or returns the existing match, trashed or
     * not, when a unique index refuses it; a trashed match is restored.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for the new model, never {@code null}
     * @return the model, never {@code null}
     * @throws IllegalStateException if the model does not soft delete
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final T createOrRestore(Map<String, ?> attributes, Map<String, ?> values) {
        requireSoftDeletes("createOrRestore()");
        return restoredIfTrashed(copy().withTrashed().createOrFirst(attributes, values));
    }

    private T restoredIfTrashed(T found) {
        if (found.trashed()) {
            found.restore();
        }
        return found;
    }

    // One moment for both columns; the update adds updated_at itself when the model keeps timestamps.
    private int softDeleteRows() {
        Map<String, Object> values = new LinkedHashMap<>();
        Object moment = model.freshTimestampValue();
        values.put(deletedAtSetColumn(), moment);
        String updatedAt = model.getUpdatedAtColumn();
        if (model.usesTimestamps() && updatedAt != null) {
            values.put(updatedAt, moment);
        }
        return update(values);
    }

    // The deleted-at column in an UPDATE: always bare, since SQLite and PostgreSQL refuse a qualified
    // SET column; a joined update is rewritten by the core to a keyed subquery.
    private String deletedAtSetColumn() {
        return model.getDeletedAtColumn();
    }

    // Laravel puts the table-qualified updated_at last; the SQLite and PostgreSQL grammars then
    // drop the qualifier from a SET column, so the unqualified name gives the same SQL.
    private Map<String, Object> addUpdatedAtColumn(Map<String, ?> values) {
        Map<String, Object> out = new LinkedHashMap<>(Objects.requireNonNull(values, "values"));
        String column = model.getUpdatedAtColumn();
        if (!model.usesTimestamps() || column == null) {
            return out;
        }
        Object value = out.containsKey(column) ? out.remove(column) : model.freshTimestampValue();
        out.put(column, value);
        return out;
    }

    // ---- upsert and insert or ignore ----

    /**
     * Inserts the rows, and where a row conflicts on the unique columns, sets every column of the
     * first row from the row that was refused, plus {@code updated_at}.
     *
     * <p>Like Laravel, it goes straight to the database: no model events run and no casts apply.
     * Rows without the key of a model that generates unique ids get one. A model that keeps
     * timestamps gets {@code created_at} and {@code updated_at} on every row, one moment for all
     * of them, unless the row names the column; {@code updated_at} joins the columns that are
     * updated, {@code created_at} does not.
     *
     * @param rows the rows, never {@code null}, all with the same columns
     * @param uniqueBy the columns of the unique index that decides a conflict, never {@code null}
     *     or empty
     * @return the number of rows written, as the database reports it; 0 for no rows
     * @throws NullPointerException if an argument or a row is {@code null}
     * @throws IllegalArgumentException if {@code uniqueBy} is empty or the rows are not alike
     * @throws UnsupportedOperationException if the database cannot upsert, as SQLite before 3.24
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the statement
     */
    public final int upsert(List<? extends Map<String, ?>> rows, List<String> uniqueBy) {
        return upsert(rows, uniqueBy, null);
    }

    /**
     * Inserts the rows, and where a row conflicts on the unique columns, sets the named columns
     * from the row that was refused, plus {@code updated_at} when the model keeps timestamps; see
     * {@link EloquentBuilder#upsert(List, List)}.
     *
     * @param rows the rows, never {@code null}, all with the same columns
     * @param uniqueBy the columns of the unique index that decides a conflict, never {@code null}
     *     or empty
     * @param update the columns to take from the refused row; {@code null} for every column of
     *     the first row as given; an empty list leaves a conflicting row as it is, with no
     *     {@code updated_at} either
     * @return the number of rows written, as the database reports it; 0 for no rows
     * @throws NullPointerException if {@code rows}, {@code uniqueBy} or a row is {@code null}
     * @throws IllegalArgumentException if {@code uniqueBy} is empty or the rows are not alike
     * @throws UnsupportedOperationException if the database cannot upsert, as SQLite before 3.24
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the statement
     */
    public final int upsert(List<? extends Map<String, ?>> rows, List<String> uniqueBy, List<String> update) {
        Objects.requireNonNull(rows, "rows");
        List<String> columns = update;
        if (columns == null) {
            columns = rows.isEmpty()
                    ? new ArrayList<>()
                    : new ArrayList<>(Objects.requireNonNull(rows.get(0), "row").keySet());
        }
        return toBase().upsert(withTimestamps(withUniqueIds(rows)), uniqueBy, withUpdatedAt(columns));
    }

    /**
     * Inserts one row, skipping it when it would break a unique or primary key constraint, with
     * the key and timestamps filled as for {@link EloquentBuilder#upsert(List, List)}.
     *
     * @param row the columns and values, never {@code null}
     * @return the number of rows inserted, 0 or 1
     * @throws NullPointerException if {@code row} is {@code null}
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the insert
     */
    public final int insertOrIgnore(Map<String, ?> row) {
        return insertOrIgnore(Collections.singletonList(Objects.requireNonNull(row, "row")));
    }

    /**
     * Inserts the rows in one statement, skipping each that would break a unique or primary key
     * constraint. No model events run. Rows without the key of a model that generates unique ids
     * get one, and a model that keeps timestamps gets them, one moment for all rows, unless a row
     * names the column.
     *
     * @param rows the rows, never {@code null}, all with the same columns
     * @return the number of rows inserted, as the database reports it; 0 for no rows
     * @throws NullPointerException if {@code rows} or a row is {@code null}
     * @throws IllegalArgumentException if the rows are not alike
     * @throws org.aristonis.diwan.database.exception.QueryException if the database refuses the insert
     */
    public final int insertOrIgnore(List<? extends Map<String, ?>> rows) {
        Objects.requireNonNull(rows, "rows");
        return toBase().insertOrIgnore(withTimestamps(withUniqueIds(rows)));
    }

    // The key goes first, as in Laravel; a row that names the key keeps its value.
    private List<Map<String, Object>> withUniqueIds(List<? extends Map<String, ?>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        boolean generates = model.getUniqueIdKind() != UniqueIdKind.NONE;
        for (Map<String, ?> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            if (generates && !Objects.requireNonNull(row, "row").containsKey(model.getKeyName())) {
                copy.put(model.getKeyName(), model.newUniqueId());
            }
            copy.putAll(Objects.requireNonNull(row, "row"));
            out.add(copy);
        }
        return out;
    }

    // One moment for every row and both columns; a column the row names keeps the row's value.
    private List<Map<String, Object>> withTimestamps(List<Map<String, Object>> rows) {
        if (!model.usesTimestamps()) {
            return rows;
        }
        Object moment = model.freshTimestampValue();
        List<String> columns = new ArrayList<>(2);
        for (String column : new String[] {model.getCreatedAtColumn(), model.getUpdatedAtColumn()}) {
            if (column != null) {
                columns.add(column);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String column : columns) {
                if (!row.containsKey(column)) {
                    copy.put(column, moment);
                }
            }
            copy.putAll(row);
            out.add(copy);
        }
        return out;
    }

    // An empty list stays empty: the caller asked for conflicting rows to be left alone.
    private List<String> withUpdatedAt(List<String> update) {
        String column = model.getUpdatedAtColumn();
        if (!model.usesTimestamps() || column == null || update.isEmpty() || update.contains(column)) {
            return update;
        }
        List<String> out = new ArrayList<>(update);
        out.add(column);
        return out;
    }
}
