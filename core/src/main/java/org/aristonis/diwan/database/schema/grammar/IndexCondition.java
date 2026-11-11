package org.aristonis.diwan.database.schema.grammar;

import org.aristonis.diwan.database.Internal;
import java.util.Objects;

/**
 * One condition of a partial index's {@code WHERE} clause, passed to a grammar in
 * {@link Command#WHERE}.
 *
 * <p>Instances are immutable. The value was checked by the index definition, so a grammar only
 * writes it.
 */
@Internal
public final class IndexCondition {

    /** What the condition tests. */
    @Internal
    public enum Kind {
        /** The column equals the value. */
        EQUALS,
        /** The column is SQL {@code NULL}. */
        NULL,
        /** The column is not SQL {@code NULL}. */
        NOT_NULL
    }

    private final String column;
    private final Kind kind;
    private final Object value;

    /**
     * Creates a condition.
     *
     * @param column the column name, never {@code null}
     * @param kind what the condition tests, never {@code null}
     * @param value the compared value for {@link Kind#EQUALS}, never {@code null} there; ignored otherwise
     * @throws NullPointerException if {@code column} or {@code kind} is {@code null}, or the value is
     *     {@code null} for {@link Kind#EQUALS}
     */
    public IndexCondition(String column, Kind kind, Object value) {
        this.column = Objects.requireNonNull(column, "column");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.value = kind == Kind.EQUALS ? Objects.requireNonNull(value, "value") : null;
    }

    /**
     * Returns the column.
     *
     * @return the column name, never {@code null}
     */
    public String column() {
        return column;
    }

    /**
     * Returns what the condition tests.
     *
     * @return the kind, never {@code null}
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the compared value.
     *
     * @return the value for {@link Kind#EQUALS}; {@code null} for the other kinds
     */
    public Object value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IndexCondition)) {
            return false;
        }
        IndexCondition that = (IndexCondition) other;
        return column.equals(that.column) && kind == that.kind && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(column, kind, value);
    }

    @Override
    public String toString() {
        return "IndexCondition[" + column + ", " + kind + "]";
    }
}
