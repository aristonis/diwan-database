package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.eloquent.ModelObserver;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.aristonis.diwan.database.Connection;

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
     * Tells whether this entry waits for the commit of the model's connection before it runs.
     *
     * @return {@code true} for an entry made by {@link #afterCommit(EventEntry)} or an observer entry
     *     that defers its non-halting methods, otherwise {@code false}
     */
    default boolean isDeferred() {
        return false;
    }

    /**
     * Wraps an entry so that it runs after the commit of the connection that wrote the model.
     *
     * <p>The wrapper asks {@code model.getQueryFactory().connection()} for its connection, which
     * inside {@code saveOrFail} is the transaction's, and hands the call to its
     * {@code afterCommit}; outside a transaction that runs the call at once. A model with no factory,
     * no resolver and no default resolver ({@code hasConnection()} is {@code false}) runs the call
     * at once. A resolver that fails for the model's connection name throws instead. The wrapped entry sees the model as it is
     * when the call runs, and its result is ignored, so the wrapper never halts.
     *
     * @param inner the entry to defer, never {@code null}
     * @return the deferred entry; {@link #isDeferred()} is {@code true}
     * @throws NullPointerException if {@code inner} is {@code null}
     */
    static EventEntry afterCommit(EventEntry inner) {
        Objects.requireNonNull(inner, "inner");
        return new Deferred(inner);
    }

    /**
     * Tells whether an event can wait for the commit: the seven events that run after a write.
     *
     * @param event the event, never {@code null}
     * @return {@code true} for saved, created, updated, deleted, soft deleted, restored and force
     *     deleted
     */
    static boolean canDefer(ModelEvent event) {
        switch (event) {
            case SAVED:
            case CREATED:
            case UPDATED:
            case DELETED:
            case SOFT_DELETED:
            case RESTORED:
            case FORCE_DELETED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Makes an observer entry that defers the events that can wait for the commit and runs every
     * other event directly, halting ones in the transaction.
     *
     * @param <T> the model class
     * @param o the observer
     * @return the entry; {@link #isDeferred()} is {@code true}
     */
    static <T extends Model> EventEntry deferredObserver(ModelObserver<? super T> o) {
        EventEntry direct = EventEntry.<T>observer(o);
        EventEntry deferred = afterCommit(direct);
        return new Routed(direct, deferred);
    }

    /** The wrapper made by {@link #afterCommit(EventEntry)}. */
    final class Deferred implements EventEntry {
        private final EventEntry inner;

        private Deferred(EventEntry inner) {
            this.inner = inner;
        }

        @Override
        public boolean handle(ModelEvent e, Model m) {
            if (!m.hasConnection()) {
                inner.handle(e, m);
            } else {
                Connection connection = m.getQueryFactory().connection();
                connection.afterCommit(() -> inner.handle(e, m));
            }
            return true;
        }

        @Override
        public boolean isDeferred() {
            return true;
        }
    }

    /** The entry made by {@link #deferredObserver(ModelObserver)}. */
    final class Routed implements EventEntry {
        private final EventEntry direct;
        private final EventEntry deferred;

        private Routed(EventEntry direct, EventEntry deferred) {
            this.direct = direct;
            this.deferred = deferred;
        }

        @Override
        public boolean handle(ModelEvent e, Model m) {
            return EventEntry.canDefer(e) ? deferred.handle(e, m) : direct.handle(e, m);
        }

        @Override
        public boolean isDeferred() {
            return true;
        }
    }

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
