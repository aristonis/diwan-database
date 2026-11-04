package org.aristonis.diwan.database.eloquent;

import java.util.Objects;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.HasManyThrough;
import org.aristonis.diwan.database.eloquent.relations.HasOneThrough;

/**
 * The through layer of {@link Model}: the protected {@code hasManyThrough} and
 * {@code hasOneThrough} builders that a model's relation methods call.
 *
 * <p>A through relation reaches the related model through an intermediate model. The first key is
 * the through table's column that holds this model's key, and the second key is the related
 * table's column that holds the through model's key. The local key is this model's column the first
 * key points at, and the second local key is the through table's column the second key points at.
 *
 * <p>A shorter overload fills in the omitted keys with Laravel's defaults: the first key is this
 * model's {@code getForeignKey()}, such as {@code country_id}; the second key is the through model's
 * {@code getForeignKey()}, such as {@code user_id}; the local key is this model's
 * {@code getKeyName()}; and the second local key is the through model's {@code getKeyName()}.
 *
 * <p>The through model is built once per call on this model's connection, so the defaults and the
 * relation read the same instance. A {@code null} key is refused and never takes a default.
 *
 * <p>The related table and the through table must be different tables. The builders do not check
 * this; the relation refuses it when it is first queried.
 */
abstract class ModelThroughRelations extends ModelManyRelations {

    ModelThroughRelations() {
    }

    /**
     * Builds a has-many-through relation with Laravel's default keys.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}; it must have a
     *     {@link ModelQuery} entry
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code through} is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasManyThrough<R> hasManyThrough(Class<R> related,
            Class<? extends Model> through) {
        Model throughModel = newThroughModel(through);
        return newHasManyThrough(related, throughModel, self().getForeignKey(),
                throughModel.getForeignKey(), self().getKeyName(), throughModel.getKeyName());
    }

    /**
     * Builds a has-many-through relation with the first key given and the other keys defaulted.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}
     * @param firstKey the through table's column holding this model's key, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasManyThrough<R> hasManyThrough(Class<R> related,
            Class<? extends Model> through, String firstKey) {
        Model throughModel = newThroughModel(through);
        return newHasManyThrough(related, throughModel, firstKey, throughModel.getForeignKey(),
                self().getKeyName(), throughModel.getKeyName());
    }

    /**
     * Builds a has-many-through relation with the first and second keys given and the local keys
     * defaulted.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}
     * @param firstKey the through table's column holding this model's key, never {@code null}
     * @param secondKey the related table's column holding the through model's key, never
     *     {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasManyThrough<R> hasManyThrough(Class<R> related,
            Class<? extends Model> through, String firstKey, String secondKey) {
        Model throughModel = newThroughModel(through);
        return newHasManyThrough(related, throughModel, firstKey, secondKey, self().getKeyName(),
                throughModel.getKeyName());
    }

    /**
     * Builds a has-many-through relation with the first, second and local keys given and the second
     * local key defaulted.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}
     * @param firstKey the through table's column holding this model's key, never {@code null}
     * @param secondKey the related table's column holding the through model's key, never
     *     {@code null}
     * @param localKey this model's column the first key points at, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasManyThrough<R> hasManyThrough(Class<R> related,
            Class<? extends Model> through, String firstKey, String secondKey, String localKey) {
        Model throughModel = newThroughModel(through);
        return newHasManyThrough(related, throughModel, firstKey, secondKey, localKey,
                throughModel.getKeyName());
    }

    /**
     * Builds a has-many-through relation with every key given.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}
     * @param firstKey the through table's column holding this model's key, never {@code null}
     * @param secondKey the related table's column holding the through model's key, never
     *     {@code null}
     * @param localKey this model's column the first key points at, never {@code null}
     * @param secondLocalKey the through table's column the second key points at, never
     *     {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasManyThrough<R> hasManyThrough(Class<R> related,
            Class<? extends Model> through, String firstKey, String secondKey, String localKey,
            String secondLocalKey) {
        return newHasManyThrough(related, newThroughModel(through), firstKey, secondKey, localKey,
                secondLocalKey);
    }

    /**
     * Builds a has-one-through relation with Laravel's default keys.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}; it must have a
     *     {@link ModelQuery} entry
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if {@code related} or {@code through} is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasOneThrough<R> hasOneThrough(Class<R> related,
            Class<? extends Model> through) {
        Model throughModel = newThroughModel(through);
        return newHasOneThrough(related, throughModel, self().getForeignKey(),
                throughModel.getForeignKey(), self().getKeyName(), throughModel.getKeyName());
    }

    /**
     * Builds a has-one-through relation with the first key given and the other keys defaulted.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}
     * @param firstKey the through table's column holding this model's key, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasOneThrough<R> hasOneThrough(Class<R> related,
            Class<? extends Model> through, String firstKey) {
        Model throughModel = newThroughModel(through);
        return newHasOneThrough(related, throughModel, firstKey, throughModel.getForeignKey(),
                self().getKeyName(), throughModel.getKeyName());
    }

    /**
     * Builds a has-one-through relation with the first and second keys given and the local keys
     * defaulted.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}
     * @param firstKey the through table's column holding this model's key, never {@code null}
     * @param secondKey the related table's column holding the through model's key, never
     *     {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasOneThrough<R> hasOneThrough(Class<R> related,
            Class<? extends Model> through, String firstKey, String secondKey) {
        Model throughModel = newThroughModel(through);
        return newHasOneThrough(related, throughModel, firstKey, secondKey, self().getKeyName(),
                throughModel.getKeyName());
    }

    /**
     * Builds a has-one-through relation with the first, second and local keys given and the second
     * local key defaulted.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}
     * @param firstKey the through table's column holding this model's key, never {@code null}
     * @param secondKey the related table's column holding the through model's key, never
     *     {@code null}
     * @param localKey this model's column the first key points at, never {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasOneThrough<R> hasOneThrough(Class<R> related,
            Class<? extends Model> through, String firstKey, String secondKey, String localKey) {
        Model throughModel = newThroughModel(through);
        return newHasOneThrough(related, throughModel, firstKey, secondKey, localKey,
                throughModel.getKeyName());
    }

    /**
     * Builds a has-one-through relation with every key given.
     *
     * @param <R> the related model class
     * @param related the related model class, never {@code null}
     * @param through the intermediate model class, never {@code null}
     * @param firstKey the through table's column holding this model's key, never {@code null}
     * @param secondKey the related table's column holding the through model's key, never
     *     {@code null}
     * @param localKey this model's column the first key points at, never {@code null}
     * @param secondLocalKey the through table's column the second key points at, never
     *     {@code null}
     * @return a new, unconstrained relation, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalStateException if a class has no {@link ModelQuery} entry; the message names
     *     the class
     */
    protected <R extends Model> HasOneThrough<R> hasOneThrough(Class<R> related,
            Class<? extends Model> through, String firstKey, String secondKey, String localKey,
            String secondLocalKey) {
        return newHasOneThrough(related, newThroughModel(through), firstKey, secondKey, localKey,
                secondLocalKey);
    }

