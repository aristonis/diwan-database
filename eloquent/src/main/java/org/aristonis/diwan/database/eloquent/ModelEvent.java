package org.aristonis.diwan.database.eloquent;

/**
 * The lifecycle events of a model. A halting event runs before the change and a listener can veto
 * it by returning {@code false}; the others run after the change, or on a read, and cannot.
 */
enum ModelEvent {
    RETRIEVED(false),
    SAVING(true),
    SAVED(false),
    CREATING(true),
    CREATED(false),
    UPDATING(true),
    UPDATED(false),
    DELETING(true),
    DELETED(false),
    SOFT_DELETED(false),
    RESTORING(true),
    RESTORED(false),
    FORCE_DELETING(true),
    FORCE_DELETED(false),
    REPLICATING(false);

    private final boolean halting;

    ModelEvent(boolean halting) {
        this.halting = halting;
    }

    /**
     * Returns whether a {@code false} from a listener stops the operation.
     *
     * @return {@code true} for the events that run before a change
     */
    boolean halting() {
        return halting;
    }
}
