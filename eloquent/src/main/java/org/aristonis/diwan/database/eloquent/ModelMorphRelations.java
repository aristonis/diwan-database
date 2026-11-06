package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.MorphMany;
import org.aristonis.diwan.database.eloquent.relations.MorphMap;
import org.aristonis.diwan.database.eloquent.relations.MorphOne;
import org.aristonis.diwan.database.eloquent.relations.MorphToMany;
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
 * <p>{@code morphToMany} and {@code morphedByMany} share one pivot table with a type column
 * {@code name_type} and an id column {@code name_id}, such as {@code taggables}. Their optional
 * arguments take the default when {@code null}.
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

    /**
     * Eager loads relations onto the owner this model's morph-to relation has loaded, choosing the
     * relations by the owner's class: Laravel's {@code loadMorph}. A relation that is not loaded is
     * loaded first, as Laravel's attribute read lazy loads it. A {@code null} owner, or an owner
     * class with no entry, loads nothing more.
     *
     * @param relation the morph-to relation's name, never {@code null}
     * @param relations the relation paths to load, by owner class, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument, a class key or a path is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a path
     *     names no relation of the owner's class
     */
    public Model loadMorph(String relation, Map<Class<? extends Model>, List<String>> relations) {
        Relation.ensureInitialised();
        Access.relations().loadMorph(Collections.singletonList(self()), relation, relations, false);
        return self();
    }

    /**
     * Loads the number of related rows of each relation onto the owner this model's morph-to
     * relation has loaded, choosing the relations by the owner's class: Laravel's
     * {@code loadMorphCount}. A relation that is not loaded is loaded first. A {@code null} owner,
     * or an owner class with no entry, counts nothing.
     *
     * @param relation the morph-to relation's name, never {@code null}
     * @param relations the relation names to count, by owner class, never {@code null}
     * @return this model
     * @throws NullPointerException if an argument, a class key or a name is {@code null}
     * @throws org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException if a name
     *     is not a relation of the owner's class
     */
    public Model loadMorphCount(String relation, Map<Class<? extends Model>, List<String>> relations) {
        Relation.ensureInitialised();
        Access.relations().loadMorph(Collections.singletonList(self()), relation, relations, true);
        return self();
    }

    /**
     * Builds a polymorphic many-to-many relation: the pivot's type column holds this model's morph
     * class and its id column holds this model's key. The defaults are the plural of the last word of the name
     * for the table, {@code name_id} for the foreign pivot key, and the related model's
     * {@code getForeignKey()} for the related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphToMany(Class<R> related, String name) {
        return buildMorphToMany(related, name, null, null, null, null, null, null, false);
    }

    /**
     * Builds a polymorphic many-to-many relation: the pivot's type column holds this model's morph
     * class and its id column holds this model's key. The defaults are the plural of the last word of the name
     * for the table, {@code name_id} for the foreign pivot key, and the related model's
     * {@code getForeignKey()} for the related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @param table the pivot table, or {@code null} or empty for the plural of the last word of the name
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphToMany(Class<R> related, String name, String table) {
        return buildMorphToMany(related, name, table, null, null, null, null, null, false);
    }

    /**
     * Builds a polymorphic many-to-many relation: the pivot's type column holds this model's morph
     * class and its id column holds this model's key. The defaults are the plural of the last word of the name
     * for the table, {@code name_id} for the foreign pivot key, and the related model's
     * {@code getForeignKey()} for the related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @param table the pivot table, or {@code null} or empty for the plural of the last word of the name
     * @param foreignPivotKey the pivot column holding the parent's key, or
     *     {@code null} for {@code name_id}
     * @param relatedPivotKey the pivot column holding the related key, or {@code null} for the related
     *     model's {@code getForeignKey()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphToMany(Class<R> related, String name,
            String table, String foreignPivotKey, String relatedPivotKey) {
        return buildMorphToMany(related, name, table, foreignPivotKey, relatedPivotKey, null, null, null, false);
    }

    /**
     * Builds a polymorphic many-to-many relation: the pivot's type column holds this model's morph
     * class and its id column holds this model's key. The defaults are the plural of the last word of the name
     * for the table, {@code name_id} for the foreign pivot key, and the related model's
     * {@code getForeignKey()} for the related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @param table the pivot table, or {@code null} or empty for the plural of the last word of the name
     * @param foreignPivotKey the pivot column holding the parent's key, or
     *     {@code null} for {@code name_id}
     * @param relatedPivotKey the pivot column holding the related key, or {@code null} for the related
     *     model's {@code getForeignKey()}
     * @param parentKey the parent's column the pivot points at, or {@code null} for this model's
     *     {@code getKeyName()}
     * @param relatedKey the related model's column the pivot points at, or {@code null} for the
     *     related model's {@code getKeyName()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphToMany(Class<R> related, String name,
            String table, String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey) {
        return buildMorphToMany(related, name, table, foreignPivotKey, relatedPivotKey, parentKey,
                relatedKey, null, false);
    }

    /**
     * Builds a polymorphic many-to-many relation: the pivot's type column holds this model's morph
     * class and its id column holds this model's key. The defaults are the plural of the last word of the name
     * for the table, {@code name_id} for the foreign pivot key, and the related model's
     * {@code getForeignKey()} for the related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @param table the pivot table, or {@code null} or empty for the plural of the last word of the name
     * @param foreignPivotKey the pivot column holding the parent's key, or
     *     {@code null} for {@code name_id}
     * @param relatedPivotKey the pivot column holding the related key, or {@code null} for the related
     *     model's {@code getForeignKey()}
     * @param parentKey the parent's column the pivot points at, or {@code null} for this model's
     *     {@code getKeyName()}
     * @param relatedKey the related model's column the pivot points at, or {@code null} for the
     *     related model's {@code getKeyName()}
     * @param relation the relation name, or {@code null} to take it from {@link #relations()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphToMany(Class<R> related, String name,
            String table, String foreignPivotKey, String relatedPivotKey, String parentKey,
            String relatedKey, String relation) {
        return buildMorphToMany(related, name, table, foreignPivotKey, relatedPivotKey, parentKey,
                relatedKey, relation, false);
    }

    /**
     * Builds the inverse side of a polymorphic many-to-many relation: this model is the related side
     * of the pivot, such as a tag, and the pivot's type column holds the class of the models it is
     * attached to. The defaults are the plural of the last word of the name for the table,
     * this model's {@code getForeignKey()} for the foreign pivot key, and {@code name_id} for the
     * related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphedByMany(Class<R> related, String name) {
        return buildMorphToMany(related, name, null, null, null, null, null, null, true);
    }

    /**
     * Builds the inverse side of a polymorphic many-to-many relation: this model is the related side
     * of the pivot, such as a tag, and the pivot's type column holds the class of the models it is
     * attached to. The defaults are the plural of the last word of the name for the table,
     * this model's {@code getForeignKey()} for the foreign pivot key, and {@code name_id} for the
     * related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @param table the pivot table, or {@code null} or empty for the plural of the last word of the name
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphedByMany(Class<R> related, String name, String table) {
        return buildMorphToMany(related, name, table, null, null, null, null, null, true);
    }

    /**
     * Builds the inverse side of a polymorphic many-to-many relation: this model is the related side
     * of the pivot, such as a tag, and the pivot's type column holds the class of the models it is
     * attached to. The defaults are the plural of the last word of the name for the table,
     * this model's {@code getForeignKey()} for the foreign pivot key, and {@code name_id} for the
     * related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @param table the pivot table, or {@code null} or empty for the plural of the last word of the name
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related key, or
     *     {@code null} for {@code name_id}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphedByMany(Class<R> related, String name,
            String table, String foreignPivotKey, String relatedPivotKey) {
        return buildMorphToMany(related, name, table, foreignPivotKey, relatedPivotKey, null, null, null, true);
    }

    /**
     * Builds the inverse side of a polymorphic many-to-many relation: this model is the related side
     * of the pivot, such as a tag, and the pivot's type column holds the class of the models it is
     * attached to. The defaults are the plural of the last word of the name for the table,
     * this model's {@code getForeignKey()} for the foreign pivot key, and {@code name_id} for the
     * related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @param table the pivot table, or {@code null} or empty for the plural of the last word of the name
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related key, or
     *     {@code null} for {@code name_id}
     * @param parentKey the parent's column the pivot points at, or {@code null} for this model's
     *     {@code getKeyName()}
     * @param relatedKey the related model's column the pivot points at, or {@code null} for the
     *     related model's {@code getKeyName()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphedByMany(Class<R> related, String name,
            String table, String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey) {
        return buildMorphToMany(related, name, table, foreignPivotKey, relatedPivotKey, parentKey,
                relatedKey, null, true);
    }

    /**
     * Builds the inverse side of a polymorphic many-to-many relation: this model is the related side
     * of the pivot, such as a tag, and the pivot's type column holds the class of the models it is
     * attached to. The defaults are the plural of the last word of the name for the table,
     * this model's {@code getForeignKey()} for the foreign pivot key, and {@code name_id} for the
     * related pivot key.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param name the morph name, such as {@code taggable}, never {@code null} or empty
     * @param table the pivot table, or {@code null} or empty for the plural of the last word of the name
     * @param foreignPivotKey the pivot column holding the parent's key, or {@code null} for the
     *     parent's {@code getForeignKey()}
     * @param relatedPivotKey the pivot column holding the related key, or
     *     {@code null} for {@code name_id}
     * @param parentKey the parent's column the pivot points at, or {@code null} for this model's
     *     {@code getKeyName()}
     * @param relatedKey the related model's column the pivot points at, or {@code null} for the
     *     related model's {@code getKeyName()}
     * @param relation the relation name, or {@code null} to take it from {@link #relations()}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} is {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or empty
     * @throws IllegalStateException if the related class has no {@link ModelQuery} entry
     */
    protected <R extends Model> MorphToMany<R> morphedByMany(Class<R> related, String name,
            String table, String foreignPivotKey, String relatedPivotKey, String parentKey,
            String relatedKey, String relation) {
        return buildMorphToMany(related, name, table, foreignPivotKey, relatedPivotKey, parentKey,
                relatedKey, relation, true);
    }

    private <R extends Model> MorphToMany<R> buildMorphToMany(Class<R> related, String name, String table,
            String foreignPivotKey, String relatedPivotKey, String parentKey, String relatedKey,
            String relation, boolean isInverse) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(
                    "The morph name of a morph-to-many relation may not be null or empty");
        }
        Relation.ensureInitialised();
        EloquentBuilder<R> query = newRelatedQuery(related);
        Model instance = query.getModel();
        Model parent = self();
        String ownKey = isInverse ? parent.getForeignKey() : name + ID_SUFFIX;
        String otherKey = isInverse ? name + ID_SUFFIX : instance.getForeignKey();
        return Access.relations().newMorphToMany(query, parent, name, table,
                foreignPivotKey != null ? foreignPivotKey : ownKey,
                relatedPivotKey != null ? relatedPivotKey : otherKey,
                parentKey != null ? parentKey : parent.getKeyName(),
                relatedKey != null ? relatedKey : instance.getKeyName(), relation, isInverse);
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
