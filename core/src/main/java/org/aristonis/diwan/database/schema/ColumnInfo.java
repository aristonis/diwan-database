package org.aristonis.diwan.database.schema;

import java.util.Objects;
import java.util.Optional;
import org.aristonis.diwan.database.Internal;

/**
 * One column of a table or view as the database reports it, from {@link Schema#getColumns(String)}.
 *
 * <p>Instances are immutable.
 */
public final class ColumnInfo {

    private final String name;
    private final String typeName;
    private final String type;
    private final String collation;
    private final boolean isNullable;
    private final String defaultValue;
    private final boolean isAutoIncrement;
    private final String comment;
    private final ColumnGeneration generation;

    /**
     * Creates the description. Only a grammar calls this.
     *
     * @param name the column name, never {@code null}
     * @param typeName the base type in lower case, such as {@code varchar}, never {@code null}
     * @param type the full type in lower case, such as {@code varchar(255)}, never {@code null}
     * @param collation the collation name, or {@code null} for the default
     * @param isNullable whether the column accepts {@code NULL}
     * @param defaultValue the raw SQL text of the default, or {@code null} for none
     * @param isAutoIncrement whether the database fills the column with the next number
     * @param comment the column comment, or {@code null} for none
     * @param generation how a generated column gets its value, or {@code null} for an ordinary column
     * @throws NullPointerException if {@code name}, {@code typeName} or {@code type} is {@code null}
     */
    @Internal
    public ColumnInfo(String name, String typeName, String type, String collation, boolean isNullable,
            String defaultValue, boolean isAutoIncrement, String comment, ColumnGeneration generation) {
        this.name = Objects.requireNonNull(name, "name");
        this.typeName = Objects.requireNonNull(typeName, "typeName");
        this.type = Objects.requireNonNull(type, "type");
        this.collation = collation;
        this.isNullable = isNullable;
        this.defaultValue = defaultValue;
        this.isAutoIncrement = isAutoIncrement;
        this.comment = comment;
        this.generation = generation;
    }

    /**
     * Returns the column name.
     *
     * @return the name as stored, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * Returns the base type.
     *
     * @return the type name without size, in lower case, never {@code null}
     */
    public String typeName() {
        return typeName;
    }

    /**
     * Returns the full type.
     *
     * @return the type with size or precision, in lower case, never {@code null}
     */
    public String type() {
        return type;
    }

    /**
     * Returns the column collation.
     *
     * @return the collation name, or empty for the default
     */
    public Optional<String> collation() {
        return Optional.ofNullable(collation);
    }

    /**
     * Tells whether the column accepts {@code NULL}.
     *
     * @return {@code true} if it does
     */
    public boolean isNullable() {
        return isNullable;
    }

    /**
     * Returns the default value.
     *
     * @return the raw SQL text of the default, or empty for none
     */
    public Optional<String> defaultValue() {
        return Optional.ofNullable(defaultValue);
    }

    /**
     * Tells whether the database fills the column with the next number.
     *
     * @return {@code true} for an auto-incrementing column
     */
    public boolean isAutoIncrement() {
        return isAutoIncrement;
    }

    /**
     * Returns the column comment.
     *
     * @return the comment, or empty for none; always empty on SQLite
     */
    public Optional<String> comment() {
        return Optional.ofNullable(comment);
    }

    /**
     * Returns how a generated column gets its value.
     *
     * @return the generation, or empty for an ordinary column
     */
    public Optional<ColumnGeneration> generation() {
        return Optional.ofNullable(generation);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ColumnInfo)) {
            return false;
        }
        ColumnInfo that = (ColumnInfo) other;
        return isNullable == that.isNullable && isAutoIncrement == that.isAutoIncrement && name.equals(that.name)
                && typeName.equals(that.typeName) && type.equals(that.type)
                && Objects.equals(collation, that.collation) && Objects.equals(defaultValue, that.defaultValue)
                && Objects.equals(comment, that.comment) && Objects.equals(generation, that.generation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, typeName, type, collation, isNullable, defaultValue, isAutoIncrement, comment,
                generation);
    }

    @Override
    public String toString() {
        return "ColumnInfo[" + name + ", " + type + (isNullable ? ", nullable" : "")
                + (isAutoIncrement ? ", auto-increment" : "") + (generation == null ? "" : ", " + generation) + "]";
    }
}
