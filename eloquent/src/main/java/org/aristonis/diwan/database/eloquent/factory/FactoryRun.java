package org.aristonis.diwan.database.eloquent.factory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The context of one terminal call: one frame per level of nested creation, all sharing one recycle
 * pool, one position per model class and one random generator.
 *
 * <p>A run is built for each terminal call and dropped at its end, so nothing is stored in a
 * factory. A frame is used by one thread.
 */
final class FactoryRun {

    /**
     * The deepest level of nested creation, where the top factory is level zero.
     */
    static final int MAX_DEPTH = 32;

    private final Shared shared;
    private final int depth;
    private final Model parent;
    private final Map<ForLink, Model> owners = new IdentityHashMap<>();

    private FactoryRun(Shared shared, int depth, Model parent) {
        this.shared = shared;
        this.depth = depth;
        this.parent = parent;
    }

    /**
     * Starts the top frame of a terminal call at depth zero, with no parent.
     *
     * @param top the relation settings of the factory the call was made on, never {@code null}
     * @param databaseFree whether the call must not touch the database
     * @return the frame, never {@code null}
     * @throws NullPointerException if {@code top} is {@code null}
     */
    static FactoryRun start(FactoryRelations top, boolean databaseFree) {
        return new FactoryRun(new Shared(Objects.requireNonNull(top, "relations"), databaseFree), 0, null);
    }

    /**
     * Returns a frame one level deeper, with its own owner cache.
     *
     * @param newParent the model the nested models are created under, or {@code null} for none
     * @param modelClassName the class name of the models about to be created, for the error message
     * @return the frame, never {@code null}
     * @throws IllegalStateException if the new depth is above {@link #MAX_DEPTH}
     */
    FactoryRun nested(Model newParent, String modelClassName) {
        if (depth + 1 > MAX_DEPTH) {
            throw new IllegalStateException("Creating [" + modelClassName + "] is nested more than " + MAX_DEPTH
                    + " levels deep; the factories create each other");
        }
        return new FactoryRun(shared, depth + 1, newParent);
    }

    /**
     * Returns the model the models of this frame are created under.
     *
     * @return the model, or {@code null} at the top and for a nested attribute value
     */
    Model parent() {
        return parent;
    }

    /**
     * Returns the level of this frame.
     *
     * @return the depth, zero for the top frame
     */
    int depth() {
        return depth;
    }

    /**
     * Returns whether the call must not touch the database.
     *
     * @return {@code true} for {@code raw} and {@code rawMany}
     */
    boolean isDatabaseFree() {
        return shared.databaseFree;
    }

    /**
     * Picks a pool model of the exact class and moves the position or draws from the generator.
     *
     * @param modelClass the exact class wanted, never {@code null}
     * @return the model, or {@code null} when the pool has none of that class
     * @throws IllegalStateException if the picked model is not saved
     */
    Model recycled(Class<?> modelClass) {
        List<Model> list = shared.pool.get(modelClass);
        if (list == null || list.isEmpty()) {
            return null;
        }
        Model picked = shared.pick(modelClass, list);
        if (!picked.exists()) {
            throw new IllegalStateException("A recycled model [" + modelClass.getName() + "] is not saved");
        }
        return picked;
    }

    /**
     * Returns the owner of a for link, computing it once in this frame.
     *
     * @param link the link, matched by identity, never {@code null}
     * @param resolver computes the owner the first time, never {@code null}
     * @return the owner, never {@code null}
     */
    Model owner(ForLink link, Supplier<Model> resolver) {
        Model cached = owners.get(link);
        if (cached != null) {
            return cached;
        }
        Model resolved = resolver.get();
        owners.put(link, resolved);
        return resolved;
    }

    /**
     * What the frames of one terminal call share.
     */
    private static final class Shared {

        private final Map<Class<?>, List<Model>> pool = new LinkedHashMap<>();
        private final RecycleMode mode;
        private final Map<Class<?>, Integer> cursor = new HashMap<>();
        private final Random random;
        private final boolean databaseFree;

        private Shared(FactoryRelations top, boolean databaseFree) {
            for (Model model : top.pool()) {
                pool.computeIfAbsent(model.getClass(), key -> new ArrayList<>()).add(model);
            }
            this.mode = top.mode();
            Long seed = top.seed();
            if (mode == RecycleMode.RANDOM) {
                this.random = seed == null ? new Random() : new Random(seed);
            } else {
                this.random = null;
            }
            this.databaseFree = databaseFree;
        }

        private Model pick(Class<?> modelClass, List<Model> list) {
            switch (mode) {
                case FIRST:
                    return list.get(0);
                case RANDOM:
                    return list.get(random.nextInt(list.size()));
                default:
                    int next = cursor.getOrDefault(modelClass, 0);
                    cursor.put(modelClass, next + 1);
                    return list.get(next % list.size());
            }
        }
    }
}
