package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * Turns the ids a pivot write is given into keys, and builds the join table rows written for them.
 * Laravel's {@code InteractsWithPivotTable::parseIds} and the attach record builders.
 *
 * <p>An id may be a plain value, a related model (its related key attribute is used, not its
 * primary key), a collection or array of either, or a map from id to the pivot attributes of that
 * id. Keys compare in the {@link EagerKeys#normalise(Object) normal form}, so {@code "1"} and
 * {@code 1} are the same id, while the original value is the one bound into the SQL.
 *
 * <p>Stateless; safe to use from any thread.
 */
final class PivotRecords {

    private PivotRecords() {
    }

    /**
     * The ids of one write, in the order given, each once, with the pivot attributes given per id.
     */
    static final class IdSet {

        private final List<Object> ids;
        private final Map<Object, Map<String, Object>> attributes;

        private IdSet(List<Object> ids, Map<Object, Map<String, Object>> attributes) {
            this.ids = Collections.unmodifiableList(ids);
            this.attributes = Collections.unmodifiableMap(attributes);
        }

        /**
         * Returns the ids as given, the first spelling of each.
         *
         * @return the ids, unmodifiable
         */
        List<Object> ids() {
            return ids;
        }

        /**
         * Returns the attributes given per id, keyed by the normal form of the id.
         *
         * @return the attributes, unmodifiable; an id given without attributes maps to an empty map
         */
        Map<Object, Map<String, Object>> attributes() {
            return attributes;
        }

        /**
         * Returns the attributes given for one id.
         *
         * @param id the id, in any spelling
         * @return the attributes, empty when none were given or the id is absent
         */
        Map<String, Object> attributesOf(Object id) {
            Map<String, Object> found = attributes.get(EagerKeys.normalise(id));
            return found == null ? Collections.<String, Object>emptyMap() : found;
        }

        /**
         * Returns whether the set holds an id.
         *
         * @param id the id, in any spelling
         * @return {@code true} if an id with the same normal form is in the set
         */
        boolean contains(Object id) {
            return attributes.containsKey(EagerKeys.normalise(id));
        }

        /**
         * Returns whether the set holds no id.
         *
         * @return {@code true} for no ids
         */
        boolean isEmpty() {
            return ids.isEmpty();
        }

        /**
         * Returns the number of distinct ids.
         *
         * @return the id count
         */
        int size() {
            return ids.size();
        }
    }

    /**
     * Parses the ids of a write into a set of distinct ids with their per-id attributes.
     *
     * @param ids a value, a model, a collection or array of values and models, or a map from id to
     *     its pivot attributes
     * @param relatedKeyName the related key attribute read from a model
     * @return the ids, in the order given
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or a model has no related key
     */
    static IdSet parse(Object ids, String relatedKeyName) {
        Objects.requireNonNull(ids, "ids");
        List<Object> order = new ArrayList<>();
        Map<Object, Map<String, Object>> attributes = new LinkedHashMap<>();
        if (ids instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) ids).entrySet()) {
                add(order, attributes, keyOf(entry.getKey(), relatedKeyName), attributesOf(entry.getValue()));
            }
        } else {
            for (Object id : parseIds(ids, relatedKeyName)) {
                add(order, attributes, id, Collections.<String, Object>emptyMap());
            }
        }
        return new IdSet(order, attributes);
    }

    /**
     * Parses the ids of a write into a plain list, duplicates kept. A map gives its keys.
     *
     * @param ids a value, a model, a collection or array of values and models, or a map from id to
     *     its pivot attributes
     * @param relatedKeyName the related key attribute read from a model
     * @return the ids, in the order given
     * @throws NullPointerException if {@code ids} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or a model has no related key
     */
    static List<Object> parseIds(Object ids, String relatedKeyName) {
        Objects.requireNonNull(ids, "ids");
        List<Object> keys = new ArrayList<>();
        if (ids instanceof Map) {
            for (Object id : ((Map<?, ?>) ids).keySet()) {
                keys.add(keyOf(id, relatedKeyName));
            }
        } else if (ids instanceof Collection) {
            for (Object id : (Collection<?>) ids) {
                keys.add(keyOf(id, relatedKeyName));
            }
        } else if (ids instanceof Object[]) {
            for (Object id : (Object[]) ids) {
                keys.add(keyOf(id, relatedKeyName));
            }
        } else {
            keys.add(keyOf(ids, relatedKeyName));
        }
        return keys;
    }

    /**
     * Returns a copy of a set where the shared attributes are laid over each id's own attributes,
     * so a shared value wins over a per-id value of the same column, as in Laravel.
     *
     * @param set the parsed ids
     * @param shared the attributes written for every id, never {@code null}
     * @return the new set, same ids in the same order
     */
    static IdSet withSharedAttributes(IdSet set, Map<String, ?> shared) {
        Map<Object, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map.Entry<Object, Map<String, Object>> entry : set.attributes().entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>(entry.getValue());
            row.putAll(shared);
            merged.put(entry.getKey(), Collections.unmodifiableMap(row));
        }
        return new IdSet(new ArrayList<>(set.ids()), merged);
    }

    /**
     * Builds one join table row: the related key first, then the fixed columns, then the attributes.
     *
     * @param relatedPivotKey the join table column holding the related key
     * @param id the related key value, bound as given
     * @param tail the foreign key, timestamps and declared pivot values, in write order
     * @param attributes the attributes of this id
     * @return the row, a new map in column order
     */
    static Map<String, Object> record(String relatedPivotKey, Object id, Map<String, Object> tail,
            Map<String, Object> attributes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(relatedPivotKey, id);
        row.putAll(tail);
        row.putAll(attributes);
        return row;
    }

    /**
     * Refuses rows of one insert that do not share one column set, before anything is written.
     *
     * @param rows the rows of one insert
     * @throws IllegalArgumentException if two rows name different columns
     */
    static void requireSameColumns(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Set<String> first = rows.get(0).keySet();
        for (Map<String, Object> row : rows) {
            if (!row.keySet().equals(first)) {
                throw new IllegalArgumentException("Every attached row must have the same columns: "
                        + first + " and " + row.keySet());
            }
        }
    }

    /**
     * Returns how many rows one insert statement may carry within a binding budget.
     *
     * @param columnCount the columns of each row
     * @param budget the largest number of bindings one statement may carry
     * @return the row count, at least 1
     */
    static int rowsPerStatement(int columnCount, int budget) {
        return Math.max(1, budget / Math.max(1, columnCount));
    }

    /**
     * Casts a key to the type a sync result reports: a {@code Long} for an integer related key,
     * text otherwise.
     *
     * @param id the key, in any spelling
     * @param isIntegerKey whether the related key is an integer column
     * @return the {@code Long} or {@code String} form of the key
     */
    static Object castKey(Object id, boolean isIntegerKey) {
        Object normal = EagerKeys.normalise(id);
        if (isIntegerKey && normal instanceof Long) {
            return normal;
        }
        return String.valueOf(id);
    }

    /**
     * Returns the key a find looks for: a model's related key attribute, or the value itself.
     *
     * @param idOrModel the id or related model
     * @param relatedKeyName the related key attribute read from a model
     * @return the key
     * @throws IllegalArgumentException if a model has no related key
     */
    static Object findKey(Object idOrModel, String relatedKeyName) {
        return idOrModel instanceof Model ? modelKey((Model) idOrModel, relatedKeyName) : idOrModel;
    }

    /**
     * Returns the keys a find looks for, one per element, in order.
     *
     * @param idsOrModels the ids and related models
     * @param relatedKeyName the related key attribute read from a model
     * @return the keys, a new list
     * @throws IllegalArgumentException if a model has no related key
     */
    static List<Object> findKeys(Collection<?> idsOrModels, String relatedKeyName) {
        List<Object> keys = new ArrayList<>(idsOrModels.size());
        for (Object idOrModel : idsOrModels) {
            keys.add(findKey(idOrModel, relatedKeyName));
        }
        return keys;
    }

    private static void add(List<Object> order, Map<Object, Map<String, Object>> attributes, Object id,
            Map<String, Object> values) {
        Object normal = EagerKeys.normalise(id);
        if (!attributes.containsKey(normal)) {
            order.add(id);
            attributes.put(normal, values);
        }
    }

    private static Object keyOf(Object id, String relatedKeyName) {
        if (id == null) {
            throw new IllegalArgumentException("A pivot id may not be null");
        }
        return id instanceof Model ? modelKey((Model) id, relatedKeyName) : id;
    }

    private static Object modelKey(Model model, String relatedKeyName) {
        Object key = model.getAttribute(relatedKeyName);
        if (key == null) {
            throw new IllegalArgumentException("Model [" + model.getClass().getName() + "] has no value for ["
                    + relatedKeyName + "]");
        }
        return key;
    }

    private static Map<String, Object> attributesOf(Object value) {
        if (value == null) {
            return Collections.emptyMap();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Pivot attributes of an id must be a map: "
                    + value.getClass().getName());
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
