package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The sync and toggle writes of a many-to-many relation: they compare the ids given with the keys
 * already in the join table and attach, detach and update the difference. Laravel's
 * {@code InteractsWithPivotTable::sync}, {@code syncWithoutDetaching}, {@code syncWithPivotValues}
 * and {@code toggle}.
 *
 * <p>Every call reads the current keys, then writes inside one transaction. The read and the writes
 * take no lock, so two racing calls can both see an id as absent; on a unique index over the link
 * columns the second one fails cleanly and its transaction rolls back. The owner touches run after
 * the commit, so a touch that fails leaves the link changes committed.
 *
 * <p>Ids are compared in their {@link EagerKeys#normalise(Object) normal form}, so {@code "1"} and
 * {@code 1} are the same id. Rows that a recorded pivot where clause hides are not seen as current.
 *
 * @param <R> the related model type
 */
abstract class PivotSync<R extends Model> extends PivotWrites<R> {

    /**
     * Builds the relation over the given query and pivot configuration.
     *
     * @param query the related model's query, never {@code null}
     * @param parent the model the relation is read from, never {@code null}
     * @param table the pivot table, never {@code null}
     * @param foreignPivotKey the pivot column that holds the parent's key, never {@code null}
     * @param relatedPivotKey the pivot column that holds the related key, never {@code null}
     * @param parentKey the parent's column the pivot points at, never {@code null}
     * @param relatedKey the related model's column the pivot points at, never {@code null}
     * @param relation the relation's name, or {@code null} when none is known
     */
    PivotSync(EloquentBuilder<R> query, Model parent, String table, String foreignPivotKey,
            String relatedPivotKey, String parentKey, String relatedKey, String relation) {
        super(query, parent, table, foreignPivotKey, relatedPivotKey, parentKey, relatedKey, relation);
    }

    /**
     * Makes the join table hold exactly the ids given: attaches the missing ones, updates the
     * attributes of the present ones and detaches the rest. It reads every current link of the
     * parent, as Laravel does. The read and the writes take no lock; a
     * racing call on a unique index fails cleanly and rolls back, and the owner touches run after
     * the commit.
     *
     * @param ids a value, a model, a collection or array of either, or a map from id to its pivot
     *     attributes
     * @return the attached, detached and updated keys, each cast to the related key type
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or a model has no related key
     */
    public SyncResult sync(Object ids) {
        return sync(ids, true);
    }

    /**
     * Attaches the missing ids and updates the present ones, and detaches the others only when asked.
     * It reads every current link of the parent, as Laravel does.
     * The read and the writes take no lock; a racing call on a unique index fails cleanly and rolls
     * back, and the owner touches run after the commit.
     *
     * @param ids a value, a model, a collection or array of either, or a map from id to its pivot
     *     attributes
     * @param detaching whether current keys absent from {@code ids} are detached
     * @return the attached, detached and updated keys, each cast to the related key type
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or a model has no related key
     */
    public SyncResult sync(Object ids, boolean detaching) {
        return syncSet(PivotRecords.parse(ids, getRelatedKeyName()), detaching);
    }

    /**
     * Attaches the missing ids and updates the present ones, and detaches nothing.
     *
     * @param ids a value, a model, a collection or array of either, or a map from id to its pivot
     *     attributes
     * @return the attached and updated keys, each cast to the related key type; the detached list is
     *     empty
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or a model has no related key
     */
    public SyncResult syncWithoutDetaching(Object ids) {
        return sync(ids, false);
    }

    /**
     * Syncs the ids and writes the same pivot attributes for every one of them; a shared value wins
     * over a per-id value of the same column.
     *
     * @param ids a value, a model, a collection or array of either, or a map from id to its pivot
     *     attributes
     * @param values the pivot attributes written for every id
     * @return the attached, detached and updated keys, each cast to the related key type
     * @throws NullPointerException if {@code ids} or {@code values} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or a model has no related key
     */
    public SyncResult syncWithPivotValues(Object ids, Map<String, ?> values) {
        return syncWithPivotValues(ids, values, true);
    }

    /**
     * Syncs the ids with the same pivot attributes for every one of them, detaching only when asked.
     *
     * @param ids a value, a model, a collection or array of either, or a map from id to its pivot
     *     attributes
     * @param values the pivot attributes written for every id
     * @param detaching whether current keys absent from {@code ids} are detached
     * @return the attached, detached and updated keys, each cast to the related key type
     * @throws NullPointerException if {@code ids} or {@code values} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or a model has no related key
     */
    public SyncResult syncWithPivotValues(Object ids, Map<String, ?> values, boolean detaching) {
        PivotRecords.IdSet set = PivotRecords.parse(ids, getRelatedKeyName());
        return syncSet(PivotRecords.withSharedAttributes(set, values), detaching);
    }

    /**
     * Detaches the ids that are linked and attaches the ones that are not, with their attributes.
     * The read and the writes take no lock; a racing call on a unique index fails cleanly and rolls
     * back, and the owner touches run after the commit. The attached keys are cast to the related key
     * type, where Laravel returns them as given.
     *
     * @param ids a value, a model, a collection or array of either, or a map from id to its pivot
     *     attributes
     * @return the attached and detached keys, each cast to the related key type; the updated list is
     *     always empty
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or a model has no related key
     */
    public SyncResult toggle(Object ids) {
        PivotRecords.IdSet set = PivotRecords.parse(ids, getRelatedKeyName());
        SyncResult result = inTransaction(() -> {
            Map<Object, Object> current = currentKeys();
            List<Object> toDetach = new ArrayList<>();
            List<Object> toAttach = new ArrayList<>();
            for (Object id : set.ids()) {
                (current.containsKey(EagerKeys.normalise(id)) ? toDetach : toAttach).add(id);
            }
            if (!toDetach.isEmpty()) {
                detachInternal(toDetach);
            }
            attachAll(set, toAttach);
            return buildResult(toAttach, toDetach, Collections.<Object>emptyList());
        });
        touchAfter(result);
        return result;
    }

    private SyncResult syncSet(PivotRecords.IdSet set, boolean detaching) {
        if (set.isEmpty() && !detaching) {
            return new SyncResult(Collections.<Object>emptyList(), Collections.<Object>emptyList(),
                    Collections.<Object>emptyList());
        }
        SyncResult result = inTransaction(() -> {
            Map<Object, Object> current = currentKeys();
            List<Object> toDetach = new ArrayList<>();
            if (detaching) {
                for (Map.Entry<Object, Object> entry : current.entrySet()) {
                    if (!set.contains(entry.getKey())) {
                        toDetach.add(entry.getValue());
                    }
                }
                if (!toDetach.isEmpty()) {
                    detachInternal(toDetach);
                }
            }
            List<Object> toAttach = new ArrayList<>();
            List<Object> updated = new ArrayList<>();
            for (Object id : set.ids()) {
                if (!current.containsKey(EagerKeys.normalise(id))) {
                    toAttach.add(id);
                    continue;
                }
                Map<String, Object> attributes = set.attributesOf(id);
                if (!attributes.isEmpty() && updateInternal(id, attributes) > 0) {
                    updated.add(id);
                }
            }
            attachAll(set, toAttach);
            return buildResult(toAttach, toDetach, updated);
        });
        touchAfter(result);
        return result;
    }

    /** Reads the linked related keys, by normal form, each with the value as the database gave it. */
    private Map<Object, Object> currentKeys() {
        Map<Object, Object> current = new LinkedHashMap<>();
        for (Object key : newPivotQuery().pluck(getRelatedPivotKeyName())) {
            current.put(EagerKeys.normalise(key), key);
        }
        return current;
    }

    /** Attaches the ids with their own attributes in one call; no query when there are none. */
    private void attachAll(PivotRecords.IdSet set, List<Object> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = recordsFor(set, ids);
        attachInternal(rows);
    }

    private SyncResult buildResult(List<Object> attached, List<Object> detached, List<Object> updated) {
        return new SyncResult(cast(attached), cast(detached), cast(updated));
    }

    private List<Object> cast(List<Object> keys) {
        List<Object> cast = new ArrayList<>(keys.size());
        for (Object key : keys) {
            cast.add(castKey(key));
        }
        return cast;
    }

    private void touchAfter(SyncResult result) {
        touchOwners(true, !result.isEmpty());
    }
}
