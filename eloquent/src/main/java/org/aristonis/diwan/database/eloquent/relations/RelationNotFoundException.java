package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Signals that a model class has no relation of a given name, a port of Laravel's
 * {@code RelationNotFoundException}.
 *
 * <p>Thrown by a model's {@code relation(name)}, by {@code with} and {@code load} before any
 * query runs, and by {@code has} and its forms. The message names the model class, the relation
 * and up to 20 of the names the class knows, so a typo is easy to spot; it carries no attribute
 * value.
 *
 * <p>Instances are immutable after construction.
 */
public final class RelationNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** At most this many known names go into the message. */
    private static final int KNOWN_NAMES_SHOWN = 20;

    /** The model class. */
    private final transient Class<?> modelClass;

    /** The name that was asked for. */
    private final String relation;

    /** Every name the class knows, in map order. */
    private final transient List<String> knownRelations;

    /**
     * Creates an exception.
     *
     * @param modelClass the model class, never {@code null}
     * @param relation the name that was asked for, never {@code null}
     * @param knownRelations the names the class knows, in map order, never {@code null}, copied
     * @throws NullPointerException if an argument is {@code null}
     */
    public RelationNotFoundException(Class<?> modelClass, String relation, List<String> knownRelations) {
        super(message(Objects.requireNonNull(modelClass, "modelClass"), Objects.requireNonNull(relation, "relation"),
                Objects.requireNonNull(knownRelations, "knownRelations")));
        this.modelClass = modelClass;
        this.relation = relation;
        this.knownRelations = Collections.unmodifiableList(new ArrayList<>(knownRelations));
    }

    private static String message(Class<?> modelClass, String relation, List<String> known) {
        StringBuilder text = new StringBuilder("Call to undefined relationship [").append(relation)
                .append("] on model [").append(modelClass.getName()).append("]. Known: ");
        if (known.isEmpty()) {
            return text.append("none").toString();
        }
        int shown = Math.min(known.size(), KNOWN_NAMES_SHOWN);
        text.append(String.join(", ", known.subList(0, shown)));
        if (known.size() > shown) {
            text.append(", and ").append(known.size() - shown).append(" more");
        }
        return text.toString();
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
     * Returns the name that was asked for.
     *
     * @return the relation name, never {@code null}
     */
    public String getRelation() {
        return relation;
    }

    /**
     * Returns every name the model class knows, not only the ones in the message.
     *
     * @return the names in map order, unmodifiable, or {@code null} after deserialisation
     */
    public List<String> getKnownRelations() {
        return knownRelations;
    }
}
