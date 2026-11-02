package org.aristonis.diwan.database.eloquent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.casts.CastValues;

/**
 * The attribute layer of {@link Model}: current values, the originals last read or saved, the
 * changes of the last save, typed getters and the dirty check.
 *
 * <p>The dirty check follows Laravel without casts: a key missing from the original is dirty;
 * {@code null} equals only {@code null}; numbers and numeric text compare by their text, so
 * {@code 1}, {@code 1L}, {@code 1.0} and {@code "1"} match but {@code "1.0"} does not; a moment
 * compares with any stored form of the same moment (an {@code Instant}, microseconds, a zoned or
 * local timestamp, or text in the date format), and a value that cannot be read as one is dirty;
 * byte arrays compare by content; anything else by {@code equals}. A {@code Boolean} is dirty
 * against a stored {@code 1} until casts exist.
 */
abstract class ModelAttributes extends ModelConfig {

    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final Map<String, Object> attributesView = Collections.unmodifiableMap(attributes);
    private Map<String, Object> original = new LinkedHashMap<>();
    private Map<String, Object> changes = new LinkedHashMap<>();
    private Map<String, Object> previous = new LinkedHashMap<>();

    ModelAttributes() {
    }

    /** Returns this layer as the model it belongs to, for fluent methods. */
    final Model self() {
        return (Model) this;
    }

    /**
     * Returns an attribute's current value.
     *
     * @param key the attribute, never {@code null}
     * @return the value, or {@code null} when absent or {@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public Object getAttribute(String key) {
        return attributes.get(Objects.requireNonNull(key, "key"));
    }

    /**
     * Sets an attribute's value, without any mass-assignment check.
     *
     * @param key the attribute, never {@code null}
     * @param value the value, may be {@code null}
     * @return this model
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public Model setAttribute(String key, Object value) {
        putRawAttribute(Objects.requireNonNull(key, "key"), value);
        return self();
    }

    /**
     * Returns whether the model holds an attribute, even a {@code null} one.
     *
     * @param key the attribute, never {@code null}
     * @return whether the attribute is present
     */
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    /**
     * Returns every attribute.
     *
     * @return a read-only copy, in insertion order, never {@code null}
     * @implNote Cached cast objects such as decoded JSON are encoded again on every call, so the
     *     cost grows with the size of those documents.
     */
    public Map<String, Object> getAttributes() {
        mergeObjectCasts();
        return copy(attributes);
    }

    /**
     * Replaces every attribute without any check, leaving the originals as they are.
     *
     * @param values the new attributes, never {@code null}; copied
     * @return this model
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public Model setRawAttributes(Map<String, ?> values) {
        return setRawAttributes(values, false);
    }

    /**
     * Replaces every attribute without any check.
     *
     * @param values the new attributes, never {@code null}; copied
     * @param sync whether the originals become these values too
     * @return this model
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public Model setRawAttributes(Map<String, ?> values, boolean sync) {
        replaceAttributes(Objects.requireNonNull(values, "values"));
        rawReplaced();
        if (sync) {
            syncOriginal();
        }
        return self();
    }

    /**
     * Returns an attribute's value as text: {@code toString} of the stored value.
     *
     * @param key the attribute
     * @return the text, or {@code null} when absent or {@code null}
     */
    public String getString(String key) {
        Object value = getAttribute(key);
        return value == null ? null : value.toString();
    }

    /**
     * Returns an attribute's value as a {@code Long}, from any whole number or numeric text.
     *
     * @param key the attribute
     * @return the value, or {@code null} when absent or {@code null}
     * @throws IllegalArgumentException if the value is not a whole number in the {@code long} range
     */
    public Long getLong(String key) {
        Object value = getAttribute(key);
        return value == null ? null : CastValues.toLong(value);
    }

