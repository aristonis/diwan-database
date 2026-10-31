package org.aristonis.photisma.database.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * One column of a {@link Blueprint}, with Laravel's modifiers.
 *
 * <p>Each modifier returns this definition, so calls chain:
 * {@code table.string("email").nullable().unique()}. Columns are not null unless
 * {@link #nullable()} is called.
 *
 * <p>A definition is changed only inside the schema callback. Once the schema builder has checked
 * the blueprint, the definition is frozen and every modifier throws {@link IllegalStateException},
 * so a grammar cannot change what it compiles.
 */
public final class ColumnDefinition {

    private final String name;
    private final ColumnType type;
    private final int length;
    private final int total;
    private final int places;
    private boolean isAutoIncrement;
    private boolean isNullable;
    private Object defaultValue;
    private boolean isUnique;
    private String uniqueIndex;
    private boolean isUnsigned;
    private String comment;
    private boolean isFrozen;

    ColumnDefinition(String name, ColumnType type, int length, int total, int places) {
        this.name = SchemaNames.check("column", name);
        this.type = type;
        this.length = length;
        this.total = total;
        this.places = places;
    }

    /**
     * Allows SQL {@code NULL} in the column.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition nullable() {
        return nullable(true);
    }

    /**
     * Sets whether the column allows SQL {@code NULL}.
     *
     * @param isNullable {@code true} to allow {@code NULL}
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition nullable(boolean isNullable) {
        ensureOpen();
        this.isNullable = isNullable;
        return this;
    }

    /**
     * Sets the value the database stores when an insert leaves the column out.
     *
     * <p>Named {@code defaultValue} because {@code default} is a Java keyword.
     *
     * @param value the value: a {@link String}, a {@link Boolean}, an integer type,
     *     {@link BigInteger}, {@link BigDecimal}, a finite {@link Float} or {@link Double}, or an
     *     {@link Expression}; {@code null} sets SQL {@code NULL} as the default, as Laravel's
     *     {@code default(null)} does
     * @return this definition
     * @throws IllegalArgumentException if the value has another type or is not finite
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition defaultValue(Object value) {
        ensureOpen();
        this.defaultValue = checkDefault(value);
        return this;
    }

    /**
     * Makes the database's current date or time the default.
     *
     * @return this definition
     * @throws IllegalStateException if the column is not a date or timestamp, or the definition is
     *     frozen
     */
    public ColumnDefinition useCurrent() {
        ensureOpen();
        if (type == ColumnType.DATE) {
            defaultValue = Expression.CURRENT_DATE;
        } else if (type == ColumnType.TIMESTAMP) {
            defaultValue = Expression.CURRENT_TIMESTAMP;
        } else {
            throw new IllegalStateException("useCurrent applies only to date and timestamp columns");
        }
        return this;
    }

    /**
     * Adds a unique index on this column, named {@code <table>_<column>_unique}.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition unique() {
        ensureOpen();
        this.isUnique = true;
        this.uniqueIndex = null;
        return this;
    }

    /**
     * Adds a unique index on this column with a chosen name.
     *
     * @param indexName the index name, never {@code null}, not empty, without control characters
     * @return this definition
     * @throws NullPointerException if {@code indexName} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition unique(String indexName) {
        ensureOpen();
        this.uniqueIndex = SchemaNames.check("index", indexName);
        this.isUnique = true;
        return this;
    }

    /**
     * Marks a number column as unsigned. Databases without unsigned types ignore it.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition unsigned() {
        ensureOpen();
        this.isUnsigned = true;
        return this;
    }

    /**
     * Attaches a comment to the column. Databases without column comments ignore it.
     *
     * @param text the comment, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition comment(String text) {
        ensureOpen();
        this.comment = Objects.requireNonNull(text, "text");
        return this;
    }

    /**
     * Makes the column count up by itself on insert, and its table's primary key.
     *
     * @return this definition
     * @throws IllegalStateException if the column is not an integer type, or the definition is
     *     frozen
     */
    public ColumnDefinition autoIncrement() {
        ensureOpen();
        if (type != ColumnType.INTEGER && type != ColumnType.BIG_INTEGER) {
            throw new IllegalStateException("autoIncrement applies only to integer columns");
        }
        this.isAutoIncrement = true;
        return this;
    }

    /**
     * Returns the column name.
     *
     * @return the name, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * Returns the column type.
     *
     * @return the type, never {@code null}
     */
    public ColumnType type() {
        return type;
    }

    /**
     * Returns the maximum length of a {@link ColumnType#STRING} column.
     *
     * @return the length, or 0 for other types
     */
    public int length() {
        return length;
    }

    /**
     * Returns the total number of digits of a {@link ColumnType#DECIMAL} column.
     *
     * @return the digits, or 0 for other types
     */
    public int total() {
        return total;
    }

    /**
     * Returns the digits after the point of a {@link ColumnType#DECIMAL} column.
     *
     * @return the digits, or 0 for other types
     */
    public int places() {
        return places;
    }

    /**
     * Tells whether the column counts up by itself.
     *
     * @return {@code true} for an auto-increment column
     */
    public boolean isAutoIncrement() {
        return isAutoIncrement;
    }

    /**
     * Tells whether the column allows SQL {@code NULL}.
     *
     * @return {@code true} if {@code NULL} is allowed
     */
    public boolean isNullable() {
        return isNullable;
    }

    /**
     * Returns the default value.
     *
     * @return the value set with {@link #defaultValue(Object)} or {@link #useCurrent()}, or empty
     */
    public Optional<Object> defaultValue() {
        return Optional.ofNullable(defaultValue);
    }

    /**
     * Tells whether the column has its own unique index.
     *
     * @return {@code true} after {@link #unique()} or {@link #unique(String)}
     */
    public boolean isUnique() {
        return isUnique;
    }

    /**
     * Tells whether the column is unsigned.
     *
     * @return {@code true} after {@link #unsigned()}
     */
    public boolean isUnsigned() {
        return isUnsigned;
    }

    /**
     * Returns the comment.
     *
     * @return the comment, or empty
     */
    public Optional<String> comment() {
        return Optional.ofNullable(comment);
    }

    Optional<String> uniqueIndex() {
        return Optional.ofNullable(uniqueIndex);
    }

    void freeze() {
        isFrozen = true;
    }

    private void ensureOpen() {
        if (isFrozen) {
            throw new IllegalStateException("Column " + name + " can no longer be changed");
        }
    }

    private static Object checkDefault(Object value) {
        if (value == null) {
            return Expression.NULL;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Expression
                || value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new IllegalArgumentException("A default number must be finite");
            }
            return value;
        }
        throw new IllegalArgumentException("Unsupported default value type " + value.getClass().getName());
    }
}
