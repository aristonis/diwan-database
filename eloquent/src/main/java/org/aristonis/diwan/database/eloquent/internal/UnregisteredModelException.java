package org.aristonis.diwan.database.eloquent.internal;

import java.util.Objects;

/**
 * Thrown when a relation names a model class that has no {@code ModelQuery.of(...)} entry yet.
 *
 * <p>Internal: the relation registry tells this failure apart from a broken relation map, so it is
 * not cached and a later registration of the class is seen. Callers see it as an
 * {@link IllegalStateException}. Application code must not catch it by this type.
 */
public final class UnregisteredModelException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The class with no entry, kept by name so the exception stays serialisable. */
    private final String modelClassName;

    /**
     * Creates the exception for a model class.
     *
     * @param modelClass the class with no entry, never {@code null}
     */
    public UnregisteredModelException(Class<?> modelClass) {
        this(modelClass, null);
    }

    /**
     * Creates the exception for a model class, with the class-load error as the cause.
     *
     * @param modelClass the class with no entry, never {@code null}
     * @param cause the error that stopped the class from being found or linked, or {@code null}
     */
    public UnregisteredModelException(Class<?> modelClass, Throwable cause) {
        super("Model [" + Objects.requireNonNull(modelClass, "modelClass").getName()
                + "] has no ModelQuery.of(...) entry to create instances with", cause);
        this.modelClassName = modelClass.getName();
    }

    /**
     * Returns the name of the class that has no entry.
     *
     * @return the binary class name, never {@code null}
     */
    public String getModelClassName() {
        return modelClassName;
    }
}
