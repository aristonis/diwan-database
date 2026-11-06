package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.InvalidRelationException;
import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The relations a model class can load by name, in the order they were added; what a model
 * returns from {@code relations()}.
 *
 * <pre>{@code
 * protected RelationMap<Post> relations() {
 *     return RelationMap.of(Post.class).add("comments", Post::comments).add("author", Post::author);
 * }
 * }</pre>
 *
 * <p>Laravel finds a relation by calling the method of that name. Java has no such call without
 * reflection, so the names that {@code with}, {@code load}, {@code has} and lazy loading use are
 * listed here instead. A map is checked once per model class, the first time a relation of the
 * class is resolved by name; a bad entry then fails with {@link InvalidRelationException}.
 *
 * <p>Immutable: {@link #add(String, RelationFactory)} returns a new map and leaves this one as it
 * is. Safe to share between threads, provided the factories are.
 *
 * @param <M> the model class the relations start from
 */
public final class RelationMap<M extends Model> {

    private static final RelationMap<Model> EMPTY =
            new RelationMap<>(Model.class, Collections.<String, RelationFactory<? super Model>>emptyMap());

    private final Class<M> modelClass;
    private final Map<String, RelationFactory<? super M>> factories;
    private final List<String> names;

    private RelationMap(Class<M> modelClass, Map<String, RelationFactory<? super M>> factories) {
        this.modelClass = modelClass;
        this.factories = factories;
        this.names = Collections.unmodifiableList(new ArrayList<>(factories.keySet()));
    }

    /**
     * Starts an empty map for a model class.
     *
     * @param <M> the model class
     * @param modelClass the model class, never {@code null}; a subclass of it inherits the map
     * @return an empty map, never {@code null}
     * @throws NullPointerException if {@code modelClass} is {@code null}
     */
    public static <M extends Model> RelationMap<M> of(Class<M> modelClass) {
        return new RelationMap<>(Objects.requireNonNull(modelClass, "modelClass"),
                Collections.<String, RelationFactory<? super M>>emptyMap());
    }

    /**
     * Returns the map of a model with no relations, the default of {@code relations()}.
     *
     * @return the shared empty map for {@link Model}, never {@code null}
     */
    public static RelationMap<Model> empty() {
        return EMPTY;
    }

    /**
     * Returns a new map with one more relation, after the ones already added.
     *
     * <p>The name is checked with the rest of the map the first time the model class resolves a
     * relation: it must not be empty and must not hold {@code .} or {@code :}, which eager load
     * paths use.
     *
     * @param name the relation name, as {@code with} and {@code has} take it, never {@code null}
     * @param factory builds the relation for a model, such as {@code Post::comments}, never
     *     {@code null}
     * @return a new map, never {@code null}; this map is unchanged
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the map already has a relation of that name
     */
    public RelationMap<M> add(String name, RelationFactory<? super M> factory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        if (factories.containsKey(name)) {
            throw new IllegalArgumentException("Relation [" + name + "] is already in the map of ["
                    + modelClass.getName() + "]");
        }
        Map<String, RelationFactory<? super M>> next = new LinkedHashMap<>(factories);
        next.put(name, factory);
        return new RelationMap<>(modelClass, Collections.unmodifiableMap(next));
    }

    /**
     * Returns the model class the map was built for.
     *
     * @return the model class, never {@code null}
     */
    public Class<M> modelClass() {
        return modelClass;
    }

    /**
     * Returns the relation names in the order they were added.
     *
     * @return the names, unmodifiable, never {@code null}
     */
    public List<String> names() {
        return names;
    }

    /**
     * Builds a relation by name for a model and gives it that name.
     *
     * <p>This runs the factory and nothing else. A model's own {@code relation(name)} also checks
     * the map once per class, and is what applications call.
     *
     * @param name the relation name, never {@code null}
     * @param model the parent model, never {@code null}, an instance of {@link #modelClass()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the model is not an instance of the map's model class
     * @throws RelationNotFoundException if the map has no relation of that name
     * @throws InvalidRelationException if the factory returns {@code null}
     */
    public Relation<?, ?> resolve(String name, Model model) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(model, "model");
        if (!modelClass.isInstance(model)) {
            throw new IllegalArgumentException("The relation map of [" + modelClass.getName()
                    + "] cannot resolve a relation of [" + model.getClass().getName() + "]");
        }
        RelationFactory<? super M> factory = factories.get(name);
        if (factory == null) {
            throw new RelationNotFoundException(model.getClass(), name, names);
        }
        Relation<?, ?> relation = factory.create(modelClass.cast(model));
        if (relation == null) {
            throw new InvalidRelationException(model.getClass(), name, "the factory returned null");
        }
        relation.useRelationName(name);
        return relation;
    }

    /**
     * Returns the factory of a relation, for the registry check.
     *
     * @param name the relation name
     * @return the factory, or {@code null} if the map has no relation of that name
     */
    RelationFactory<? super M> factory(String name) {
        return factories.get(name);
    }
}
