package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.UniqueConstraintViolationException;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;

/**
 * The {@code findOrNew}, {@code firstOrNew}, {@code firstOrCreate}, {@code createOrFirst} and
 * {@code updateOrCreate} methods of the through relations.
 *
 * <p>A lookup runs through the relation, so it sees only related rows joined to the far parent, and
 * it names columns with the related table. A created row is saved through the related model alone:
 * nothing links it to the far parent, because the link lives in the through table. Such a row does
 * not appear in the relation until the through table points to it.
 *
 * @param <R> the related model class
 * @param <S> the concrete relation class
 */
abstract class ThroughCreators<R extends Model, S extends ThroughReads<R, S>> extends ThroughReads<R, S> {

    /**
     * Builds the relation over the given query.
     *
     * @param query the related model's query, never {@code null}
     * @param parent the model the relation is read from, never {@code null}
     * @param relationName the relation's name, or {@code null} when none is known
     */
    ThroughCreators(EloquentBuilder<R> query, Model parent, String relationName) {
        super(query, parent, relationName);
    }

    /**
     * Finds a related model by its key, or returns a new unsaved one. The new model has no
     * attributes and is not linked to the far parent.
     *
     * @param id the key of the related model
     * @return the model, never {@code null}
     * @throws IllegalArgumentException if {@code id} is a collection
     */
    public final R findOrNew(Object id) {
        Optional<R> found = find(id);
        return found.isPresent() ? found.get() : make(Collections.<String, Object>emptyMap());
    }

    /**
     * Returns the first related model matching the attributes, or a new unsaved one filled from
     * them. The new model is not linked to the far parent.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public final R firstOrNew(Map<String, ?> attributes) {
        return firstOrNew(attributes, Collections.<String, Object>emptyMap());
    }

    /**
     * Returns the first related model matching the attributes, or a new unsaved one filled from the
     * attributes and the values. The new model is not linked to the far parent.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for a new model, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related model's
     *     guards refuse an attribute
     */
    public final R firstOrNew(Map<String, ?> attributes, Map<String, ?> values) {
        Map<String, Object> merged = merge(attributes, values);
        Optional<R> found = lookup(attributes);
        return found.isPresent() ? found.get() : make(merged);
    }

    /**
     * Returns the first related model matching the attributes, or creates one from them. The
     * created row is not linked to the far parent.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final R firstOrCreate(Map<String, ?> attributes) {
        return firstOrCreate(attributes, Collections.<String, Object>emptyMap());
    }

    /**
     * Returns the first related model matching the attributes, or creates one from the attributes
     * and the values. The created row is not linked to the far parent, so repeating the call without
     * the through table pointing to the row inserts another one unless the attributes find it.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for a new model, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related model's
     *     guards refuse an attribute
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final R firstOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        Optional<R> found = lookup(attributes);
        return found.isPresent() ? found.get() : createOrFirst(attributes, values);
    }

    /**
     * Creates a related model from the attributes, or returns the existing match when a unique
     * index refuses the insert. Inside a transaction the insert runs in a savepoint, so the refusal
     * does not abort the transaction. The created row is not linked to the far parent.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final R createOrFirst(Map<String, ?> attributes) {
        return createOrFirst(attributes, Collections.<String, Object>emptyMap());
    }

    /**
     * Creates a related model from the attributes and the values, or returns the existing match when
     * a unique index refuses the insert. Inside a transaction the insert runs in a savepoint, so the
     * refusal does not abort the transaction. The match is read through the relation. The created
     * row is not linked to the far parent.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for the new model, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related model's
     *     guards refuse an attribute
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     *     through the relation
     */
    public final R createOrFirst(Map<String, ?> attributes, Map<String, ?> values) {
        Map<String, Object> merged = merge(attributes, values);
        EloquentBuilder<R> builder = Access.eloquent().newQueryOn(getRelated());
        try {
            Connection connection = getRelated().getConnection();
            if (connection.transactionLevel() > 0) {
                return connection.transaction(c -> builder.create(merged));
            }
            return builder.create(merged);
        } catch (UniqueConstraintViolationException e) {
            Optional<R> found = lookup(attributes);
            if (found.isPresent()) {
                return found.get();
            }
            throw e;
        }
    }

    /**
     * Updates the first related model matching the attributes with the values, or creates one from
     * both. A created row is not linked to the far parent.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values the attributes to set, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related model's
     *     guards refuse an attribute
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final R updateOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        R model = firstOrCreate(attributes, values);
        if (!model.wasRecentlyCreated()) {
            model.fill(values).save();
        }
        return model;
    }

    // The lookup names each column with the related table: a bare name could belong to the join.
    private Optional<R> lookup(Map<String, ?> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        Map<String, Object> qualified = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : attributes.entrySet()) {
            qualified.put(getRelated().qualifyColumn(entry.getKey()), entry.getValue());
        }
        return prepared(PivotReads.Select.REPLACE).where(qualified).first();
    }

    private R make(Map<String, ?> values) {
        return Access.eloquent().newQueryOn(getRelated()).make(values);
    }

    private static Map<String, Object> merge(Map<String, ?> attributes, Map<String, ?> values) {
        Map<String, Object> merged = new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes"));
        merged.putAll(Objects.requireNonNull(values, "values"));
        return merged;
    }
}