    /**
     * Returns an attribute's value as an {@code Integer}, from any whole number or numeric text.
     *
     * @param key the attribute
     * @return the value, or {@code null} when absent or {@code null}
     * @throws IllegalArgumentException if the value is not a whole number in the {@code int} range
     */
    public Integer getInteger(String key) {
        Long value = getLong(key);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The value of " + key + " is outside the int range");
        }
        return value.intValue();
    }

    /**
     * Returns an attribute's value as a {@code Double}, from any number or numeric text.
     *
     * @param key the attribute
     * @return the value, or {@code null} when absent or {@code null}
     * @throws IllegalArgumentException if the value is not a number
     */
    public Double getDouble(String key) {
        Object value = getAttribute(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return CastValues.toBigDecimal(value).doubleValue();
    }

    /**
     * Returns an attribute's value as an exact {@code BigDecimal}; a {@code double} gives its
     * shortest decimal text, so {@code 0.1} gives {@code 0.1}.
     *
     * @param key the attribute
     * @return the value, or {@code null} when absent or {@code null}
     * @throws IllegalArgumentException if the value is not a finite number
     */
    public BigDecimal getBigDecimal(String key) {
        Object value = getAttribute(key);
        return value == null ? null : CastValues.toBigDecimal(value);
    }

    /**
     * Returns an attribute's value as a {@code Boolean}: a boolean as is, a number as non-zero,
     * text {@code 1}/{@code true} or {@code 0}/{@code false}/empty.
     *
     * @param key the attribute
     * @return the value, or {@code null} when absent or {@code null}
     * @throws IllegalArgumentException if the value is other text or another type
     */
    public Boolean getBoolean(String key) {
        Object value = getAttribute(key);
        return value == null ? null : CastValues.toBoolean(value);
    }

    /**
     * Returns an attribute's value as an {@code Instant}. Reads an {@code Instant}, microseconds
     * since the epoch, an {@code OffsetDateTime}, a {@code LocalDateTime} as UTC, text in the
     * model's date format as UTC, or ISO-8601 text with or without an offset.
     *
     * @param key the attribute
     * @return the moment, or {@code null} when absent or {@code null}
     * @throws IllegalArgumentException if the value is not a moment in any of those forms
     */
    public Instant getInstant(String key) {
        Object value = getAttribute(key);
        return value == null ? null : CastValues.toInstant(value, getDateFormat());
    }

    /**
     * Returns an attribute's original value: as last read from or saved to the database.
     *
     * @param key the attribute, never {@code null}
     * @return the value, or {@code null} when absent or {@code null}
     */
    public Object getOriginal(String key) {
        return original.get(key);
    }

    /**
     * Returns every original value.
     *
     * @return a read-only copy, never {@code null}
     */
    public Map<String, Object> getOriginal() {
        return copy(original);
    }

    /**
     * Returns an attribute's original value as stored, with no cast applied.
     *
     * @param key the attribute, never {@code null}
     * @return the value, or {@code null} when absent or {@code null}
     */
    public Object getRawOriginal(String key) {
        return original.get(key);
    }

    /**
     * Makes every current value the original.
     *
     * @return this model
     */
    public Model syncOriginal() {
        mergeObjectCasts();
        original = new LinkedHashMap<>(attributes);
        return self();
    }

    /**
     * Makes one attribute's current value its original.
     *
     * @param key the attribute, never {@code null}
     * @return this model
     */
    public Model syncOriginalAttribute(String key) {
        return syncOriginalAttributes(key);
    }

    /**
     * Makes the given attributes' current values their originals; an absent attribute loses its
     * original too.
     *
     * @param keys the attributes, never {@code null}
     * @return this model
     */
    public Model syncOriginalAttributes(String... keys) {
        mergeObjectCasts();
        for (String key : keys) {
            if (attributes.containsKey(key)) {
                original.put(key, attributes.get(key));
            } else {
                original.remove(key);
            }
        }
        return self();
    }

    /**
     * Records the dirty attributes as the changes of the last save, and their originals as the
     * previous values.
     *
     * @return this model
     */
    public Model syncChanges() {
        Map<String, Object> dirty = dirty();
        changes = dirty;
        previous = new LinkedHashMap<>();
        for (String key : dirty.keySet()) {
            if (original.containsKey(key)) {
                previous.put(key, original.get(key));
            }
        }
        return self();
    }

    /**
     * Puts every attribute back to its original and forgets the last changes.
     *
     * @return this model
     */
    public Model discardChanges() {
        replaceAttributes(original);
        changes = new LinkedHashMap<>();
        previous = new LinkedHashMap<>();
        rawReplaced();
        return self();
    }

    /**
     * Returns whether any of the given attributes, or any attribute when none is given, differs
     * from its original.
     *
     * @param keys the attributes, or none for every attribute
     * @return whether the model is dirty
     * @implNote Cached cast objects such as decoded JSON are encoded again on every call, so the
     *     cost grows with the size of those documents.
     */
    public boolean isDirty(String... keys) {
        return anyIn(dirty(), keys);
    }

    /**
     * Returns whether none of the given attributes, or no attribute when none is given, differs
     * from its original.
     *
     * @param keys the attributes, or none for every attribute
     * @return whether the model is clean
     */
    public boolean isClean(String... keys) {
        return !isDirty(keys);
    }

    /**
     * Returns whether the last save changed any of the given attributes, or any attribute when
     * none is given.
     *
     * @param keys the attributes, or none for every attribute
     * @return whether they changed
     */
    public boolean wasChanged(String... keys) {
        return anyIn(changes, keys);
    }

    /**
     * Returns the attributes that differ from their originals, with their current values.
     *
     * @return a read-only map, in attribute order, never {@code null}
     */
    public Map<String, Object> getDirty() {
        return Collections.unmodifiableMap(dirty());
    }

    /**
     * Returns the attributes the last save changed, with their saved values.
     *
     * @return a read-only copy, never {@code null}
     */
    public Map<String, Object> getChanges() {
        return copy(changes);
    }

    /**
     * Returns the values the last save replaced.
     *
     * @return a read-only copy, never {@code null}
     */
    public Map<String, Object> getPrevious() {
        return copy(previous);
    }

    /**
     * Returns whether an attribute's current value is the same as its original, by the rules in
     * the class description.
     *
     * @param key the attribute, never {@code null}
     * @return whether the value is unchanged
     */
    public boolean originalIsEquivalent(String key) {
        if (!original.containsKey(key)) {
            return false;
        }
        return AttributeValues.equivalent(attributes.get(key), original.get(key), getDateFormat());
    }

    private Map<String, Object> dirty() {
        mergeObjectCasts();
        Map<String, Object> dirty = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (!originalIsEquivalent(entry.getKey())) {
                dirty.put(entry.getKey(), entry.getValue());
            }
        }
        return dirty;
    }

    private static boolean anyIn(Map<String, Object> map, String... keys) {
        if (keys.length == 0) {
            return !map.isEmpty();
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    /** The attribute maps at one moment, so a failed save can put them back. */
    static final class Snapshot {
        private final Map<String, Object> attributes;
        private final Map<String, Object> original;
        private final Map<String, Object> changes;
        private final Map<String, Object> previous;
        private final Map<String, Object> castObjects;

        private Snapshot(ModelAttributes model) {
            castObjects = model.castObjects();
            attributes = new LinkedHashMap<>(model.attributes);
            original = new LinkedHashMap<>(model.original);
            changes = new LinkedHashMap<>(model.changes);
            previous = new LinkedHashMap<>(model.previous);
        }
    }

    final Snapshot snapshotAttributes() {
        mergeObjectCasts();
        return new Snapshot(this);
    }

    final void restoreAttributes(Snapshot snapshot) {
        replaceAttributes(snapshot.attributes);
        original = new LinkedHashMap<>(snapshot.original);
        changes = new LinkedHashMap<>(snapshot.changes);
        previous = new LinkedHashMap<>(snapshot.previous);
        rawReplaced();
        // The caller may still hold these objects; tracking them again keeps later mutations saved.
        restoreCastObjects(snapshot.castObjects);
    }

    /**
     * Returns an attribute's stored value, with no cast or accessor applied.
     *
     * @param key the attribute
     * @return the stored value, or {@code null} when absent or {@code null}
     */
    final Object getRawAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Stores a value as is, with no cast or accessor, and reports the write to
     * {@link #rawWritten(String)}.
     *
     * @param key the attribute, never {@code null}
     * @param value the stored value, may be {@code null}
     */
    final void putRawAttribute(String key, Object value) {
        attributes.put(key, value);
        rawWritten(key);
    }

    /**
     * Returns the stored attributes as a live read-only view; later writes show through it.
     *
     * @return the view, never {@code null}
     */
    final Map<String, Object> rawAttributesView() {
        return attributesView;
    }

    /**
     * Returns whether an attribute has an original value, even a {@code null} one.
     *
     * @param key the attribute
     * @return whether an original is present
     */
    final boolean hasRawOriginal(String key) {
        return original.containsKey(key);
    }

    /**
     * Writes cached cast objects back to their stored values before the attributes are read as a
     * whole; does nothing in this layer.
     */
    void mergeObjectCasts() {
    }

    /**
     * Called after one stored value is written; does nothing in this layer.
     *
     * @param key the attribute written
     */
    void rawWritten(String key) {
    }

    /** Called after the stored values are replaced as a whole; does nothing in this layer. */
    void rawReplaced() {
    }

    /**
     * Returns the cached cast objects for a snapshot; none in this layer.
     *
     * @return a copy holding the same object references, never {@code null}
     */
    Map<String, Object> castObjects() {
        return Collections.emptyMap();
    }

    /**
     * Tracks the cast objects of a snapshot again after a restore; does nothing in this layer.
     *
     * @param objects the objects taken by {@link #castObjects()}
     */
    void restoreCastObjects(Map<String, Object> objects) {
    }

    private void replaceAttributes(Map<String, ?> values) {
        Map<String, Object> fresh = new LinkedHashMap<>(values);
        attributes.clear();
        attributes.putAll(fresh);
    }

    private static Map<String, Object> copy(Map<String, Object> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
