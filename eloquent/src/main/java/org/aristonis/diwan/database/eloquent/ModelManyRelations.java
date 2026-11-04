package org.aristonis.diwan.database.eloquent;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.BelongsToMany;
import org.aristonis.diwan.database.eloquent.relations.Pivot;
import org.aristonis.diwan.database.eloquent.relations.Relation;
import org.aristonis.diwan.database.support.Inflector;

/**
 * The many-to-many layer of {@link Model}: the protected {@code belongsToMany} builders that a
 * model's relation methods call.
 *
 * <p>A null argument takes Laravel's default. The pivot table is the joining table of the two
 * model names: their snake-cased simple names, sorted and joined with an underscore, so {@code User}
 * and {@code Role} give {@code role_user}. The foreign pivot key is the parent's
 * {@code getForeignKey()}, such as {@code user_id}; the related pivot key is the related model's
 * {@code getForeignKey()}; the parent key and the related key are the two models'
 * {@code getKeyName()}.
 *
 * <p>An overload that takes a {@link Class} as its table reads the table from that class's registry
 * entry, and sets the relation's pivot class when the class is a {@link Pivot} subclass.
 *
 * <p>On Android, R8 may rename simple class names, so the joining-table default is not stable there:
 * pass the table explicitly.
 */
abstract class ModelManyRelations extends ModelRelations {

    ModelManyRelations() {
    }

