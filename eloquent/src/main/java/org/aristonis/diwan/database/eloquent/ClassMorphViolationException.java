package org.aristonis.diwan.database.eloquent;

import java.util.Objects;

/**
 * Signals that a polymorphic relation met a model class or a type string the morph map does not
 * cover, a port of Laravel's {@code ClassMorphViolationException}.
 *
 * <p>It is thrown when a class has no alias while the map is required, when a stored type string
 * names no alias while the map is required, and when a stored type string names a class that is
 * missing or is not a {@link Model}. The message names the class or the string and tells the
 * caller to add it to the map.
 *
 * <p>Instances are immutable after construction.
 */
public class ClassMorphViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The class name or type string that failed. */
    private final String model;

    /**
     * Creates an exception.
     *
     * @param model the class name or type string that failed, never {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     */
    public ClassMorphViolationException(String model) {
        super("No morph alias is defined for [" + Objects.requireNonNull(model, "model")
                + "]; add it with MorphMap.morphMap.");
        this.model = model;
    }

    /**
     * Returns the class name or type string that failed.
     *
     * @return the class name or type string, never {@code null}
     */
    public String model() {
        return model;
    }
}
