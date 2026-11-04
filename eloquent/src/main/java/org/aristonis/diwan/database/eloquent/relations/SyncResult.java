package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a sync or toggle changed in a join table: the related keys attached, detached and updated.
 * Laravel returns the same three lists as an array.
 *
 * <p>Each key is a {@code Long} when the related key is an integer column and a {@code String}
 * otherwise, whatever spelling the caller passed. A toggle never updates, so its updated list is
 * always empty. Immutable.
 */
public final class SyncResult {

    private final List<Object> attached;
    private final List<Object> detached;
    private final List<Object> updated;

    SyncResult(List<Object> attached, List<Object> detached, List<Object> updated) {
        this.attached = Collections.unmodifiableList(new ArrayList<>(attached));
        this.detached = Collections.unmodifiableList(new ArrayList<>(detached));
        this.updated = Collections.unmodifiableList(new ArrayList<>(updated));
    }

    /**
     * Returns the related keys attached.
     *
     * @return the keys, unmodifiable, in attach order
     */
    public List<Object> getAttached() {
        return attached;
    }

    /**
     * Returns the related keys detached.
     *
     * @return the keys, unmodifiable, in detach order
     */
    public List<Object> getDetached() {
        return detached;
    }

    /**
     * Returns the related keys whose join table row was updated.
     *
     * @return the keys, unmodifiable, in update order
     */
    public List<Object> getUpdated() {
        return updated;
    }

    /**
     * Returns whether nothing changed.
     *
     * @return {@code true} if all three lists are empty
     */
    public boolean isEmpty() {
        return attached.isEmpty() && detached.isEmpty() && updated.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SyncResult)) {
            return false;
        }
        SyncResult that = (SyncResult) other;
        return attached.equals(that.attached) && detached.equals(that.detached) && updated.equals(that.updated);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * attached.hashCode() + detached.hashCode()) + updated.hashCode();
    }

    @Override
    public String toString() {
        return "SyncResult{attached=" + attached + ", detached=" + detached + ", updated=" + updated + "}";
    }
}
