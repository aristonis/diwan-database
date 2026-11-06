package org.aristonis.diwan.database.eloquent.exception;

import org.aristonis.diwan.database.eloquent.Model;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Thrown when a lookup that must find a model finds none, with Laravel's message:
 * {@code "No query results for model [app.User] 1, 2"}, or {@code "No query results for model
 * [app.User]."} when no ids were asked for.
 */
public final class ModelNotFoundException extends EloquentException {

    private static final long serialVersionUID = 1L;

    /** The binary name of the model class. */
    private final String model;

    /** The ids not found, as text. */
    private final String[] ids;

    /**
     * Creates the exception for a model class and the ids that were not found.
     *
     * @param model the model class, never {@code null}
     * @param ids the ids not found, never {@code null}, may be empty; kept as text
     * @throws NullPointerException if an argument is {@code null}
     */
    public ModelNotFoundException(Class<? extends Model> model, Collection<?> ids) {
        this(Objects.requireNonNull(model, "model").getName(), text(ids));
    }

    private ModelNotFoundException(String model, List<String> ids) {
        super("No query results for model [" + model + "]" + (ids.isEmpty() ? "." : " " + String.join(", ", ids)));
        this.model = model;
        this.ids = ids.toArray(new String[0]);
    }

    private static List<String> text(Collection<?> ids) {
        List<String> out = new ArrayList<>(Objects.requireNonNull(ids, "ids").size());
        for (Object id : ids) {
            out.add(String.valueOf(id));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Returns the binary name of the model class, such as {@code app.User}.
     *
     * @return the class name, never {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * Returns the ids that were not found, as text.
     *
     * @return the ids, unmodifiable, in the order given, never {@code null}
     */
    public List<String> getIds() {
        return Collections.unmodifiableList(Arrays.asList(ids));
    }
}
