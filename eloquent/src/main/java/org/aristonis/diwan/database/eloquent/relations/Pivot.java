package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.support.Inflector;

/**
 * A row of a belongsToMany join table, as a model: the pivot columns of a related model, such as
 * {@code role_user.status} for one of a user's roles. Read it through {@link #of(Model)} or
 * through the relation's pivot accessor.
 *
 * <p>A pivot is never incrementing and has no mass-assignment guard. It takes the table and the
 * connection it was built with, and its timestamp columns are its parent's. A subclass that
 * overrides {@code timestamps()} or {@code connectionName()} replaces the values that
 * {@code fromAttributes} and {@code fromRawAttributes} set.
 *
 * <p>A pivot with a key value writes by that key. A pivot with no key value, such as a join table
 * with no {@code id} column, writes by the two pivot key columns once {@link #setPivotKeys} has
 * set them and both hold a value, using their original values. Until then {@code update} and
 * {@code delete} throw an {@link IllegalStateException} that names the class and the table.
 * {@code increment}, {@code decrement}, {@code refresh} and {@code fresh} always need a key value
 * and throw the same exception without one. A clean pivot still saves without error, since it has
 * nothing to write.
 *
 * <p>The guards are overrides. A subclass that overrides {@code updating()} or {@code deleting()}
 * without calling {@code super} bypasses them. {@code push()} saves the related models one after
 * another with no transaction, so a dirty pivot without a key throws after the models before it
 * have been written.
 *
 * <p>Not safe to share across threads, like every model.
 */
public class Pivot extends Model {

    // Registers the plain pivot class, so the factories can build it through the registry.
    private static final ModelQuery<Pivot> ENTRY = ModelQuery.of(Pivot.class, Pivot::new);

    private Model pivotParent;
    private Model relatedModel;
    private String foreignKey;
    private String relatedKey;
    private String givenTable;
    private String givenConnection;
    private Boolean givenTimestamps;

    /**
     * Creates an empty pivot. A subclass that is built through a relation registers its own entry
     * with {@code ModelQuery.of(...)}, as any model does.
     */
    public Pivot() {
    }

    /**
     * Returns whether the database assigns the key on insert: never for a pivot.
     *
     * @return {@code false}
     */
    @Override
    protected boolean incrementing() {
        return false;
    }

    /**
     * Returns no guarded keys, so every column may be mass assigned, as Laravel's pivot does.
     *
     * @return an empty list, never {@code null}
     */
    @Override
    protected List<String> guarded() {
        return Collections.emptyList();
    }

    /**
     * Returns the connection the pivot was built with, else the model's own connection name.
     *
     * @return the connection name, or {@code null} for the resolver's default
     */
    @Override
    protected String connectionName() {
        return givenConnection != null ? givenConnection : super.connectionName();
    }

    /**
     * Returns whether timestamps are written: as the pivot was built, else the model's setting.
     *
     * @return whether timestamps are written
     */
    @Override
    protected boolean timestamps() {
        return givenTimestamps != null ? givenTimestamps : super.timestamps();
    }

    /**
     * Returns the join table: the one the pivot was built with, else the class's {@code table()},
     * else its simple class name in snake case, with no plural.
     *
     * @return the table name, never {@code null}
     */
    @Override
    public final String getTable() {
        if (givenTable != null) {
            return givenTable;
        }
        String table = table();
        return table != null ? table : Inflector.snake(getClass().getSimpleName());
    }

    /**
     * Returns the creation timestamp column of the parent, or the model's own when there is no
     * parent.
     *
     * @return the column, or {@code null} for none
     */
    @Override
    public String getCreatedAtColumn() {
        return pivotParent != null ? pivotParent.getCreatedAtColumn() : super.getCreatedAtColumn();
    }

    /**
     * Returns the update timestamp column of the parent, or the model's own when there is no
     * parent.
     *
     * @return the column, or {@code null} for none
     */
    @Override
    public String getUpdatedAtColumn() {
        return pivotParent != null ? pivotParent.getUpdatedAtColumn() : super.getUpdatedAtColumn();
    }

    /**
     * Refuses an update while the pivot has neither a key value nor both pivot key values, then
     * runs the model's own check.
     *
     * @return whether to continue, as the model's {@code updating()} says
     * @throws IllegalStateException if the pivot has no way to select its row; the message names
     *     the class and the table
     */
    @Override
    protected boolean updating() {
        requireWriteKey("update");
        return super.updating();
    }

    /**
     * Refuses a delete while the pivot has neither a key value nor both pivot key values, then
     * runs the model's own check.
     *
     * @return whether to continue, as the model's {@code deleting()} says
     * @throws IllegalStateException if the pivot has no way to select its row; the message names
     *     the class and the table
     */
    @Override
    protected boolean deleting() {
        requireWriteKey("delete");
        return super.deleting();
    }

