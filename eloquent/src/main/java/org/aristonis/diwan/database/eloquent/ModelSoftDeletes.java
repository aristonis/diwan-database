package org.aristonis.diwan.database.eloquent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.aristonis.diwan.database.query.Builder;

/**
 * The soft-delete layer of {@link Model}: a model that opts in through {@code softDeletes()} keeps
 * its row on {@code delete()} and stamps a deleted-at column instead.
 *
 * <p>Rules as Laravel's {@code SoftDeletes}: {@code delete()} runs one keyed UPDATE that sets the
 * deleted-at column and, when the model writes timestamps, {@code updated_at} to the same moment;
 * the model keeps existing. {@link #forceDelete()} removes the row for good and {@link #restore()}
 * clears the column through {@code save()}.
 *
 * <p>The five soft-delete hooks, declared in the event layer, are the seams for model events:
 * {@code forceDeleting} and {@code restoring} may veto by returning {@code false};
 * {@code softDeleted}, {@code forceDeleted} and {@code restored} run after the work. Each runs
 * once per operation, and {@code softDeleted} also when the keyed update touched no row.
 *
 * <p>Instances are not safe to share across threads.
 */
abstract class ModelSoftDeletes extends ModelEventHooks {

    private boolean forceDeleteRun;

    ModelSoftDeletes() {
    }

    // ---- configuration ----

    /**
     * Returns whether this model soft-deletes, as its {@code softDeletes()} setting says.
     *
     * @return {@code true} when {@code delete()} stamps the deleted-at column instead of removing
     *     the row
     */
    public boolean isSoftDeletable() {
        return softDeletes();
    }

    /**
     * Returns the name of the deleted-at column.
     *
     * @return the column, never {@code null} or blank on a soft-deleting model
     * @throws IllegalStateException if the model soft-deletes and its {@code deletedAtColumn()} is
     *     {@code null} or blank
     */
    public String getDeletedAtColumn() {
        String column = deletedAtColumn();
        if (softDeletes() && (column == null || column.trim().isEmpty())) {
            throw new IllegalStateException("The deleted-at column of soft-deleting model ["
                    + getClass().getName() + "] is null or blank");
        }
        return column;
    }

    /**
     * Returns the deleted-at column qualified with the table name, as {@code qualifyColumn} does.
     *
     * @return the qualified column, never {@code null}
     * @throws IllegalStateException as {@link #getDeletedAtColumn()}
     */
    public String getQualifiedDeletedAtColumn() {
        return qualifyColumn(getDeletedAtColumn());
    }

    // ---- state ----

    /**
     * Returns whether the model is soft-deleted: its deleted-at column holds a value.
     *
     * @return {@code true} when the column is set
     * @throws IllegalStateException as {@link #getDeletedAtColumn()}
     */
    public boolean trashed() {
        return getAttribute(getDeletedAtColumn()) != null;
    }

    /**
     * Returns whether a {@code forceDelete()} is running on this model right now.
     *
     * @return {@code true} from the start of the delete to its end, also inside {@code deleting()}
     */
    public boolean isForceDeleting() {
        return forceDeleteRun;
    }

    // ---- soft delete ----

    /**
     * Soft-deletes the row when this model soft-deletes and no force delete is running. Called by
     * {@code Model.delete()} after the {@code deleting()} hook allowed the delete.
     *
     * <p>One UPDATE keyed by the model's key sets the deleted-at column and, when the model uses
     * timestamps, the updated-at column to the same fresh moment. The two attributes are then made
     * original and the model keeps existing. If the UPDATE fails, the attributes get their
     * previous values back and the exception propagates.
     *
     * @return {@code true} when the row was soft-deleted; {@code false} when the caller must
     *     delete the row for good
     */
    final boolean runSoftDelete() {
        if (!softDeletes() || forceDeleteRun) {
            return false;
        }
        String column = getDeletedAtColumn();
        Object moment = freshTimestampValue();
        Map<String, Object> written = new LinkedHashMap<>();
        written.put(column, moment);
        String updatedAt = getUpdatedAtColumn();
        if (usesTimestamps() && updatedAt != null && !updatedAt.equals(column)) {
            written.put(updatedAt, moment);
        }
        Map<String, Object> before = getAttributes();
        for (Map.Entry<String, Object> entry : written.entrySet()) {
            putRawAttribute(entry.getKey(), entry.getValue());
        }
        try {
            setKeysForSaveQuery(newBaseQuery()).update(written);
        } catch (RuntimeException failure) {
            setRawAttributes(before);
            throw failure;
        }
        syncOriginalAttributes(written.keySet().toArray(new String[0]));
        fire(ModelEvent.SOFT_DELETED);
        return true;
    }

