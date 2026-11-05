package org.aristonis.diwan.database.eloquent;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One registered listener: a callback that sees every event and reacts to the ones it was
 * registered for. {@code handle} returns {@code false} to halt; the result is ignored for
 * events that are not halting.
 */
interface EventEntry {

    /**
     * Handles an event on a model.
     *
     * @param e the event that fired
     * @param m the model it fired on
     * @return {@code false} to halt a halting event, otherwise {@code true}
     */
    boolean handle(ModelEvent e, Model m);

    /**
     * Makes an entry that tests one event and halts when the predicate returns {@code false}.
     *
     * @param <T> the model class
     * @param event the event to react to
     * @param p the predicate
     * @return the entry
     */
    @SuppressWarnings("unchecked")
    static <T extends Model> EventEntry predicate(ModelEvent event, Predicate<? super T> p) {
        Objects.requireNonNull(p, "listener");
        return (e, m) -> e != event || p.test((T) m);
    }

    /**
     * Makes an entry that runs a callback for one event and never halts.
     *
     * @param <T> the model class
     * @param event the event to react to
     * @param c the callback
     * @return the entry
     */
    @SuppressWarnings("unchecked")
    static <T extends Model> EventEntry consumer(ModelEvent event, Consumer<? super T> c) {
        Objects.requireNonNull(c, "listener");
        return (e, m) -> {
            if (e == event) {
                c.accept((T) m);
            }
            return true;
        };
    }

    /**
     * Makes an entry that routes every event to the matching method of an observer.
     *
     * @param <T> the model class
     * @param o the observer
     * @return the entry
     */
    @SuppressWarnings("unchecked")
    static <T extends Model> EventEntry observer(ModelObserver<? super T> o) {
        Objects.requireNonNull(o, "observer");
        ModelObserver<T> observer = (ModelObserver<T>) o;
        return (e, m) -> {
            T model = (T) m;
            switch (e) {
                case RETRIEVED:
                    observer.retrieved(model);
                    return true;
                case SAVING:
                    return observer.saving(model);
                case SAVED:
                    observer.saved(model);
                    return true;
                case CREATING:
                    return observer.creating(model);
                case CREATED:
                    observer.created(model);
                    return true;
                case UPDATING:
                    return observer.updating(model);
                case UPDATED:
                    observer.updated(model);
                    return true;
                case DELETING:
                    return observer.deleting(model);
                case DELETED:
                    observer.deleted(model);
                    return true;
                case SOFT_DELETED:
                    observer.softDeleted(model);
                    return true;
                case RESTORING:
                    return observer.restoring(model);
                case RESTORED:
                    observer.restored(model);
                    return true;
                case FORCE_DELETING:
                    return observer.forceDeleting(model);
                case FORCE_DELETED:
                    observer.forceDeleted(model);
                    return true;
                case REPLICATING:
                    observer.replicating(model);
                    return true;
                default:
                    throw new IllegalStateException("Unhandled event " + e);
            }
        };
    }
}
