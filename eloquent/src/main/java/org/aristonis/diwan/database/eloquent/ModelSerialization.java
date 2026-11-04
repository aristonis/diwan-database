package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.CastSerializer;
import org.aristonis.diwan.database.eloquent.casts.CastValues;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.casts.InvalidCastException;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;
import org.aristonis.diwan.database.eloquent.json.JsonException;

/**
 * The serialization layer of {@link Model}: the map and JSON forms, shaped by the hidden, visible
 * and appended attribute lists.
 *
 * <p>Each list starts as the model's {@link #hidden()}, {@link #visible()} or {@link #appends()},
 * copied on first use; the instance methods then change this model's copy only. A key in a
 * non-empty visible list is the only kind kept, and a hidden key is always dropped — visible
 * filters first, hidden removes after, as Laravel.
 *
 * <p>Each kept value takes the first form that applies: the accessor's result; the cast's
 * {@link CastSerializer} output, or else its cast value; for an uncast {@code created_at} or
 * {@code updated_at} column of a model that uses timestamps, the datetime form; else the stored
 * value. The result then goes through the plain-form rules: moments become UTC text with
 * microseconds, dates ISO text, enums their stored value, maps and lists fresh copies.
 */
abstract class ModelSerialization extends ModelAccessors {

    private static final String DATETIME = "datetime";

    private List<String> hiddenKeys;

    private List<String> visibleKeys;

    private List<String> appendedKeys;

    ModelSerialization() {
    }

    /**
     * Returns the model as a map of plain values: text, numbers, booleans, {@code null}, maps and
     * lists, plus any value a cast or accessor returns that has no plain form.
     *
     * <p>Appended keys follow the stored ones, each read through its accessor, and are subject to
     * the visible and hidden lists like any other key. Loaded relations follow the appends, each
     * under its name in snake case unless {@link #snakeRelationKeys()} says otherwise, and filtered
     * by the same visible and hidden lists: a model becomes its own map, a {@link ModelCollection}
     * a list of maps, and a loaded {@code null} stays {@code null}. A relation key replaces an
     * attribute of the same name.
     *
     * <p>A model met again while it is already being serialised, as in a post whose author holds
     * the same post, writes its attributes and appends only, so a cycle ends. The guard lets a model
     * go once its own map is done, so an instance shared by several parents is written in full at
     * each place it appears. With two-way relations loaded on many models, such as users holding
     * their posts and each post holding its author, the output therefore grows with the product of
     * the two sides — Laravel's behaviour too; drop one side with {@link #setHidden(List)} or
     * {@link Model#withoutRelations()} when that is too much.
     *
     * @return a new mutable map in attribute order, then append order, then relation order; never
     *     {@code null}
     * @throws IllegalStateException if an appended key has no accessor with a getter; the message
     *     names the key
     * @throws InvalidCastException if a cast cannot read or serialize a stored value, or an uncast
     *     timestamp column holds something that is not a moment
     */
    public Map<String, Object> toMap() {
        return toMap(new VisitPath());
    }

    /**
     * Returns the map form, guarded against cycles by the models already being serialised.
     *
     * @param path the models whose maps are being built, by identity, never {@code null}; this
     *     model enters it for the duration of the call and leaves after
     * @return a new mutable map, never {@code null}
     */
    final Map<String, Object> toMap(VisitPath path) {
        Model model = self();
        if (!path.enter(model)) {
            return attributesToMap();
        }
        try {
            Map<String, Object> result = attributesToMap();
            appendRelations(result, path);
            return result;
        } finally {
            path.leave(model);
        }
    }

    /**
     * Writes the loaded relations into a map that already holds the attributes and appends. This
     * layer has no relations, so it writes nothing.
     *
     * @param result the map being built, never {@code null}
     * @param path the models whose maps are being built, never {@code null}
     */
    void appendRelations(Map<String, Object> result, VisitPath path) {
    }

