package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.UniqueConstraintViolationException;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.EloquentAccess;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * The related-side writes of a many-to-many relation: they save or create a related model and link
 * it to the parent in the join table. Laravel's {@code save}, {@code saveMany}, {@code create},
 * {@code createMany}, {@code findOrNew}, {@code firstOrNew}, {@code firstOrCreate},
 * {@code createOrFirst} and {@code updateOrCreate} on {@code BelongsToMany}.
 *
 * <p>Saving the related model and linking it run in one transaction, with the related model
 * rebound to the transaction's connection, so a failed link leaves no related row behind. A
 * {@code createMany} that fails on a record rolls back every record of the call. The owner
 * touches run after the commit, so a touch that fails leaves the saved rows committed. The
 * related model's own save does not touch its owners; the link does, once.
 *
 * <p>A related model bound to another connection than the parent is saved outside the link
 * transaction, so its row is not rolled back with a failed link.
 *
 * <p>Every write needs a parent with a key. A pivot-attribute list is aligned with the models or
 * records by index.
 *
 * @param <R> the related model type
 */
abstract class PivotModelWrites<R extends Model> extends PivotSync<R> {

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
    PivotModelWrites(EloquentBuilder<R> query, Model parent, String table, String foreignPivotKey,
            String relatedPivotKey, String parentKey, String relatedKey, String relation) {
        super(query, parent, table, foreignPivotKey, relatedPivotKey, parentKey, relatedKey, relation);
    }