    /**
     * Selects the pivot's row by its key value, or, when it has none and the pivot keys are set,
     * by both pivot key columns with their original values.
     *
     * @param query the base query on the pivot's table, never {@code null}
     * @return the same query with the key clauses added
     */
    @Override
    protected Builder setKeysForSaveQuery(Builder query) {
        if (getKey() != null || foreignKey == null) {
            return super.setKeysForSaveQuery(query);
        }
        return query.where(foreignKey, "=", originalOrCurrent(foreignKey))
                .where(relatedKey, "=", originalOrCurrent(relatedKey));
    }

    /**
     * Refuses an increment while the pivot has no key value, then runs the model's own increment.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @param extra other columns to set, never {@code null}
     * @return the affected rows, 0 when a hook vetoed it
     * @throws IllegalStateException if the pivot has no key value, or does not exist; the message
     *     names the class and the table
     */
    @Override
    public int increment(String column, Number amount, Map<String, ?> extra) {
        requireKey("increment");
        return super.increment(column, amount, extra);
    }

    /**
     * Refuses a decrement while the pivot has no key value, then runs the model's own decrement.
     *
     * @param column the column, never {@code null}
     * @param amount the amount, never {@code null}
     * @param extra other columns to set, never {@code null}
     * @return the affected rows, 0 when a hook vetoed it
     * @throws IllegalStateException if the pivot has no key value, or does not exist; the message
     *     names the class and the table
     */
    @Override
    public int decrement(String column, Number amount, Map<String, ?> extra) {
        requireKey("decrement");
        return super.decrement(column, amount, extra);
    }

    /**
     * Refuses a reload while the pivot has no key value, then reloads the model.
     *
     * @return this pivot
     * @throws IllegalStateException if the pivot has no key value; the message names the class and
     *     the table
     */
    @Override
    public Model refresh() {
        requireKey("refresh");
        return super.refresh();
    }

    /**
     * Refuses a fresh copy while the pivot has no key value, then loads the copy.
     *
     * <p>The copy has no pivot parent, no pivot keys and no given table. It uses the class's own
     * table, not the one the pivot was built with.
     *
     * @param <M> the pivot's own class, inferred from the assignment
     * @return the new instance, or empty when this pivot does not exist or its row is gone
     * @throws IllegalStateException if the pivot has no key value; the message names the class and
     *     the table
     */
    @Override
    public <M extends Model> Optional<M> fresh() {
        requireKey("fresh");
        return super.fresh();
    }

    /**
     * Builds a pivot for a row read through a parent model. The attributes are set as they are,
     * with no mutator, cast or mass-assignment check, and the original values are synced, so the
     * row starts clean.
     *
     * <p>The pivot takes the parent's connection and is linked to the parent. Its timestamps are
     * written only when the attributes include the parent's creation timestamp column.
     *
     * @param parent the model the row was read through, never {@code null}
     * @param attributes the row's columns, never {@code null}; copied
     * @param table the join table, or {@code null} for the class's own table name
     * @param exists whether the row is in the database
     * @return a new pivot, never {@code null}
     * @throws NullPointerException if {@code parent} or {@code attributes} is {@code null}
     * @throws IllegalStateException if the pivot class has no registry entry and cannot be loaded
     *     into one
     */
    public static Pivot fromAttributes(Model parent, Map<String, ?> attributes, String table,
            boolean exists) {
        Pivot pivot = Access.eloquent().newRegistered(Pivot.class, parent);
        return setUp(pivot, parent, attributes, table, true, exists);
    }

    /**
     * Builds a pivot for a row read through a parent model, with the attributes set raw. It is the
     * same as {@code fromAttributes}, except that the original values are synced only when the row
     * exists, so a row that is not yet stored is dirty.
     *
     * @param parent the model the row was read through, never {@code null}
     * @param attributes the row's columns, never {@code null}; copied
     * @param table the join table, or {@code null} for the class's own table name
     * @param exists whether the row is in the database
     * @return a new pivot, never {@code null}
     * @throws NullPointerException if {@code parent} or {@code attributes} is {@code null}
     * @throws IllegalStateException if the pivot class has no registry entry and cannot be loaded
     *     into one
     */
    public static Pivot fromRawAttributes(Model parent, Map<String, ?> attributes, String table,
            boolean exists) {
        Pivot pivot = Access.eloquent().newRegistered(Pivot.class, parent);
        return fromRawAttributes(pivot, parent, attributes, table, exists);
    }

    /**
     * Sets up a pivot built on the given instance, such as one of a custom class registered for
     * {@code using}. The attributes are set raw, as {@code fromRawAttributes} does.
     *
     * @param <P> the pivot class
     * @param blank the new instance to set up, never {@code null}
     * @param parent the model the row was read through, never {@code null}
     * @param attributes the row's columns, never {@code null}; copied
     * @param table the join table, or {@code null} for the class's own table name
     * @param exists whether the row is in the database
     * @return {@code blank}, set up, never {@code null}
     */
    static <P extends Pivot> P fromRawAttributes(P blank, Model parent, Map<String, ?> attributes,
            String table, boolean exists) {
        return setUp(blank, parent, attributes, table, exists, exists);
    }

