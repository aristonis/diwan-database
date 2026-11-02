package org.aristonis.diwan.database.eloquent;

/**
 * Thrown when a mass assignment names a key the model does not allow, with Laravel's message.
 */
public final class MassAssignmentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with its message.
     *
     * @param message the message, such as
     *     {@code "Add [title] to fillable property to allow mass assignment on [app.Post]."}
     */
    public MassAssignmentException(String message) {
        super(message);
    }
}