    /**
     * Saves the related model and links it to the parent, with no pivot attributes and the owners
     * touched.
     *
     * @param model the related model, never {@code null}
     * @return the same model
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R save(R model) {
        return save(model, Collections.<String, Object>emptyMap(), true);
    }

    /**
     * Saves the related model and links it to the parent with pivot attributes, owners touched.
     *
     * @param model the related model, never {@code null}
     * @param pivotAttributes the join-table attributes of the link, never {@code null}
     * @return the same model
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R save(R model, Map<String, ?> pivotAttributes) {
        return save(model, pivotAttributes, true);
    }

    /**
     * Saves the related model and links it to the parent. A save vetoed by a hook leaves the
     * model unsaved and unlinked; check {@link Model#exists()}. Touches run after the commit. A
     * model bound to another connection than the parent is saved outside the link transaction.
     *
     * @param model the related model, never {@code null}
     * @param pivotAttributes the join-table attributes of the link, never {@code null}
     * @param touch whether to touch the owners after the link
     * @return the same model
     * @throws NullPointerException if {@code model} or {@code pivotAttributes} is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R save(R model, Map<String, ?> pivotAttributes, boolean touch) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(pivotAttributes, "pivotAttributes");
        requireParentKey();
        boolean isLinked = inTransaction(() -> saveLinked(model, pivotAttributes));
        if (isLinked) {
            touchOwners(touch, true);
        }
        return model;
    }

    /**
     * Saves and links each model in order, with no pivot attributes, then touches the owners once.
     *
     * @param models the related models, never {@code null}, no {@code null} element
     * @return the models, in order, unmodifiable, never {@code null}
     * @throws NullPointerException if {@code models} or an element is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final List<R> saveMany(Collection<? extends R> models) {
        Objects.requireNonNull(models, "models");
        return saveMany(models, emptyAttributes(models.size()));
    }

    /**
     * Saves and links each model in order with its own pivot attributes, then touches the owners
     * once, or not at all when no model was linked. The size check runs before any write. A model
     * bound to another connection than the parent is saved outside the link transaction.
     *
     * @param models the related models, never {@code null}, no {@code null} element
     * @param pivotAttributes the join-table attributes, one map per model by index, never
     *     {@code null}
     * @return the models, in order, unmodifiable, never {@code null}
     * @throws NullPointerException if an argument or an element is {@code null}
     * @throws IllegalArgumentException if the two collections differ in size
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final List<R> saveMany(Collection<? extends R> models, List<? extends Map<String, ?>> pivotAttributes) {
        Objects.requireNonNull(models, "models");
        requireSameSize(models.size(), pivotAttributes);
        requireParentKey();
        List<R> saved = new ArrayList<>(models);
        int linked = inTransaction(() -> {
            int count = 0;
            for (int i = 0; i < saved.size(); i++) {
                if (saveLinked(Objects.requireNonNull(saved.get(i), "model"), pivotAttributes.get(i))) {
                    count++;
                }
            }
            return count;
        });
        touchOwners(true, linked > 0);
        return Collections.unmodifiableList(saved);
    }

    /**
     * Creates a related model from the attributes and links it, with no pivot attributes and the
     * owners touched.
     *
     * @param attributes the related model's attributes, never {@code null}
     * @return the saved model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R create(Map<String, ?> attributes) {
        return create(attributes, Collections.<String, Object>emptyMap(), true);
    }

    /**
     * Creates a related model from the attributes and links it with pivot attributes, owners
     * touched.
     *
     * @param attributes the related model's attributes, never {@code null}
     * @param pivotAttributes the join-table attributes of the link, never {@code null}
     * @return the saved model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R create(Map<String, ?> attributes, Map<String, ?> pivotAttributes) {
        return create(attributes, pivotAttributes, true);
    }

    /**
     * Creates a related model from the attributes and links it. Touches run after the commit. A
     * model bound to another connection than the parent is saved outside the link transaction.
     *
     * @param attributes the related model's attributes, never {@code null}
     * @param pivotAttributes the join-table attributes of the link, never {@code null}
     * @param touch whether to touch the owners after the link
     * @return the saved model, never {@code null}
     * @throws NullPointerException if {@code attributes} or {@code pivotAttributes} is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R create(Map<String, ?> attributes, Map<String, ?> pivotAttributes, boolean touch) {
        Objects.requireNonNull(attributes, "attributes");
        requireParentKey();
        return save(make(attributes), pivotAttributes, touch);
    }

    /**
     * Creates and links one related model per record, in order, with no pivot attributes, in one
     * transaction.
     *
     * @param records the attribute maps, never {@code null}, no {@code null} element
     * @return the saved models, in order, unmodifiable, never {@code null}
     * @throws NullPointerException if {@code records} or an element is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement; every
     *     record of the call is rolled back
     */
    public final List<R> createMany(List<? extends Map<String, ?>> records) {
        Objects.requireNonNull(records, "records");
        return createMany(records, emptyAttributes(records.size()));
    }

    /**
     * Creates and links one related model per record with its own pivot attributes, in one
     * transaction, then touches the owners once. The size check runs before any write.
     *
     * @param records the attribute maps, never {@code null}, no {@code null} element
     * @param pivotAttributes the join-table attributes, one map per record by index, never
     *     {@code null}
     * @return the saved models, in order, unmodifiable, never {@code null}
     * @throws NullPointerException if an argument or an element is {@code null}
     * @throws IllegalArgumentException if the two lists differ in size
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement; every
     *     record of the call is rolled back
     */
    public final List<R> createMany(List<? extends Map<String, ?>> records,
            List<? extends Map<String, ?>> pivotAttributes) {
        Objects.requireNonNull(records, "records");
        requireSameSize(records.size(), pivotAttributes);
        requireParentKey();
        int[] linked = new int[1];
        List<R> created = inTransaction(() -> {
            linked[0] = 0;
            List<R> saved = new ArrayList<>(records.size());
            for (int i = 0; i < records.size(); i++) {
                R model = make(records.get(i));
                if (saveLinked(model, pivotAttributes.get(i))) {
                    linked[0]++;
                }
                saved.add(model);
            }
            return saved;
        });
        touchOwners(true, linked[0] > 0);
        return Collections.unmodifiableList(created);
    }

