package org.aristonis.diwan.database.eloquent.exception;

import org.aristonis.diwan.database.Internal;
import java.util.Objects;

/**
 * Signals that a model class's {@code relations()} map failed its one-time check.
 *
 * <p>The check runs the first time a relation of the class is resolved by name. It fails when the
 * map was built for a class the model does not extend, a name is empty or holds {@code .} or
 * {@code :}, a factory returns {@code null}, or a relation's related class has no
 * {@code ModelQuery} entry. The failure is kept: every later use of the class's relations throws
 * a new exception with the same message and the first one as its cause, so every thread sees the
 * same error and the factories do not run again.
 *
 * <p>The message names the model class, the relation and the reason; it carries no attribute
 * value. Instances are immutable after construction.
 */
public final class InvalidRelationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The model class. */
    private final transient Class<?> modelClass;

    /** The relation name, or null when the fault is in the map as a whole. */
    private final String relation;

    /** Why the check failed. */
    private final String reason;

    /**
     * Creates an exception.
     *
     * @param modelClass the model class, never {@code null}
     * @param relation the relation name, or {@code null} when the fault is in the map as a whole
     * @param reason why the check failed, never {@code null}; must not hold attribute values
     * @throws NullPointerException if {@code modelClass} or {@code reason} is {@code null}
     */
    public InvalidRelationException(Class<?> modelClass, String relation, String reason) {
        this(modelClass, relation, reason, null);
    }

    /**
     * Creates an exception that repeats an earlier one, with the earlier one as its cause.
     *
     * @param original the first failure, never {@code null}
     * @throws NullPointerException if {@code original} is {@code null}
     */
    public InvalidRelationException(InvalidRelationException original) {
        this(Objects.requireNonNull(original, "original").modelClass, original.relation, original.reason, original);
    }

    /**
     * Creates an exception with the failure that caused it.
     *
     * @param modelClass the model class whose relations failed the check, never {@code null}
     * @param relation the relation name, or {@code null} when the fault is in the map as a whole
     * @param reason why the check failed, never {@code null}; must not hold attribute values
     * @param cause the underlying failure, or {@code null}
     * @throws NullPointerException if {@code modelClass} or {@code reason} is {@code null}
     */
    @Internal
    public InvalidRelationException(Class<?> modelClass, String relation, String reason, Throwable cause) {
        super(message(Objects.requireNonNull(modelClass, "modelClass"), relation,
                Objects.requireNonNull(reason, "reason")), cause);
        this.modelClass = modelClass;
        this.relation = relation;
        this.reason = reason;
    }

    private static String message(Class<?> modelClass, String relation, String reason) {
        String subject = relation == null ? "Invalid relations()" : "Invalid relation [" + relation + "]";
        return subject + " on model [" + modelClass.getName() + "]: " + reason;
    }

    /**
     * Returns the model class.
     *
     * @return the model class, or {@code null} after deserialisation
     */
    public Class<?> getModelClass() {
        return modelClass;
    }

    /**
     * Returns the relation name.
     *
     * @return the relation name, or {@code null} when the fault is in the map as a whole
     */
    public String getRelation() {
        return relation;
    }

    /**
     * Returns why the check failed.
     *
     * @return the reason, never {@code null}
     */
    public String getReason() {
        return reason;
    }
}
