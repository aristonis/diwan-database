package org.aristonis.diwan.database.eloquent.internal;

import org.aristonis.diwan.database.Internal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * Tracks the models one recursive walk over loaded relations is inside of, so the walk ends on a
 * cycle: serialization, owner touching and {@code push()} each use one.
 *
 * <p>Two identity sets are kept. The path holds the models the walk has entered and not yet left;
 * a model met again while it is on the path closes a cycle. The done set holds every model a
 * touch or push walk has already handled in this call, so a model reached by two routes (a
 * diamond, or many children sharing one loaded owner) is saved or touched once. Serialization
 * uses the path only, because a shared model is written in full under each parent.
 *
 * <p>Membership is by identity, never {@code equals}: two instances of the same row are two
 * places in the graph. Callers enter on the way in and leave in a {@code finally} block, so an
 * exception never leaves a model on the path.
 *
 * <p>Internal: not API. Not thread-safe. One instance belongs to one outermost call and is
 * passed down by parameter, never kept in a field, a static or a {@code ThreadLocal}, so two
 * threads or two independent calls never share one.
 *
 * <p>A path made for a touch walk inside a {@code saveOrFail()} transaction also carries that
 * transaction's factory and the connection it was opened on, so the walk can run each owner on
 * the transaction instead of the connection the transaction holds.
 */
@Internal
public final class VisitPath {

    private final Set<Model> path =
            Collections.newSetFromMap(new IdentityHashMap<Model, Boolean>());
    private final Set<Model> done =
            Collections.newSetFromMap(new IdentityHashMap<Model, Boolean>());

    private final QueryFactory transaction;
    private final Connection outer;

    /**
     * Creates an empty path with an empty done set, outside any transaction.
     */
    public VisitPath() {
        this.transaction = null;
        this.outer = null;
    }

    /**
     * Creates an empty path with an empty done set for a walk inside a transaction.
     *
     * @param transaction the factory on the transaction's callback connection, never {@code null}
     * @param outer the connection the transaction was opened on, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public VisitPath(QueryFactory transaction, Connection outer) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.outer = Objects.requireNonNull(outer, "outer");
    }

    /**
     * Returns the factory of the transaction this walk runs inside.
     *
     * @return the transaction's factory, or {@code null} when the walk is outside a transaction
     */
    public QueryFactory transaction() {
        return transaction;
    }

    /**
     * Returns the connection the transaction of this walk was opened on.
     *
     * @return the connection, or {@code null} when the walk is outside a transaction
     */
    public Connection outer() {
        return outer;
    }

    /**
     * Puts a model on the path.
     *
     * @param model the model, never {@code null}
     * @return whether the model was added; {@code false} when it was already on the path
     * @throws NullPointerException if {@code model} is {@code null}
     */
    public boolean enter(Model model) {
        return path.add(Objects.requireNonNull(model, "model"));
    }

    /**
     * Takes a model off the path; a model not on it is ignored. The done set is unchanged.
     *
     * @param model the model, never {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     */
    public void leave(Model model) {
        path.remove(Objects.requireNonNull(model, "model"));
    }

    /**
     * Returns whether a model is on the path now.
     *
     * @param model the model, never {@code null}
     * @return whether this instance has been entered and not yet left
     * @throws NullPointerException if {@code model} is {@code null}
     */
    public boolean contains(Model model) {
        return path.contains(Objects.requireNonNull(model, "model"));
    }

    /**
     * Records that a model has been handled in this call. It stays recorded after it leaves the
     * path.
     *
     * @param model the model, never {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     */
    public void markDone(Model model) {
        done.add(Objects.requireNonNull(model, "model"));
    }

    /**
     * Returns whether a model has been handled in this call.
     *
     * @param model the model, never {@code null}
     * @return whether {@link #markDone(Model)} was called with this instance
     * @throws NullPointerException if {@code model} is {@code null}
     */
    public boolean isDone(Model model) {
        return done.contains(Objects.requireNonNull(model, "model"));
    }

    /**
     * Returns the models held by a loaded relation value, in order.
     *
     * <p>A relation value is a model, a collection of models (a {@code ModelCollection}) or
     * {@code null} for an empty to-one relation. Null items of a collection are skipped.
     *
     * @param value the relation value, may be {@code null}
     * @return an unmodifiable snapshot of the models, empty for {@code null}, never {@code null}
     * @throws IllegalArgumentException if {@code value} is neither a model, a collection nor
     *         {@code null}, or a collection holds something other than a model
     */
    public static List<Model> modelsOf(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Model) {
            return Collections.singletonList((Model) value);
        }
        if (!(value instanceof Collection)) {
            throw new IllegalArgumentException("A relation value must be a model, a collection of "
                    + "models or null, not [" + value.getClass().getName() + "]");
        }
        List<Model> models = new ArrayList<>();
        for (Object item : (Collection<?>) value) {
            if (item == null) {
                continue;
            }
            if (!(item instanceof Model)) {
                throw new IllegalArgumentException("A relation collection must hold models only, "
                        + "not [" + item.getClass().getName() + "]");
            }
            models.add((Model) item);
        }
        return Collections.unmodifiableList(models);
    }
}