    /**
     * Builds a many-to-many relation to the related model, with Laravel's default table and keys.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}; it must have a {@link ModelQuery}
     *     entry
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry; the message
     *     names the class
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related) {
        return belongsToMany(related, (String) null);
    }

    /**
     * Builds a many-to-many relation to the related model through the given pivot table.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the pivot table name, or {@code null} for the joining table of the two models
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, String table) {
        return belongsToMany(related, table, null, null, null, null, null);
    }

    /**
     * Builds a many-to-many relation to the related model through the given pivot table and parent
     * pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the pivot table name, or {@code null} for the joining table of the two models
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}, such as {@code user_id}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, String table,
            String foreignPivotKey) {
        return belongsToMany(related, table, foreignPivotKey, null, null, null, null);
    }

    /**
     * Builds a many-to-many relation to the related model through the given pivot table and pivot
     * keys.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the pivot table name, or {@code null} for the joining table of the two models
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related model's key, or {@code null} for
     *     the related model's {@code getForeignKey()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, String table,
            String foreignPivotKey, String relatedPivotKey) {
        return belongsToMany(related, table, foreignPivotKey, relatedPivotKey, null, null, null);
    }

    /**
     * Builds a many-to-many relation to the related model through the given pivot table, pivot
     * keys and model keys.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the pivot table name, or {@code null} for the joining table of the two models
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related model's key, or {@code null} for
     *     the related model's {@code getForeignKey()}
     * @param parentKey the parent's column the pivot points at, or {@code null} for the parent's
     *     {@code getKeyName()}
     * @param relatedKey the related model's column the pivot points at, or {@code null} for the
     *     related model's {@code getKeyName()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, String table,
            String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey) {
        return belongsToMany(related, table, foreignPivotKey, relatedPivotKey, parentKey, relatedKey, null);
    }

    /**
     * Builds a many-to-many relation to the related model, under an explicit relation name.
     *
     * <p>A {@code null} name is filled in by the model's {@link #relations()} map when the relation
     * is read through it; a name given here is kept as it is.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the pivot table name, or {@code null} for the joining table of the two models
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related model's key, or {@code null} for
     *     the related model's {@code getForeignKey()}
     * @param parentKey the parent's column the pivot points at, or {@code null} for the parent's
     *     {@code getKeyName()}
     * @param relatedKey the related model's column the pivot points at, or {@code null} for the
     *     related model's {@code getKeyName()}
     * @param relation the relation name, or {@code null} to take it from {@link #relations()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, String table,
            String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey, String relation) {
        Relation.ensureInitialised();
        EloquentBuilder<R> query = newRelatedQuery(related);
        Model instance = query.getModel();
        Model parent = self();
        String pivotTable = table != null ? table : joiningTable(instance, parent);
        String fpk = foreignPivotKey != null ? foreignPivotKey : parent.getForeignKey();
        String rpk = relatedPivotKey != null ? relatedPivotKey : instance.getForeignKey();
        String pk = parentKey != null ? parentKey : parent.getKeyName();
        String rk = relatedKey != null ? relatedKey : instance.getKeyName();
        return Access.relations().newBelongsToMany(query, parent, pivotTable, fpk, rpk, pk, rk, relation);
    }

    /**
     * Builds a many-to-many relation to the related model, with the pivot table read from a model
     * class.
     *
     * <p>A call such as {@code belongsToMany(Role.class, null)} does not compile, because the
     * {@code null} matches both the {@link String} and the {@link Class} table overloads. Pass
     * {@code (String) null} for the joining table, or use the one-argument form.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the model class whose table is the pivot table, never {@code null}; when it is a
     *     {@link Pivot} subclass, the relation builds its pivots as that class
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code table} is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names the
     *     class
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, Class<? extends Model> table) {
        return belongsToMany(related, table, null, null, null, null, null);
    }

    /**
     * Builds a many-to-many relation to the related model, with the pivot table read from a model
     * class and the parent pivot key given.
     *
     * <p>A {@code null} table must be written {@code (String) null} to pick the {@link String} overload,
     * because a bare {@code null} matches this overload and that one alike and does not compile.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the model class whose table is the pivot table, never {@code null}; a {@link Pivot}
     *     subclass also sets the pivot class
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code table} is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names the
     *     class
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, Class<? extends Model> table,
            String foreignPivotKey) {
        return belongsToMany(related, table, foreignPivotKey, null, null, null, null);
    }

    /**
     * Builds a many-to-many relation to the related model, with the pivot table read from a model
     * class and both pivot keys given.
     *
     * <p>A {@code null} table must be written {@code (String) null} to pick the {@link String} overload,
     * because a bare {@code null} matches this overload and that one alike and does not compile.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the model class whose table is the pivot table, never {@code null}; a {@link Pivot}
     *     subclass also sets the pivot class
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related model's key, or {@code null} for
     *     the related model's {@code getForeignKey()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code table} is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names the
     *     class
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, Class<? extends Model> table,
            String foreignPivotKey, String relatedPivotKey) {
        return belongsToMany(related, table, foreignPivotKey, relatedPivotKey, null, null, null);
    }

    /**
     * Builds a many-to-many relation to the related model, with the pivot table read from a model
     * class, the pivot keys and the model keys given.
     *
     * <p>A {@code null} table must be written {@code (String) null} to pick the {@link String} overload,
     * because a bare {@code null} matches this overload and that one alike and does not compile.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the model class whose table is the pivot table, never {@code null}; a {@link Pivot}
     *     subclass also sets the pivot class
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related model's key, or {@code null} for
     *     the related model's {@code getForeignKey()}
     * @param parentKey the parent's column the pivot points at, or {@code null} for the parent's
     *     {@code getKeyName()}
     * @param relatedKey the related model's column the pivot points at, or {@code null} for the
     *     related model's {@code getKeyName()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code table} is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names the
     *     class
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, Class<? extends Model> table,
            String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey) {
        return belongsToMany(related, table, foreignPivotKey, relatedPivotKey, parentKey, relatedKey, null);
    }

    /**
     * Builds a many-to-many relation to the related model, with the pivot table read from a model
     * class and every other argument given, under an explicit relation name.
     *
     * <p>The table class is looked up in the registry and read with {@code getTable()}, so its
     * table override or default table applies. A {@link Pivot} subclass also sets the pivot class,
     * which the relation builds its pivots as.
     *
     * <p>A {@code null} table must be written {@code (String) null} to pick the {@link String} overload,
     * because a bare {@code null} matches this overload and that one alike and does not compile.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param table the model class whose table is the pivot table, never {@code null}
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related model's key, or {@code null} for
     *     the related model's {@code getForeignKey()}
     * @param parentKey the parent's column the pivot points at, or {@code null} for the parent's
     *     {@code getKeyName()}
     * @param relatedKey the related model's column the pivot points at, or {@code null} for the
     *     related model's {@code getKeyName()}
     * @param relation the relation name, or {@code null} to take it from {@link #relations()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code table} is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names the
     *     class
     */
    protected <R extends Model> BelongsToMany<R> belongsToMany(Class<R> related, Class<? extends Model> table,
            String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey, String relation) {
        Objects.requireNonNull(table, "table");
        Model sample = Access.eloquent().newRegistered(table, null);
        BelongsToMany<R> built = belongsToMany(related, sample.getTable(), foreignPivotKey, relatedPivotKey,
                parentKey, relatedKey, relation);
        if (Pivot.class.isAssignableFrom(table)) {
            return built.using(table.asSubclass(Pivot.class));
        }
        return built;
    }

    /**
     * Returns the default pivot table of two models: their snake-cased simple names, sorted and
     * joined with an underscore.
     *
     * <p>Sorting is the plain string order, the order Laravel's PHP sort gives these ASCII names, so
     * a relation declared on either side names the same table.
     */
    private static String joiningTable(Model related, Model parent) {
        String[] segments = {Inflector.snake(related.getClass().getSimpleName()),
                Inflector.snake(parent.getClass().getSimpleName())};
        Arrays.sort(segments);
        return (segments[0] + "_" + segments[1]).toLowerCase(Locale.ROOT);
    }
}