    /**
     * Deletes the row for good, whether or not the model soft-deletes.
     *
     * <p>Runs {@code forceDeleting()} first, then {@code delete()} (so {@code deleting()} and
     * {@code deleted()} still run) with {@link #isForceDeleting()} true, and {@code forceDeleted()}
     * once the delete returned {@code true}. The flag is cleared even when a hook or the statement
     * throws, so the next plain {@code delete()} soft-deletes again.
     *
     * @return {@code true} when deleted; {@code false} when a hook vetoed it or the model does not
     *     exist
     * @throws IllegalStateException if the model does not soft-delete, or has no primary key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the statement
     */
    public boolean forceDelete() {
        requireSoftDeletes("forceDelete");
        if (!fireHalting(ModelEvent.FORCE_DELETING)) {
            return false;
        }
        forceDeleteRun = true;
        boolean deleted;
        try {
            deleted = delete();
        } finally {
            forceDeleteRun = false;
        }
        if (deleted) {
            fire(ModelEvent.FORCE_DELETED);
        }
        return deleted;
    }

    /**
     * Clears the deleted-at column and saves the model.
     *
     * <p>Runs {@code restoring()} first; {@code false} from it stops the restore. The model is
     * marked as existing, so a restore after a {@link #forceDelete()} runs an UPDATE that touches
     * no row and still returns {@code true}. A model whose column is already empty runs no
     * statement. {@code restored()} runs after the save returned {@code true}.
     *
     * @return {@code true} when saved or already clean; {@code false} when a hook vetoed it
     * @throws IllegalStateException if the model does not soft-delete
     * @throws org.aristonis.diwan.database.QueryException if the database refuses the statement
     */
    public boolean restore() {
        requireSoftDeletes("restore");
        if (!fireHalting(ModelEvent.RESTORING)) {
            return false;
        }
        putRawAttribute(getDeletedAtColumn(), null);
        markExisting();
        boolean saved = save();
        if (saved) {
            fire(ModelEvent.RESTORED);
        }
        return saved;
    }

    /**
     * Restores the model with the registered listeners silenced.
     *
     * @return as {@link #restore()}
     * @throws IllegalStateException if the model does not soft-delete
     */
    public boolean restoreQuietly() {
        Supplier<Boolean> work = this::restore;
        return withoutEvents(work);
    }

    /**
     * Deletes the row for good with the registered listeners silenced.
     *
     * @return as {@link #forceDelete()}
     * @throws IllegalStateException if the model does not soft-delete, or has no primary key
     */
    public boolean forceDeleteQuietly() {
        Supplier<Boolean> work = this::forceDelete;
        return withoutEvents(work);
    }

    private void requireSoftDeletes(String operation) {
        if (!softDeletes()) {
            throw new IllegalStateException("Cannot " + operation + " model [" + getClass().getName()
                    + "]: it does not soft-delete");
        }
    }

    // ---- implemented by Model ----

    /**
     * Returns whether this model writes timestamps now, as {@code Model.usesTimestamps()}.
     *
     * @return whether timestamps are written
     */
    public abstract boolean usesTimestamps();

    /**
     * Returns the current moment in the stored form, as {@code Model.freshTimestampValue()}.
     *
     * @return the value, never {@code null}
     */
    public abstract Object freshTimestampValue();

    /**
     * Converts a moment to the stored form, as {@code Model.fromDateTime(Instant)}.
     *
     * @param moment the moment, never {@code null}
     * @return the stored form, never {@code null}
     */
    public abstract Object fromDateTime(Instant moment);

    /**
     * Returns a new base query on the model's table, as {@code Model.newBaseQuery()}.
     *
     * @return the builder, never {@code null}
     */
    public abstract Builder newBaseQuery();

    /**
     * Adds the where clauses that select this model's row, as {@code Model.setKeysForSaveQuery}.
     *
     * @param query the base query, never {@code null}
     * @return the same query with the key clauses added
     */
    protected abstract Builder setKeysForSaveQuery(Builder query);

    /** Marks the model as existing, as {@code Model.markExisting()}. */
    abstract void markExisting();
}