    /**
     * Builds the through model that the relation reads its defaults from and passes on, on this
     * model's connection.
     */
    private Model newThroughModel(Class<? extends Model> through) {
        Objects.requireNonNull(through, "through");
        return Access.eloquent().newRegistered(through, self());
    }

    private <R extends Model> HasManyThrough<R> newHasManyThrough(Class<R> related, Model throughModel,
            String firstKey, String secondKey, String localKey, String secondLocalKey) {
        requireKeys(firstKey, secondKey, localKey, secondLocalKey);
        return new HasManyThrough<>(newRelatedQuery(related), self(), throughModel, firstKey, secondKey,
                localKey, secondLocalKey);
    }

    private <R extends Model> HasOneThrough<R> newHasOneThrough(Class<R> related, Model throughModel,
            String firstKey, String secondKey, String localKey, String secondLocalKey) {
        requireKeys(firstKey, secondKey, localKey, secondLocalKey);
        return new HasOneThrough<>(newRelatedQuery(related), self(), throughModel, firstKey, secondKey,
                localKey, secondLocalKey);
    }

    private static void requireKeys(String firstKey, String secondKey, String localKey,
            String secondLocalKey) {
        Objects.requireNonNull(firstKey, "firstKey");
        Objects.requireNonNull(secondKey, "secondKey");
        Objects.requireNonNull(localKey, "localKey");
        Objects.requireNonNull(secondLocalKey, "secondLocalKey");
    }
}
