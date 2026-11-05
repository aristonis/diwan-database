package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable snapshot of the listeners of one model class, in registration order. Adding one
 * makes a new snapshot, so a fire in progress never sees a change.
 */
final class EventListeners {

    static final EventListeners EMPTY = new EventListeners(Collections.<EventEntry>emptyList());

    private final List<EventEntry> entries;

    private EventListeners(List<EventEntry> entries) {
        this.entries = entries;
    }

    /**
     * Returns whether no listener is registered.
     *
     * @return {@code true} when the snapshot has no entry
     */
    boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Returns a snapshot with an entry appended.
     *
     * @param entry the entry, never {@code null}
     * @return the new snapshot; this one is unchanged
     */
    EventListeners with(EventEntry entry) {
        Objects.requireNonNull(entry, "entry");
        List<EventEntry> next = new ArrayList<>(entries.size() + 1);
        next.addAll(entries);
        next.add(entry);
        return new EventListeners(Collections.unmodifiableList(next));
    }

    /**
     * Runs the entries in registration order. A halting event stops at the first {@code false};
     * any other event runs every entry.
     *
     * @param e the event
     * @param m the model
     * @return {@code false} when a halting event was vetoed, otherwise {@code true}
     */
    boolean fire(ModelEvent e, Model m) {
        boolean halting = e.halting();
        for (EventEntry entry : entries) {
            if (!entry.handle(e, m) && halting) {
                return false;
            }
        }
        return true;
    }
}
