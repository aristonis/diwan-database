package org.aristonis.diwan.database.eloquent;

import java.util.Objects;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.MorphMany;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphOne;
import org.aristonis.diwan.database.eloquent.relations.MorphTo;
import org.aristonis.diwan.database.eloquent.relations.Relation;

/**
 * The polymorphic layer of {@link Model}: {@code getMorphClass} and the protected {@code morphOne},
 * {@code morphMany} and {@code morphTo} builders that a model's relation methods call.
 *
 * <p>A morph relation stores two columns on the child table: a type column holding the owner's
 * morph class (its {@link MorphMap} alias, or its class name when the map is not required) and an
 * id column holding the owner's key. For the name {@code commentable} the defaults are
 * {@code commentable_type} and {@code commentable_id}.
 *
 * <p>{@code morphOne} and {@code morphMany} sit on the owner side; the local key defaults to this
 * model's {@code getKeyName()}. {@code morphTo} sits on the child side; the owner class is read
 * from the type attribute when the relation is built, and the owner key defaults to the resolved
 * model's key.
 *
 * <p>A {@code null} column or key is refused and never takes a default, except the owner key of
 * {@code morphTo}, where {@code null} means the owner's key.
 */
abstract class ModelMorphRelations extends ModelThroughRelations {

    private static final String TYPE_SUFFIX = "_type";

    private static final String ID_SUFFIX = "_id";

    ModelMorphRelations() {
    }

    /**
     * Returns the type string this model writes into a morph type column: its {@link MorphMap}
     * alias, or its class name when the map is not required and has no alias for it.
     *
     * <p>A model may override it to choose its own string.
     *
     * @return the alias or the class name, never {@code null}
     * @throws ClassMorphViolationException if the class has no alias and the map is required; the
     *     message names the class
     */
    public String getMorphClass() {
        Relation.ensureInitialised();
        return Access.relations().aliasOf(self().getClass());
    }

    /**
     * Builds a one-to-one polymorphic relation with the default columns {@code name_type} and
     * {@code name_id} and the local key {@code getKeyName()}.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphOne<R> morphOne(Class<R> related, String name) {
        Objects.requireNonNull(name, "name");
        return morphOne(related, name, name + TYPE_SUFFIX, name + ID_SUFFIX, getKeyName());
    }

    /**
     * Builds a one-to-one polymorphic relation.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, never {@code null}
     * @param type the related table's type column, bare or qualified, never {@code null}
     * @param id the related table's id column, bare or qualified, never {@code null}
     * @param localKey this model's column the id column points at, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphOne<R> morphOne(Class<R> related, String name, String type, String id,
            String localKey) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(localKey, "localKey");
        EloquentBuilder<R> query = newRelatedQuery(related);
        Model model = query.getModel();
        return new MorphOne<>(query, self(), model.qualifyColumn(type), model.qualifyColumn(id), localKey);
    }

    /**
     * Builds a one-to-many polymorphic relation with the default columns {@code name_type} and
     * {@code name_id} and the local key {@code getKeyName()}.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphMany<R> morphMany(Class<R> related, String name) {
        Objects.requireNonNull(name, "name");
        return morphMany(related, name, name + TYPE_SUFFIX, name + ID_SUFFIX, getKeyName());
    }

    /**
     * Builds a one-to-many polymorphic relation.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, never {@code null}
     * @param type the related table's type column, bare or qualified, never {@code null}
     * @param id the related table's id column, bare or qualified, never {@code null}
     * @param localKey this model's column the id column points at, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphMany<R> morphMany(Class<R> related, String name, String type, String id,
            String localKey) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(localKey, "localKey");
        EloquentBuilder<R> query = newRelatedQuery(related);
        Model model = query.getModel();
        return new MorphMany<>(query, self(), model.qualifyColumn(type), model.qualifyColumn(id), localKey);
    }

    /**
     * Builds the child side of a polymorphic relation with the default columns {@code name_type}
     * and {@code name_id} and the owner's key as owner key.
     *
     * @param name the relation name, also the morph name, never {@code null}
     * @return a new relation, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws ClassMorphViolationException if the type attribute holds a string the morph map
     *     cannot resolve
     * @throws IllegalStateException if the resolved class has no {@link ModelQuery} entry
     */
    protected MorphTo<Model> morphTo(String name) {
        Objects.requireNonNull(name, "name");
        return morphTo(name, name + TYPE_SUFFIX, name + ID_SUFFIX, null);
    }

    /**
     * Builds the child side of a polymorphic relation.
     *
     * <p>When the type attribute is {@code null} or empty the relation is built over a placeholder
     * query on this model's own class and runs no query for a single model.
     *
     * @param name the relation name, never {@code null}
     * @param type this table's bare type column, never {@code null}
     * @param id this table's bare id column, never {@code null}
     * @param ownerKey the owner's column the id column points at, or {@code null} for the owner's
     *     key
     * @return a new relation, never {@code null}
     * @throws NullPointerException if {@code name}, {@code type} or {@code id} is {@code null}
     * @throws ClassMorphViolationException if the type attribute holds a string the morph map
     *     cannot resolve
     * @throws IllegalStateException if the resolved class has no {@link ModelQuery} entry
     */
    protected MorphTo<Model> morphTo(String name, String type, String id, String ownerKey) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Model child = self();
        Object stored = child.getAttribute(type);
        String typeName = stored == null ? "" : String.valueOf(stored);
        Class<? extends Model> target = typeName.isEmpty() ? child.getClass()
                : resolveMorph(typeName, child);
        return new MorphTo<>(modelQuery(target), child, type, id, ownerKey, name);
    }

    private static Class<? extends Model> resolveMorph(String typeName, Model child) {
        Relation.ensureInitialised();
        return Access.relations().resolveMorph(typeName, child.getClass().getClassLoader());
    }

    private <R extends Model> EloquentBuilder<Model> modelQuery(Class<R> target) {
        EloquentBuilder<R> query = newRelatedQuery(target);
        // Safe: the query is only read through Model, never given a model of another class.
        @SuppressWarnings("unchecked")
        EloquentBuilder<Model> widened = (EloquentBuilder<Model>) (EloquentBuilder<?>) query;
        return widened;
    }
}