    /**
     * Finds a related model of this relation by key, or returns a new unsaved one.
     *
     * @param id the related key, a model, or anything {@code find} accepts
     * @return the model found, or a new related model with no attributes, never {@code null}
     */
    public final R findOrNew(Object id) {
        Optional<R> found = find(id);
        if (found.isPresent()) {
            return found.get();
        }
        return getRelated().newInstance();
    }

    /**
     * Returns the first related model matching the attributes, or a new unsaved one filled from
     * them. The search covers the whole related table, not only the rows linked to the parent.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     */
    public final R firstOrNew(Map<String, ?> attributes) {
        return firstOrNew(attributes, Collections.<String, Object>emptyMap());
    }

    /**
     * Returns the first related model matching the attributes, or a new unsaved one filled from
     * the attributes and the values. The search covers the whole related table, not only the
     * rows linked to the parent.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for a new model, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute of a new model
     */
    public final R firstOrNew(Map<String, ?> attributes, Map<String, ?> values) {
        return access().newQueryOn(getRelated()).firstOrNew(attributes, values);
    }

    /**
     * Returns the first linked related model matching the attributes, or creates one.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R firstOrCreate(Map<String, ?> attributes) {
        return firstOrCreate(attributes, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap());
    }

    /**
     * Returns the first linked related model matching the attributes, or creates one from the
     * attributes and the values.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for a new model, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R firstOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        return firstOrCreate(attributes, values, Collections.<String, Object>emptyMap());
    }

    /**
     * Returns the first linked related model matching the attributes. When none is linked, looks
     * the whole related table: a match there is linked to the parent (a link made by someone else
     * in between is accepted); with no match a model is created and linked. The match found in the
     * whole table carries no {@code pivot} attribute. Pivot attributes apply to a new link only.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for a new model, never {@code null}
     * @param pivotAttributes the join-table attributes of a new link, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R firstOrCreate(Map<String, ?> attributes, Map<String, ?> values,
            Map<String, ?> pivotAttributes) {
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(pivotAttributes, "pivotAttributes");
        requireParentKey();
        Optional<R> linked = prepared(Select.REPLACE).where(attributes).first();
        if (linked.isPresent()) {
            return linked.get();
        }
        Optional<R> anywhere = access().newQueryOn(getRelated()).where(attributes).first();
        if (anywhere.isPresent()) {
            attachQuietly(anywhere.get(), pivotAttributes);
            return anywhere.get();
        }
        return createOrFirst(attributes, values, pivotAttributes);
    }

    /**
     * Creates and links a related model from the attributes; when a unique index refuses it,
     * returns the existing match instead.
     *
     * @param attributes the columns to match, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if {@code attributes} is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final R createOrFirst(Map<String, ?> attributes) {
        return createOrFirst(attributes, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap());
    }

    /**
     * Creates and links a related model from the attributes and values; when a unique index
     * refuses it, returns the existing match instead.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for the new model, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final R createOrFirst(Map<String, ?> attributes, Map<String, ?> values) {
        return createOrFirst(attributes, values, Collections.<String, Object>emptyMap());
    }

    /**
     * Creates and links a related model from the attributes and values. The create runs in its own
     * transaction, a savepoint inside a caller's transaction, so a refusal by a unique index does
     * not abort the caller's transaction. On a refusal the whole related table is searched: a match
     * is linked to the parent (a link made by someone else in between is accepted) and returned.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values more attributes for the new model, never {@code null}
     * @param pivotAttributes the join-table attributes of a new link, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws UniqueConstraintViolationException if the insert is refused and no match is found
     */
    public final R createOrFirst(Map<String, ?> attributes, Map<String, ?> values,
            Map<String, ?> pivotAttributes) {
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(values, "values");
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.putAll(values);
        try {
            return create(merged, pivotAttributes, true);
        } catch (UniqueConstraintViolationException e) {
            Optional<R> found = access().newQueryOn(getRelated()).where(attributes).first();
            if (!found.isPresent()) {
                throw e;
            }
            attachQuietly(found.get(), pivotAttributes);
            return found.get();
        }
    }