    private Map<String, Object> attributesToMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getAttributes().entrySet()) {
            if (isShown(entry.getKey())) {
                result.put(entry.getKey(), serialValue(entry.getKey(), entry.getValue()));
            }
        }
        for (String key : appendedKeys()) {
            if (!isShown(key)) {
                continue;
            }
            if (!hasGetter(key)) {
                throw new IllegalStateException("Appended attribute [" + key + "] has no accessor on ["
                        + getClass().getName() + "]");
            }
            result.put(key, SerialValues.toSerializable(getAttribute(key)));
        }
        return result;
    }

    /**
     * Returns the model as JSON text: {@code toMap()} encoded by the model's JSON codec.
     *
     * @return the JSON text, never {@code null}
     * @throws JsonException if a value cannot be encoded, such as a {@code NaN} or infinite
     *     number, or a {@code byte[]}
     * @throws IllegalStateException if an appended key has no accessor with a getter
     * @throws InvalidCastException if a cast cannot read or serialize a stored value
     */
    public String toJson() {
        return getJsonCodec().encode(toMap());
    }

    /**
     * Returns the attributes this model leaves out of its map and JSON forms.
     *
     * @return a read-only copy, never {@code null}
     */
    public List<String> getHidden() {
        return Collections.unmodifiableList(new ArrayList<>(hiddenKeys()));
    }

    /**
     * Replaces the attributes this model leaves out of its map and JSON forms.
     *
     * @param keys the hidden attributes, never {@code null}; copied
     * @return this model
     * @throws NullPointerException if {@code keys} or any key is {@code null}
     */
    public Model setHidden(List<String> keys) {
        hiddenKeys = copyOf(keys);
        return self();
    }

    /**
     * Adds attributes to the hidden list; a key already there is not added twice.
     *
     * @param keys the attributes to hide, never {@code null}
     * @return this model
     * @throws NullPointerException if {@code keys} or any key is {@code null}
     */
    public Model makeHidden(String... keys) {
        hiddenKeys = merged(hiddenKeys(), keys);
        return self();
    }

    /**
     * Returns the only attributes this model keeps in its map and JSON forms; empty means every
     * attribute.
     *
     * @return a read-only copy, never {@code null}
     */
    public List<String> getVisible() {
        return Collections.unmodifiableList(new ArrayList<>(visibleKeys()));
    }

    /**
     * Replaces the only attributes this model keeps in its map and JSON forms; empty means every
     * attribute.
     *
     * @param keys the visible attributes, never {@code null}; copied
     * @return this model
     * @throws NullPointerException if {@code keys} or any key is {@code null}
     */
    public Model setVisible(List<String> keys) {
        visibleKeys = copyOf(keys);
        return self();
    }

    /**
     * Shows attributes: removes them from the hidden list, and adds them to the visible list when
     * that list is not empty — an empty visible list already keeps every attribute.
     *
     * @param keys the attributes to show, never {@code null}
     * @return this model
     * @throws NullPointerException if {@code keys} or any key is {@code null}
     */
    public Model makeVisible(String... keys) {
        List<String> shown = copyOf(Arrays.asList(keys));
        List<String> hidden = new ArrayList<>(hiddenKeys());
        hidden.removeAll(shown);
        hiddenKeys = hidden;
        if (!visibleKeys().isEmpty()) {
            visibleKeys = merged(visibleKeys(), keys);
        }
        return self();
    }

    /**
     * Returns the computed attributes this model adds to its map and JSON forms.
     *
     * @return a read-only copy, never {@code null}
     */
    public List<String> getAppends() {
        return Collections.unmodifiableList(new ArrayList<>(appendedKeys()));
    }

    /**
     * Adds computed attributes to the map and JSON forms; a key already there is not added twice.
     * Each needs an accessor with a getter by the time the model is serialized.
     *
     * @param keys the attributes to append, never {@code null}
     * @return this model
     * @throws NullPointerException if {@code keys} or any key is {@code null}
     */
    public Model append(String... keys) {
        appendedKeys = merged(appendedKeys(), keys);
        return self();
    }

    /**
     * Replaces the computed attributes added to the map and JSON forms.
     *
     * @param keys the appended attributes, never {@code null}; copied
     * @return this model
     * @throws NullPointerException if {@code keys} or any key is {@code null}
     */
    public Model setAppends(List<String> keys) {
        appendedKeys = copyOf(keys);
        return self();
    }

    /**
     * Removes every appended attribute from this model's map and JSON forms.
     *
     * @return this model
     */
    public Model withoutAppends() {
        appendedKeys = Collections.emptyList();
        return self();
    }

    final boolean isShown(String key) {
        List<String> visible = visibleKeys();
        return (visible.isEmpty() || visible.contains(key)) && !hiddenKeys().contains(key);
    }

    private Object serialValue(String key, Object raw) {
        if (hasGetter(key)) {
            return SerialValues.toSerializable(getAttribute(key));
        }
        Cast<?> cast = getCasts().get(key);
        if (cast != null) {
            return SerialValues.toSerializable(castSerialized(cast, key));
        }
        if (raw != null && isImplicitDate(key)) {
            return implicitDate(key, raw);
        }
        return SerialValues.toSerializable(raw);
    }

    @SuppressWarnings("unchecked")
    private Object castSerialized(Cast<?> cast, String key) {
        Object value = getAttribute(key);
        if (value == null || !(cast instanceof CastSerializer)) {
            return value;
        }
        // The serializer's value type is erased; it receives what the same cast's get returned.
        CastSerializer<Object> serializer = (CastSerializer<Object>) cast;
        try {
            return serializer.serialize(self(), key, value, rawAttributesView());
        } catch (InvalidCastException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidCastException(getClass(), key, Casts.typeName(cast), e);
        }
    }

    private boolean isImplicitDate(String key) {
        // Laravel treats the timestamp columns as dates even without a cast; here that holds
        // for serialization only, so reads and dirty checks stay raw.
        return self().usesTimestamps()
                && (key.equals(getCreatedAtColumn()) || key.equals(getUpdatedAtColumn()));
    }

    private Object implicitDate(String key, Object raw) {
        try {
            return SerialValues.MOMENT_FORMAT.format(CastValues.toInstant(raw, getDateFormat()));
        } catch (RuntimeException e) {
            throw new InvalidCastException(getClass(), key, DATETIME, e);
        }
    }

    private List<String> hiddenKeys() {
        if (hiddenKeys == null) {
            hiddenKeys = copyOf(hidden());
        }
        return hiddenKeys;
    }

    private List<String> visibleKeys() {
        if (visibleKeys == null) {
            visibleKeys = copyOf(visible());
        }
        return visibleKeys;
    }

    private List<String> appendedKeys() {
        if (appendedKeys == null) {
            appendedKeys = copyOf(appends());
        }
        return appendedKeys;
    }

    private static List<String> copyOf(List<String> keys) {
        Objects.requireNonNull(keys, "keys");
        List<String> copy = new ArrayList<>(keys.size());
        for (String key : keys) {
            copy.add(Objects.requireNonNull(key, "key"));
        }
        return copy;
    }

    private static List<String> merged(List<String> current, String... keys) {
        Objects.requireNonNull(keys, "keys");
        List<String> result = new ArrayList<>(current);
        for (String key : keys) {
            if (!result.contains(Objects.requireNonNull(key, "key"))) {
                result.add(key);
            }
        }
        return result;
    }
}