    /**
     * Returns the pivot a related model loaded under the name {@code pivot}.
     *
     * @param related the related model, never {@code null}
     * @return the pivot, or {@code null} when none is loaded under that name or it is not a pivot
     * @throws NullPointerException if {@code related} is {@code null}
     */
    public static Pivot of(Model related) {
        return of(related, "pivot");
    }

    /**
     * Returns the pivot a related model loaded under an accessor name, such as the one set by
     * {@code as(...)} on the relation.
     *
     * @param related the related model, never {@code null}
     * @param accessor the name the pivot was loaded under, never {@code null}
     * @return the pivot, or {@code null} when none is loaded under that name or it is not a pivot
     * @throws NullPointerException if {@code related} or {@code accessor} is {@code null}
     */
    public static Pivot of(Model related, String accessor) {
        Objects.requireNonNull(related, "related");
        Objects.requireNonNull(accessor, "accessor");
        if (!related.relationLoaded(accessor)) {
            return null;
        }
        Object loaded = related.getRelation(accessor);
        return loaded instanceof Pivot ? (Pivot) loaded : null;
    }

    /**
     * Returns the parent model the pivot was read through.
     *
     * @return the parent, or {@code null} for a pivot built by hand
     */
    public Model getPivotParent() {
        return pivotParent;
    }

    /**
     * Returns the related model the pivot belongs to.
     *
     * @return the related model, or {@code null} until one is set
     */
    public Model getRelatedModel() {
        return relatedModel;
    }

    /**
     * Returns the pivot column that holds the parent's key. The model's own foreign key is not
     * changed.
     *
     * @return the column, or {@code null} until the keys are set
     */
    public String getForeignPivotKeyName() {
        return foreignKey;
    }

    /**
     * Returns the pivot column that holds the related model's key. The model's own foreign key is
     * not changed.
     *
     * @return the column, or {@code null} until the keys are set
     */
    public String getRelatedPivotKeyName() {
        return relatedKey;
    }

    /**
     * Sets the pivot columns that hold the parent's key and the related model's key.
     *
     * @param foreignKey the column that holds the parent's key, never {@code null}
     * @param relatedKey the column that holds the related model's key, never {@code null}
     * @return this pivot
     * @throws NullPointerException if {@code foreignKey} or {@code relatedKey} is {@code null}
     */
    public Pivot setPivotKeys(String foreignKey, String relatedKey) {
        this.foreignKey = Objects.requireNonNull(foreignKey, "foreignKey");
        this.relatedKey = Objects.requireNonNull(relatedKey, "relatedKey");
        return this;
    }

    /**
     * Sets the related model the pivot belongs to.
     *
     * @param related the related model, never {@code null}
     * @return this pivot
     * @throws NullPointerException if {@code related} is {@code null}
     */
    public Pivot setRelatedModel(Model related) {
        this.relatedModel = Objects.requireNonNull(related, "related");
        return this;
    }

    /**
     * Points the pivot at the parent model it was read through. The hydrator calls this when an
     * eager load links the pivot to the parent that owns the relation.
     *
     * @param parent the parent model, never {@code null}
     */
    final void setPivotParent(Model parent) {
        this.pivotParent = Objects.requireNonNull(parent, "parent");
    }

    private static <P extends Pivot> P setUp(P pivot, Model parent, Map<String, ?> attributes,
            String table, boolean sync, boolean exists) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(attributes, "attributes");
        // Private fields need a Pivot-typed reference; javac rejects them through the type variable.
        Pivot target = pivot;
        String createdAt = parent.getCreatedAtColumn();
        target.givenTimestamps = createdAt != null && attributes.containsKey(createdAt);
        target.givenConnection = parent.getConnectionName();
        target.givenTable = table;
        target.setRawAttributes(attributes, sync);
        target.pivotParent = parent;
        if (exists) {
            Access.eloquent().markExisting(target);
        }
        return pivot;
    }

    private Object originalOrCurrent(String key) {
        Object original = getRawOriginal(key);
        return original != null ? original : getAttribute(key);
    }

    private boolean hasPivotKeyValues() {
        return foreignKey != null && relatedKey != null
                && originalOrCurrent(foreignKey) != null && originalOrCurrent(relatedKey) != null;
    }

    // Update and delete can fall back to the pivot keys; the other writes cannot.
    private void requireWriteKey(String operation) {
        if (getKey() == null && !hasPivotKeyValues()) {
            requireKey(operation);
        }
    }

    // Checked before the model's own method, so a refused write never changes the attributes.
    private void requireKey(String operation) {
        if (getKey() == null) {
            throw new IllegalStateException("Cannot " + operation + " a pivot of class ["
                    + getClass().getName() + "] on table [" + getTable() + "] with no key value");
        }
    }
}
