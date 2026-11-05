package org.aristonis.diwan.database.eloquent;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.Relation;

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

    private static final String ANY_COLUMN = "*";
    private static final String COUNT = "count";
    private static final String EXISTS = "exists";

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
        ModelCollection<T> loaded = source.query().withoutGlobalScopes().findMany(modelKeys());
        List<T> ordered = new ArrayList<>(models.size());
        for (T model : models) {
            Optional<T> match = model.exists() ? loaded.find(model.getKey()) : Optional.<T>empty();
            if (match.isPresent()) {
                ordered.add(match.get());
            }
        }
        return new ModelCollection<>(ordered, source);
    }

    /**
     * Eager loads relations onto every model, one query per relation per level, replacing any
     * loaded value. Paths are as {@link EloquentBuilder#with(String...)}.
     *
     * @param relations the relation paths, none {@code null}
     * @return this collection
     * @throws NullPointerException if a path is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class, or a
     *     {@code name:columns} path lists an empty column
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a path
     *     names no relation; checked before any query
     */
    public ModelCollection<T> load(String... relations) {
        EloquentRelationQueries.eagerLoad(models, WithParser.parse(relations), false);
        return this;
    }

    /**
     * Eager loads one relation onto every model, letting a callback constrain its query, as
     * {@link EloquentBuilder#with(String, Consumer)}.
     *
     * @param relation the relation path, never {@code null}
     * @param constraint the constraint on the relation's query, never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if the path
     *     names no relation
     */
    public ModelCollection<T> load(String relation, Consumer<EloquentBuilder<?>> constraint) {
        return load(Collections.singletonMap(Objects.requireNonNull(relation, "relation"),
                Objects.requireNonNull(constraint, "constraint")));
    }

    /**
     * Eager loads relations onto every model, each with a callback constraining its query.
     * An empty collection or map runs nothing.
     *
     * @param relations the constraints by relation path, no key or value {@code null}
     * @return this collection
     * @throws NullPointerException if the map, a path or a constraint is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a path
     *     names no relation
     */
    public ModelCollection<T> load(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        EloquentRelationQueries.eagerLoad(models, WithParser.parse(relations), false);
        return this;
    }

    /**
     * Eager loads the relations not loaded yet, level by level: for {@code "posts.comments"},
     * posts on the models without them, then comments on every loaded post without them. A loaded
     * {@code null} or empty value counts as loaded and is not read again.
     *
     * @param relations the relation paths, none {@code null}
     * @return this collection
     * @throws NullPointerException if a path is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a path
     *     names no relation
     */
    public ModelCollection<T> loadMissing(String... relations) {
        EloquentRelationQueries.eagerLoad(models, WithParser.parse(relations), true);
        return this;
    }

    // ---- relation aggregates

    /**
     * Loads a {@code count(*)} per relation onto every model, named {@code relation_count}, in one
     * key-only query. A model whose row is gone or whose key is {@code null} is skipped; the
     * values become original attributes, so they are not dirty.
     *
     * @param relations the relation names, each optionally {@code "name as alias"}, none
     *     {@code null}; none, or an empty collection, runs nothing
     * @return this collection
     * @throws NullPointerException if a name is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     */
    public ModelCollection<T> loadCount(String... relations) {
        return aggregate(WithParser.parseAggregates(relations), ANY_COLUMN, COUNT);
    }

    /**
     * Loads a {@code count(*)} per relation onto every model, each constrained by its callback.
     *
     * @param relations the constraints by relation name, each name optionally
     *     {@code "name as alias"}, no key or value {@code null}; an empty map runs nothing
     * @return this collection
     * @throws NullPointerException if the map, a name or a constraint is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     or groupBy
     */
    public ModelCollection<T> loadCount(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        return aggregate(WithParser.parseAggregates(relations), ANY_COLUMN, COUNT);
    }

    /**
     * Loads a {@code max} of a related column onto every model, named
     * {@code relation_max_column}, as {@link EloquentBuilder#withMax(String, String)}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, qualified with the related table unless it has a dot,
     *     never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     */
    public ModelCollection<T> loadMax(String relation, String column) {
        return single(relation, column, "max");
    }

    /**
     * Loads a {@code max} of a related column per relation onto every model, each constrained
     * by its callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     or groupBy
     */
    public ModelCollection<T> loadMax(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return many(relations, column, "max");
    }

    /**
     * Loads a {@code min} of a related column onto every model, named
     * {@code relation_min_column}, as {@link EloquentBuilder#withMin(String, String)}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, qualified with the related table unless it has a dot,
     *     never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     */
    public ModelCollection<T> loadMin(String relation, String column) {
        return single(relation, column, "min");
    }

    /**
     * Loads a {@code min} of a related column per relation onto every model, each constrained
     * by its callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     or groupBy
     */
    public ModelCollection<T> loadMin(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return many(relations, column, "min");
    }

    /**
     * Loads a {@code sum} of a related column onto every model, named
     * {@code relation_sum_column}, as {@link EloquentBuilder#withSum(String, String)}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, qualified with the related table unless it has a dot,
     *     never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     */
    public ModelCollection<T> loadSum(String relation, String column) {
        return single(relation, column, "sum");
    }

    /**
     * Loads a {@code sum} of a related column per relation onto every model, each constrained
     * by its callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     or groupBy
     */
    public ModelCollection<T> loadSum(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return many(relations, column, "sum");
    }

    /**
     * Loads a {@code avg} of a related column onto every model, named
     * {@code relation_avg_column}, as {@link EloquentBuilder#withAvg(String, String)}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, qualified with the related table unless it has a dot,
     *     never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     */
    public ModelCollection<T> loadAvg(String relation, String column) {
        return single(relation, column, "avg");
    }

    /**
     * Loads a {@code avg} of a related column per relation onto every model, each constrained
     * by its callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument, a name or a constraint is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     or groupBy
     */
    public ModelCollection<T> loadAvg(Map<String, Consumer<EloquentBuilder<?>>> relations, String column) {
        return many(relations, column, "avg");
    }

    /**
     * Loads an {@code exists(...)} value per relation onto every model, named
     * {@code relation_exists} and read as a {@link Boolean}.
     *
     * @param relations the relation names, each optionally {@code "name as alias"}, none
     *     {@code null}; none, or an empty collection, runs nothing
     * @return this collection
     * @throws NullPointerException if a name is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     */
    public ModelCollection<T> loadExists(String... relations) {
        return aggregate(WithParser.parseAggregates(relations), ANY_COLUMN, EXISTS);
    }

    /**
     * Loads an {@code exists(...)} value per relation onto every model, each constrained by its
     * callback, read as a {@link Boolean}.
     *
     * @param relations the constraints by relation name, each name optionally
     *     {@code "name as alias"}, no key or value {@code null}; an empty map runs nothing
     * @return this collection
     * @throws NullPointerException if the map, a name or a constraint is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     or groupBy
     */
    public ModelCollection<T> loadExists(Map<String, Consumer<EloquentBuilder<?>>> relations) {
        return aggregate(WithParser.parseAggregates(relations), ANY_COLUMN, EXISTS);
    }

    /**
     * Loads an aggregate of a related column onto every model, as
     * {@link EloquentBuilder#withAggregate(String, String, String)}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, never {@code null}
     * @param function the function: {@code count}, {@code max}, {@code min}, {@code sum},
     *     {@code avg} or {@code exists}, in any case, or {@code null} for the column of one
     *     related row
     * @return this collection
     * @throws NullPointerException if {@code relation} or {@code column} is {@code null}
     * @throws IllegalArgumentException if {@code function} is not one of those, or the models are
     *     of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     */
    public ModelCollection<T> loadAggregate(String relation, String column, String function) {
        return single(relation, column, function);
    }

    /**
     * Loads an aggregate of a related column per relation onto every model, each constrained by
     * its callback.
     *
     * @param relations the constraints by relation name, no key or value {@code null}
     * @param column the related column, never {@code null}
     * @param function the function as {@link #loadAggregate(String, String, String)} takes, or
     *     {@code null} for the column of one related row
     * @return this collection
     * @throws NullPointerException if the map, the column, a name or a constraint is {@code null}
     * @throws IllegalArgumentException if {@code function} is not a supported function, or the
     *     models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     * @throws IllegalStateException if a constraint uses a clause other than where, limit, offset
     *     or groupBy
     */
    public ModelCollection<T> loadAggregate(Map<String, Consumer<EloquentBuilder<?>>> relations,
            String column, String function) {
        return many(relations, column, function);
    }

    /**
     * Loads the column of one related row onto every model, named {@code relation_column}, as
     * {@link EloquentBuilder#withAggregate(String, String)}.
     *
     * @param relation the relation name, optionally {@code "name as alias"}, never {@code null}
     * @param column the related column, never {@code null}
     * @return this collection
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the models are of more than one class
     * @throws org.aristonis.diwan.database.eloquent.relations.RelationNotFoundException if a name is
     *     not a relation of the model or is a dotted path; checked before any query
     */
    public ModelCollection<T> loadAggregate(String relation, String column) {
        return single(relation, column, null);
    }

    private ModelCollection<T> single(String relation, String column, String function) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(column, "column");
        return aggregate(WithParser.parseAggregates(relation), column, function);
    }

    private ModelCollection<T> many(Map<String, Consumer<EloquentBuilder<?>>> relations, String column,
            String function) {
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(column, "column");
        return aggregate(WithParser.parseAggregates(relations), column, function);
    }

    private ModelCollection<T> aggregate(Map<String, Consumer<EloquentBuilder<?>>> parsed, String column,
            String function) {
        // A class literal does not initialise Relation, and its initialiser registers the bridge.
        Relation.ensureInitialised();
        Access.relations().loadAggregate(models, parsed, column, function);
        return this;
    }
}
