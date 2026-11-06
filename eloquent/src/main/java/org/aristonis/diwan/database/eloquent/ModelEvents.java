package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.ModelObserver;
import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.eloquent.EventEntry;
import org.aristonis.diwan.database.eloquent.EventListeners;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelEvent;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The listener registrar of one model class, reached through {@link ModelQuery#events()}. Each
 * registration appends a listener and returns this registrar, so calls chain.
 *
 * <p>For every event the model's own hook runs first, then the listeners in registration order.
 * Only a {@code false} from a listener halts, and only the six halting events (saving, creating,
 * updating, deleting, restoring, force deleting) can be halted; a halted event stops the listeners
 * after it and cancels the operation. A listener that throws propagates its exception to the
 * caller.
 *
 * <p>Listeners are static per model class and are never removed except by {@code forget()}.
 * Register them once at start-up, not per request, or every registration fires.
 *
 * <p>Listeners belong to the class entry registered with {@link ModelQuery#of}, so they apply to
 * every instance and every derived entry of the class. Registration is thread safe and takes effect
 * for the next event. The mute of {@code withoutEvents} is per thread and does not cross to an
 * executor or another thread. A write a listener makes inside {@code saveOrFail} joins that
 * transaction only through the saving model's {@code getQueryFactory()}.
 *
 * @param <T> the model class
 */
public final class ModelEvents<T extends Model> {

    private final AtomicReference<EventListeners> slot;
    private final boolean isAfterCommit;

    ModelEvents(AtomicReference<EventListeners> slot) {
        this(slot, false);
    }

    private ModelEvents(AtomicReference<EventListeners> slot, boolean isAfterCommit) {
        this.slot = slot;
        this.isAfterCommit = isAfterCommit;
    }

    private ModelEvents<T> add(EventEntry entry) {
        slot.updateAndGet(current -> current.with(entry));
        return this;
    }

    private ModelEvents<T> addNonHalting(EventEntry entry) {
        return add(isAfterCommit ? EventEntry.afterCommit(entry) : entry);
    }

    private void refuseDeferred(ModelEvent event) {
        if (isAfterCommit) {
            throw new UnsupportedOperationException("The after-commit view cannot register the "
                    + event + " event: only saved, created, updated, deleted, softDeleted, restored and"
                    + " forceDeleted can wait for the commit");
        }
    }

    /**
     * Returns a view of this registrar whose registrations run after the commit.
     *
     * <p>The view shares this registrar's listeners: what it registers is visible through
     * {@link ModelQuery#events()} and removed by {@code forget()}. Each listener registered through
     * it for {@code saved}, {@code created}, {@code updated}, {@code deleted}, {@code softDeleted},
     * {@code restored} or {@code forceDeleted} runs after the outermost commit of the connection
     * that wrote the model, in registration order, on the thread that committed. Outside a
     * transaction it runs at once; when the transaction or its savepoint rolls back it never runs.
     * The listener sees the model as it is when it runs, not as it was when the event fired.
     *
     * <p>A halting event cannot wait for the commit, because its result decides the write that is
     * still to run, and {@code retrieved} and {@code replicating} are not write events, so the
     * view refuses them. A save, delete, restore or force delete of a class that has such a listener
     * fails with {@link IllegalStateException}, before it writes anything, when the model's
     * connection runs in a transaction opened outside this library; bind the model to a connection
     * that owns its transaction instead. Events muted by {@code withoutEvents} queue nothing.
     *
     * @apiNote Register after-commit listeners once, at start-up, like every other listener. Each
     *     deferred callback holds its model until the outermost commit, so a large import that saves
     *     many models in one transaction keeps every one of them, once per listener, reachable
     *     until then; commit in batches.
     * @return a view that registers after-commit listeners, never {@code null}; calling it on the
     *     view returns an equivalent view
     */
    public ModelEvents<T> afterCommit() {
        return new ModelEvents<>(slot, true);
    }

    /**
     * Registers a listener that runs before a model is saved, insert or update.
     * Returning {@code false} halts the operation and the listeners after it.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     * @throws UnsupportedOperationException if this is the {@link #afterCommit()} view, which cannot
     *     offer this event
     */
    public ModelEvents<T> saving(Predicate<? super T> listener) {
        refuseDeferred(ModelEvent.SAVING);
        return add(EventEntry.<T>predicate(
                ModelEvent.SAVING, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs before a new model is inserted.
     * Returning {@code false} halts the operation and the listeners after it.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     * @throws UnsupportedOperationException if this is the {@link #afterCommit()} view, which cannot
     *     offer this event
     */
    public ModelEvents<T> creating(Predicate<? super T> listener) {
        refuseDeferred(ModelEvent.CREATING);
        return add(EventEntry.<T>predicate(
                ModelEvent.CREATING, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs before an existing model is updated.
     * Returning {@code false} halts the operation and the listeners after it.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     * @throws UnsupportedOperationException if this is the {@link #afterCommit()} view, which cannot
     *     offer this event
     */
    public ModelEvents<T> updating(Predicate<? super T> listener) {
        refuseDeferred(ModelEvent.UPDATING);
        return add(EventEntry.<T>predicate(
                ModelEvent.UPDATING, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs before a model is deleted.
     * Returning {@code false} halts the operation and the listeners after it.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     * @throws UnsupportedOperationException if this is the {@link #afterCommit()} view, which cannot
     *     offer this event
     */
    public ModelEvents<T> deleting(Predicate<? super T> listener) {
        refuseDeferred(ModelEvent.DELETING);
        return add(EventEntry.<T>predicate(
                ModelEvent.DELETING, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs before a soft-deleted model is restored.
     * Returning {@code false} halts the operation and the listeners after it.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     * @throws UnsupportedOperationException if this is the {@link #afterCommit()} view, which cannot
     *     offer this event
     */
    public ModelEvents<T> restoring(Predicate<? super T> listener) {
        refuseDeferred(ModelEvent.RESTORING);
        return add(EventEntry.<T>predicate(
                ModelEvent.RESTORING, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs before a model is permanently deleted.
     * Returning {@code false} halts the operation and the listeners after it.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     * @throws UnsupportedOperationException if this is the {@link #afterCommit()} view, which cannot
     *     offer this event
     */
    public ModelEvents<T> forceDeleting(Predicate<? super T> listener) {
        refuseDeferred(ModelEvent.FORCE_DELETING);
        return add(EventEntry.<T>predicate(
                ModelEvent.FORCE_DELETING, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs after a model is read from the database.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     * @throws UnsupportedOperationException if this is the {@link #afterCommit()} view, which cannot
     *     offer this event
     */
    public ModelEvents<T> retrieved(Consumer<? super T> listener) {
        refuseDeferred(ModelEvent.RETRIEVED);
        return add(EventEntry.<T>consumer(
                ModelEvent.RETRIEVED, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs after a model is saved.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public ModelEvents<T> saved(Consumer<? super T> listener) {
        return addNonHalting(EventEntry.<T>consumer(
                ModelEvent.SAVED, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs after a new model is inserted.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public ModelEvents<T> created(Consumer<? super T> listener) {
        return addNonHalting(EventEntry.<T>consumer(
                ModelEvent.CREATED, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs after an existing model is updated.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public ModelEvents<T> updated(Consumer<? super T> listener) {
        return addNonHalting(EventEntry.<T>consumer(
                ModelEvent.UPDATED, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs after a model is deleted.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public ModelEvents<T> deleted(Consumer<? super T> listener) {
        return addNonHalting(EventEntry.<T>consumer(
                ModelEvent.DELETED, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs after a model is soft-deleted.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public ModelEvents<T> softDeleted(Consumer<? super T> listener) {
        return addNonHalting(EventEntry.<T>consumer(
                ModelEvent.SOFT_DELETED, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs after a soft-deleted model is restored.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public ModelEvents<T> restored(Consumer<? super T> listener) {
        return addNonHalting(EventEntry.<T>consumer(
                ModelEvent.RESTORED, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs after a model is permanently deleted.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public ModelEvents<T> forceDeleted(Consumer<? super T> listener) {
        return addNonHalting(EventEntry.<T>consumer(
                ModelEvent.FORCE_DELETED, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers a listener that runs on the copy made by {@code replicate}, after its attributes
     * are set.
     *
     * @param listener the listener, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code listener} is {@code null}
     * @throws UnsupportedOperationException if this is the {@link #afterCommit()} view, which cannot
     *     offer this event
     */
    public ModelEvents<T> replicating(Consumer<? super T> listener) {
        refuseDeferred(ModelEvent.REPLICATING);
        return add(EventEntry.<T>consumer(
                ModelEvent.REPLICATING, Objects.requireNonNull(listener, "listener")));
    }

    /**
     * Registers an observer, one listener that receives every event through the matching method of
     * {@link ModelObserver}. It runs in registration order among the other listeners.
     *
     * <p>When {@link ModelObserver#afterCommit()} returns {@code true}, or this is the
     * {@link #afterCommit()} view, the observer's non-halting write events wait for the commit as
     * {@link #afterCommit()} describes and its halting methods run in the transaction as usual.
     *
     * @param observer the observer, never {@code null}
     * @return this registrar
     * @throws NullPointerException if {@code observer} is {@code null}
     */
    public ModelEvents<T> observe(ModelObserver<? super T> observer) {
        Objects.requireNonNull(observer, "observer");
        boolean isDeferred = isAfterCommit || observer.afterCommit();
        return add(isDeferred ? EventEntry.<T>deferredObserver(observer) : EventEntry.<T>observer(observer));
    }

    /**
     * Removes every listener and observer of the class. A fire already in progress finishes with
     * the listeners it started with.
     *
     * @return this registrar
     */
    public ModelEvents<T> forget() {
        slot.set(EventListeners.EMPTY);
        return this;
    }

    /**
     * Returns whether any listener or observer is registered.
     *
     * @return {@code true} when at least one is registered
     */
    public boolean hasListeners() {
        return !slot.get().isEmpty();
    }
}
