package org.aristonis.diwan.database.eloquent;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * The event layer of {@link Model}: the protected hooks a model class overrides, and the dispatch
 * that runs them and then the listeners registered through {@link ModelQuery#events()}.
 *
 * <p>For each event the model's own hook runs first. A halting event stops there when the hook
 * returns {@code false}; otherwise the listeners of the model's {@link ModelQuery} entry run in
 * registration order. The entry is the one the model was made from, else the one registered for
 * its class; a class with neither fires its own hooks only.
 *
 * <p>{@link #withoutEvents(Supplier)} and the {@code xQuietly()} writes silence the listeners on
 * the calling thread only; the model's own hooks still run. The mute does not cross threads: work
 * handed to an executor, a {@code CompletableFuture} or an Android handler fires its events.
 *
 * <p>A listener that saves the model it was called for runs the whole save again, as in Laravel;
 * it recurses unless it guards itself.
 *
 * <p>Instances are not safe to share across threads.
 */
abstract class ModelEventHooks extends ModelGuards {

    ModelEventHooks() {
    }

    // ---- dispatch ----

    /**
     * Fires a halting event: the model's own hook, then the registered listeners.
     *
     * @param event the event, never {@code null}
     * @return {@code false} when the hook or a listener vetoed the operation
     */
    final boolean fireHalting(ModelEvent event) {
        if (!runOwnHalting(event)) {
            return false;
        }
        return dispatch(event);
    }

    /**
     * Fires an event that cannot halt: the model's own hook, then the registered listeners.
     *
     * @param event the event, never {@code null}
     */
    final void fire(ModelEvent event) {
        runOwn(event);
        dispatch(event);
    }

    private boolean dispatch(ModelEvent event) {
        EventListeners listeners = listeners();
        if (listeners.isEmpty() || EventMute.isMuted()) {
            return true;
        }
        return listeners.fire(event, (Model) this);
    }

    // The entry the model was made from, else the one registered for its class; none has no listener.
    private EventListeners listeners() {
        ModelQuery<?> entry = eventSource();
        if (entry == null) {
            entry = ModelQuery.registered(getClass());
        }
        return entry == null ? EventListeners.EMPTY : entry.eventSlot().get();
    }

    /**
     * Refuses a write that has after-commit listeners and no commit to wait for.
     *
     * <p>Called before the first write of save, delete, restore and force delete. It does nothing
     * while events are muted on the thread, when the class has no after-commit listener, or when
     * the model has no connection to ask: that write fails on its own, so there is nothing to
     * refuse first.
     *
     * @throws IllegalStateException if the class has after-commit listeners and the model's
     *     connection runs inside a transaction opened outside this library
     */
    final void requireDeferrable() {
        if (EventMute.isMuted() || !listeners().hasDeferred() || !hasConnection()) {
            return;
        }
        if (!getQueryFactory().connection().canDeferUntilCommit()) {
            throw new IllegalStateException("Model [" + getClass().getName() + "] has after-commit listeners,"
                    + " but its connection runs inside a transaction opened outside this library, so the"
                    + " commit is not known; nothing was written. Bind the model to a connection that owns"
                    + " its transaction, or remove the after-commit listeners");
        }
    }

    private boolean runOwnHalting(ModelEvent event) {
        switch (event) {
            case SAVING:
                return saving();
            case CREATING:
                return creating();
            case UPDATING:
                return updating();
            case DELETING:
                return deleting();
            case RESTORING:
                return restoring();
            case FORCE_DELETING:
                return forceDeleting();
            default:
                runOwn(event);
                return true;
        }
    }

    private void runOwn(ModelEvent event) {
        switch (event) {
            case RETRIEVED:
                retrieved();
                break;
            case SAVED:
                saved();
                break;
            case CREATED:
                created();
                break;
            case UPDATED:
                updated();
                break;
            case DELETED:
                deleted();
                break;
            case SOFT_DELETED:
                softDeleted();
                break;
            case RESTORED:
                restored();
                break;
            case FORCE_DELETED:
                forceDeleted();
                break;
            case REPLICATING:
                replicating();
                break;
            default:
                break;
        }
    }

    // ---- silencing ----

    /**
     * Runs work with the registered listeners silenced for every model on the calling thread. The
     * models' own hooks still run. Calls nest, and the mute ends even when the work throws.
     *
     * @param <R> the type of the result
     * @param work the work to run, never {@code null}
     * @return the work's result, may be {@code null}
     * @throws NullPointerException if {@code work} is {@code null}
     */
    public static <R> R withoutEvents(Supplier<R> work) {
        Objects.requireNonNull(work, "work");
        EventMute.enter();
        try {
            return work.get();
        } finally {
            EventMute.exit();
        }
    }

    /**
     * Runs work with the registered listeners silenced, as {@code withoutEvents(Supplier)}.
     *
     * @param work the work to run, never {@code null}
     * @throws NullPointerException if {@code work} is {@code null}
     */
    public static void withoutEvents(Runnable work) {
        Objects.requireNonNull(work, "work");
        EventMute.enter();
        try {
            work.run();
        } finally {
            EventMute.exit();
        }
    }

    /**
     * Saves the model with the registered listeners silenced.
     *
     * @return as {@code save()}
     */
    public boolean saveQuietly() {
        Supplier<Boolean> work = this::save;
        return withoutEvents(work);
    }

    /**
     * Fills and saves the model, when it exists, with the registered listeners silenced.
     *
     * @param values the attributes, as for {@code update(Map)}
     * @return as {@code update(Map)}
     */
    public boolean updateQuietly(Map<String, ?> values) {
        Supplier<Boolean> work = () -> update(values);
        return withoutEvents(work);
    }

    /**
     * Deletes the model with the registered listeners silenced.
     *
     * @return as {@code delete()}
     */
    public boolean deleteQuietly() {
        Supplier<Boolean> work = this::delete;
        return withoutEvents(work);
    }

    /**
     * Updates the timestamps and saves with the registered listeners silenced.
     *
     * @return as {@code touch()}
     */
    public boolean touchQuietly() {
        Supplier<Boolean> work = this::touch;
        return withoutEvents(work);
    }

    /**
     * Sets a column to now and saves with the registered listeners silenced.
     *
     * @param column the column, never {@code null}
     * @return as {@code touch(String)}
     */
    public boolean touchQuietly(String column) {
        Supplier<Boolean> work = () -> touch(column);
        return withoutEvents(work);
    }

    /**
     * Copies the model with the registered listeners silenced.
     *
     * @param <M> the model's own class, inferred from the assignment
     * @param except further attributes to leave out
     * @return as {@code replicate(String...)}
     * @throws ClassCastException at the call site if {@code M} is not this model's class
     */
    public <M extends Model> M replicateQuietly(String... except) {
        Supplier<M> work = () -> this.<M>replicate(except);
        return withoutEvents(work);
    }

    // ---- hooks ----

    /**
     * Runs before every save; returning {@code false} cancels it.
     *
     * @return whether to continue; {@code true} by default
     */
    protected boolean saving() {
        return true;
    }

    /** Runs after a save that wrote or found nothing to write, before the originals sync. */
    protected void saved() {
    }

    /**
     * Runs before an insert, after unique ids are set; returning {@code false} cancels it.
     *
     * @return whether to continue; {@code true} by default
     */
    protected boolean creating() {
        return true;
    }

    /** Runs after an insert. */
    protected void created() {
    }

    /**
     * Runs before an update of a dirty model; returning {@code false} cancels it.
     *
     * @return whether to continue; {@code true} by default
     */
    protected boolean updating() {
        return true;
    }

    /** Runs after an update that wrote at least one column. */
    protected void updated() {
    }

    /**
     * Runs before a delete; returning {@code false} cancels it.
     *
     * @return whether to continue; {@code true} by default
     */
    protected boolean deleting() {
        return true;
    }

    /** Runs after a delete. */
    protected void deleted() {
    }

    /**
     * Runs after the model is read from the database. An exception stops the read and
     * propagates.
     */
    protected void retrieved() {
    }

    /**
     * Runs at the start of {@code restore()}; returning {@code false} cancels it.
     *
     * @return whether to continue; {@code true} by default
     */
    protected boolean restoring() {
        return true;
    }

    /** Runs after {@code restore()} saved the model. */
    protected void restored() {
    }

    /** Runs after a soft delete wrote the deleted-at column and made it original. */
    protected void softDeleted() {
    }

    /**
     * Runs at the start of {@code forceDelete()}, before the force-deleting flag is set;
     * returning {@code false} cancels it.
     *
     * @return whether to continue; {@code true} by default
     */
    protected boolean forceDeleting() {
        return true;
    }

    /** Runs after {@code forceDelete()} removed the row and the flag was cleared. */
    protected void forceDeleted() {
    }

    /**
     * Runs on the copy made by {@code replicate()}, after its attributes are set and before the
     * copy is returned.
     */
    protected void replicating() {
    }

    // ---- implemented by Model ----

    /**
     * Returns the {@link ModelQuery} entry this model was made from.
     *
     * @return the entry, or {@code null} when the model was built by hand
     */
    abstract ModelQuery<?> eventSource();

    abstract boolean hasConnection();

    /**
     * Returns the query factory this model uses, as {@code Model.getQueryFactory()}.
     *
     * @return the factory, never {@code null}
     * @throws IllegalStateException if the model has no connection
     */
    public abstract QueryFactory getQueryFactory();

    /**
     * Saves the model, as {@code Model.save()}.
     *
     * @return {@code true} when saved or already clean
     */
    public abstract boolean save();

    /**
     * Fills the model and saves it, as {@code Model.update(Map)}.
     *
     * @param values the attributes
     * @return {@code false} when the model does not exist or a hook vetoed the save
     */
    public abstract boolean update(Map<String, ?> values);

    /**
     * Deletes the model's row, as {@code Model.delete()}.
     *
     * @return {@code true} when deleted
     */
    public abstract boolean delete();

    /**
     * Updates the timestamps and saves, as {@code Model.touch()}.
     *
     * @return {@code false} when the model keeps no timestamps or a hook vetoed the save
     */
    public abstract boolean touch();

    /**
     * Sets a column to now and saves, as {@code Model.touch(String)}.
     *
     * @param column the column, never {@code null}
     * @return {@code false} when a hook vetoed the save
     */
    public abstract boolean touch(String column);

    /**
     * Returns a new, unsaved copy, as {@code Model.replicate(String...)}.
     *
     * @param <M> the model's own class, inferred from the assignment
     * @param except further attributes to leave out
     * @return the copy
     */
    public abstract <M extends Model> M replicate(String... except);
}
