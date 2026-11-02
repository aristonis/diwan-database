package org.aristonis.diwan.database.eloquent;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.RandomAccess;

/**
 * A read-only list of models, with Laravel's Eloquent collection helpers.
 *
 * <p>Every {@code List} method that changes the list throws
 * {@link UnsupportedOperationException}. Unlike a plain list, {@link #contains(Object)} matches a
 * model as {@link Model#is(Model)} does and any other value as a primary key, as Laravel's
 * collection does. Not safe to share across threads while its models change.
 *
 * @param <T> the model class
 */
public final class ModelCollection<T extends Model> extends AbstractList<T> implements RandomAccess {

    private final List<T> models;
    private final ModelQuery<T> source;

    ModelCollection(List<T> models, ModelQuery<T> source) {
        this.models = Collections.unmodifiableList(new ArrayList<>(models));
        this.source = source;
    }

    /**
     * Returns the model at a position.
     *
     * @param index the position, from 0
     * @return the model, never {@code null}
     * @throws IndexOutOfBoundsException if the position is outside the list
     */
    @Override
    public T get(int index) {
        return models.get(index);
    }

    /**
     * Returns the number of models.
     *
     * @return the size
     */
    @Override
    public int size() {
        return models.size();
    }

    /**
     * Returns the first model whose primary key matches, compared loosely as PHP's {@code ==}:
     * {@code 1}, {@code 1L} and {@code "1"} all match the key 1.
     *
     * @param key the key, or a model whose key to use
     * @return the model, or empty
     */
    public Optional<T> find(Object key) {
        Object wanted = key instanceof Model ? ((Model) key).getKey() : key;
        for (T model : models) {
            if (AttributeValues.looseKey(model.getKey(), wanted)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether the list holds the same row as a model, or a model with a primary key.
     *
     * @param value a model, compared with {@link Model#is(Model)}, or a key, compared as
     *     {@link #find(Object)} does
     * @return whether it is held
     */
    @Override
    public boolean contains(Object value) {
        if (value instanceof Model) {
            for (T model : models) {
                if (model.is((Model) value)) {
                    return true;
                }
            }
            return false;
        }
        return value != null && find(value).isPresent();
    }

    /**
     * Returns the primary keys of the models, in list order.
     *
     * @return the keys, unmodifiable, never {@code null}
     */
    public List<Object> modelKeys() {
        List<Object> keys = new ArrayList<>(models.size());
        for (T model : models) {
            keys.add(model.getKey());
        }
        return Collections.unmodifiableList(keys);
    }

    /**
     * Reloads the models with one query, keeping this list's order and leaving out models whose
     * row is gone.
     *
     * @return a new collection of new instances, never {@code null}
     * @throws IllegalStateException if the models have no connection
     */
    public ModelCollection<T> fresh() {
        if (models.isEmpty()) {
            return this;
        }
        ModelCollection<T> loaded = source.query().findMany(modelKeys());
        List<T> ordered = new ArrayList<>(models.size());
        for (T model : models) {
            Optional<T> match = model.exists() ? loaded.find(model.getKey()) : Optional.<T>empty();
            if (match.isPresent()) {
                ordered.add(match.get());
            }
        }
        return new ModelCollection<>(ordered, source);
    }
}
