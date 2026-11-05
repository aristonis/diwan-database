package org.aristonis.diwan.database.eloquent;

/**
 * A set of model event hooks in one class, registered with {@link ModelEvents#observe}. Every
 * method defaults to doing nothing, or to {@code true} for the halting ones, so an observer
 * overrides only the events it cares about.
 *
 * <p>For each event the model's own hook runs first, then the listeners in registration order.
 * Only a {@code false} returned from a halting method halts the operation and the listeners after
 * it. An exception thrown here propagates to the caller. Observers register on the class entry and
 * are not called while events are muted on the thread.
 *
 * @param <T> the model class
 */
public interface ModelObserver<T extends Model> {

    /**
     * Called before a model is saved, insert or update.
     *
     * @param model the model, never {@code null}
     * @return {@code false} to halt the operation, {@code true} to continue; the default is
     *     {@code true}
     */
    default boolean saving(T model) {
        return true;
    }

    /**
     * Called before a new model is inserted.
     *
     * @param model the model, never {@code null}
     * @return {@code false} to halt the operation, {@code true} to continue; the default is
     *     {@code true}
     */
    default boolean creating(T model) {
        return true;
    }

    /**
     * Called before an existing model is updated.
     *
     * @param model the model, never {@code null}
     * @return {@code false} to halt the operation, {@code true} to continue; the default is
     *     {@code true}
     */
    default boolean updating(T model) {
        return true;
    }

    /**
     * Called before a model is deleted.
     *
     * @param model the model, never {@code null}
     * @return {@code false} to halt the operation, {@code true} to continue; the default is
     *     {@code true}
     */
    default boolean deleting(T model) {
        return true;
    }

    /**
     * Called before a soft-deleted model is restored.
     *
     * @param model the model, never {@code null}
     * @return {@code false} to halt the operation, {@code true} to continue; the default is
     *     {@code true}
     */
    default boolean restoring(T model) {
        return true;
    }

    /**
     * Called before a model is permanently deleted.
     *
     * @param model the model, never {@code null}
     * @return {@code false} to halt the operation, {@code true} to continue; the default is
     *     {@code true}
     */
    default boolean forceDeleting(T model) {
        return true;
    }

    /**
     * Called after a model is read from the database. It cannot halt anything.
     *
     * @param model the model, never {@code null}
     */
    default void retrieved(T model) {
    }

    /**
     * Called after a model is saved. It cannot halt anything.
     *
     * @param model the model, never {@code null}
     */
    default void saved(T model) {
    }

    /**
     * Called after a new model is inserted. It cannot halt anything.
     *
     * @param model the model, never {@code null}
     */
    default void created(T model) {
    }

    /**
     * Called after an existing model is updated. It cannot halt anything.
     *
     * @param model the model, never {@code null}
     */
    default void updated(T model) {
    }

    /**
     * Called after a model is deleted. It cannot halt anything.
     *
     * @param model the model, never {@code null}
     */
    default void deleted(T model) {
    }

    /**
     * Called after a model is soft-deleted. It cannot halt anything.
     *
     * @param model the model, never {@code null}
     */
    default void softDeleted(T model) {
    }

    /**
     * Called after a soft-deleted model is restored. It cannot halt anything.
     *
     * @param model the model, never {@code null}
     */
    default void restored(T model) {
    }

    /**
     * Called after a model is permanently deleted. It cannot halt anything.
     *
     * @param model the model, never {@code null}
     */
    default void forceDeleted(T model) {
    }

    /**
     * Called on the copy made by {@code replicate}, after its attributes are set. It cannot halt
     * anything.
     *
     * @param model the model, never {@code null}
     */
    default void replicating(T model) {
    }
}
