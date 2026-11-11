package org.aristonis.diwan.database.schema;

import java.util.Objects;
import org.aristonis.diwan.database.Internal;

/**
 * How a generated column gets its value, from {@link ColumnInfo#generation()}.
 *
 * <p>Instances are immutable.
 */
public final class ColumnGeneration {

    private final boolean isStored;
    private final String expression;

    /**
     * Creates the description. Only a grammar calls this.
     *
     * @param isStored whether the value is stored, not computed on each read
     * @param expression the SQL text of the expression, never {@code null}
     * @throws NullPointerException if {@code expression} is {@code null}
     */
    @Internal
    public ColumnGeneration(boolean isStored, String expression) {
        this.isStored = isStored;
        this.expression = Objects.requireNonNull(expression, "expression");
    }

    /**
     * Tells whether the value is stored.
     *
     * @return {@code true} for a stored column, {@code false} for a virtual one
     */
    public boolean isStored() {
        return isStored;
    }

    /**
     * Returns the generation expression.
     *
     * @return the SQL text as the database reports it, never {@code null}; empty when the database
     *     does not keep it, as SQLite when the table text is unavailable or cannot be read
     */
    public String expression() {
        return expression;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ColumnGeneration)) {
            return false;
        }
        ColumnGeneration that = (ColumnGeneration) other;
        return isStored == that.isStored && expression.equals(that.expression);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isStored, expression);
    }

    @Override
    public String toString() {
        return "ColumnGeneration[" + (isStored ? "stored" : "virtual") + ", " + expression + "]";
    }
}