    /**
     * Updates the first linked related model matching the attributes with the values, or creates
     * and links one from both. The update does not touch the owners.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values the attributes to set, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R updateOrCreate(Map<String, ?> attributes, Map<String, ?> values) {
        return updateOrCreate(attributes, values, Collections.<String, Object>emptyMap());
    }

    /**
     * Updates the first linked related model matching the attributes with the values, or creates
     * and links one from both. Pivot attributes apply only when a model is created or linked; the
     * update does not touch the owners.
     *
     * @param attributes the columns to match, never {@code null}
     * @param values the attributes to set, never {@code null}
     * @param pivotAttributes the join-table attributes of a new link, never {@code null}
     * @return the model, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the parent has no key
     * @throws org.aristonis.diwan.database.eloquent.MassAssignmentException if the related
     *     model's guards refuse an attribute
     * @throws org.aristonis.diwan.database.QueryException if the database refuses a statement
     */
    public final R updateOrCreate(Map<String, ?> attributes, Map<String, ?> values,
            Map<String, ?> pivotAttributes) {
        R model = firstOrCreate(attributes, values, pivotAttributes);
        if (!model.wasRecentlyCreated()) {
            model.fill(values);
            Supplier<Boolean> save = model::save;
            Model.withoutTouching(getRelated().getClass(), save);
        }
        return model;
    }

    // ---- helpers ----

    private static EloquentAccess access() {
        return Access.eloquent();
    }

    private R make(Map<String, ?> attributes) {
        R instance = getRelated().newInstance();
        instance.fill(Objects.requireNonNull(attributes, "attributes"));
        return instance;
    }

    private static List<Map<String, ?>> emptyAttributes(int size) {
        List<Map<String, ?>> empty = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            empty.add(Collections.<String, Object>emptyMap());
        }
        return empty;
    }

    private static void requireSameSize(int size, List<? extends Map<String, ?>> pivotAttributes) {
        Objects.requireNonNull(pivotAttributes, "pivotAttributes");
        if (pivotAttributes.size() != size) {
            throw new IllegalArgumentException("pivotAttributes has " + pivotAttributes.size()
                    + " entries for " + size + " records; they are matched by index");
        }
    }

    // Saves the model on the transaction's factory with its own touches off, then links it; a
    // vetoed save returns false and links nothing. Runs inside inTransaction.
    private boolean saveLinked(R model, Map<String, ?> pivotAttributes) {
        QueryFactory factory = activeFactory();
        boolean isRebound = factory != null && model.getConnection() == getParent().getConnection();
        QueryFactory prior = isRebound ? access().swapFactory(model, factory) : null;
        try {
            Supplier<Boolean> save = model::save;
            if (!Model.withoutTouching(getRelated().getClass(), save)) {
                return false;
            }
            attach(model, pivotAttributes, false);
            return true;
        } finally {
            if (isRebound) {
                access().swapFactory(model, prior);
            }
        }
    }

    // Links an existing model; a unique violation from a racing link is swallowed when the link is
    // there afterwards, and rethrown when it is not.
    private void attachQuietly(R model, Map<String, ?> pivotAttributes) {
        Connection connection = getParent().getConnection();
        try {
            if (connection.transactionLevel() > 0) {
                connection.transaction(c -> {
                    attach(model, pivotAttributes, false);
                    return null;
                });
            } else {
                attach(model, pivotAttributes, false);
            }
        } catch (UniqueConstraintViolationException e) {
            if (!isLinked(model)) {
                throw e;
            }
            return;
        }
        touchOwners(true, true);
    }

    private boolean isLinked(R model) {
        Object key = model.getAttribute(getRelatedKeyName());
        return newPivotQuery().where(getRelatedPivotKeyName(), key).exists();
    }
}
