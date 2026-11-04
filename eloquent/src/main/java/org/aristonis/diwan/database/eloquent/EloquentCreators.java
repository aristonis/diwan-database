package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.UniqueConstraintViolationException;
import org.aristonis.diwan.database.query.Builder;

/**
 * The key clauses, creators and bulk writes of {@link EloquentBuilder}.
 *
 * @param <T> the model class
 */
abstract class EloquentCreators<T extends Model> extends EloquentClauses<T> {

    EloquentCreators(ModelQuery<T> source, T model, Builder query) {
        super(source, model, query);
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
    final EloquentBuilder<T> copy() {
        EloquentBuilder<T> copied = new EloquentBuilder<>(source, model, query.clone());
        self().copyRelationStateTo(copied);
        self().copyAggregateStateTo(copied);
        return copied;
    }

    // ---- bulk writes ----

    /**
     * Updates every matching row, setting {@code updated_at} too when the model keeps
     * timestamps and the values do not name it. No model hooks run.
     *
     * @param values the columns and values, never {@code null}
     * @return the affected rows
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the update
     */
    public final int update(Map<String, ?> values) {
        return query.update(addUpdatedAtColumn(values));
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
        return query.increment(column, amount, addUpdatedAtColumn(extra));
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
        return query.decrement(column, amount, addUpdatedAtColumn(extra));
    }

    /**
     * Deletes every matching row. No model hooks run.
     *
     * @return the affected rows
     */
    public final int delete() {
        return query.delete();
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
}
