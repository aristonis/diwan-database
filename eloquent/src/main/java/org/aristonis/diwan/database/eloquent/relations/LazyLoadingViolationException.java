package org.aristonis.diwan.database.eloquent.relations;

import java.util.Objects;

/**
 * Signals a lazy load while lazy loading is prevented, a port of Laravel's
 * {@code LazyLoadingViolationException}.
 *
 * <p>Thrown when {@code ModelQuery.preventLazyLoading(true)} is set and code reads a relation that
 * is not loaded from a model that came in a result of more than one row. No query has run. The
 * message names the model class and the relation; it carries no attribute value.
 *
 * <p>Instances are immutable after construction.
 */
public final class LazyLoadingViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The model class. */
    private final transient Class<?> modelClass;

    /** The relation that was read. */
    private final String relation;

    /**
     * Creates an exception.
     *
     * @param modelClass the model class, never {@code null}
     * @param relation the relation that was read, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public LazyLoadingViolationException(Class<?> modelClass, String relation) {
        super("Attempted to lazy load [" + Objects.requireNonNull(relation, "relation") + "] on model ["
                + Objects.requireNonNull(modelClass, "modelClass").getName() + "] but lazy loading is disabled.");
        this.modelClass = modelClass;
        this.relation = relation;
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
     * Returns the relation that was read.
     *
     * @return the relation name, never {@code null}
     */
    public String getRelation() {
        return relation;
    }
}
