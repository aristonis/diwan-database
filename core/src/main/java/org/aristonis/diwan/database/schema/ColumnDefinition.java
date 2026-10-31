package org.aristonis.diwan.database.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.aristonis.diwan.database.values.Money;

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
 *
 * <p>The class is open only to {@link ForeignIdColumnDefinition}; its constructor is not public.
 */
public class ColumnDefinition {

    static final int NO_PRECISION = -1;
    private static final int MAX_DECIMAL_PRECISION = 38;

    private final String name;
    private final ColumnType type;
    private final int length;
    private int total;
    private final int places;
    private final int precision;
    private final List<String> allowed;
    private boolean usesCurrent;
    private boolean isAutoIncrement;
    private boolean isNullable;
    private Object defaultValue;
    private boolean isUnique;
    private String uniqueIndex;
    private boolean isIndexed;
    private String plainIndex;
    private boolean isPrimary;
    private boolean isUnsigned;
    private String comment;
    private final List<String> checks = new ArrayList<>();
    private String virtualAs;
    private String storedAs;
    private String rawDefinition;
    private boolean isNotNullStated;
    private boolean isChange;
    private boolean dropsUnique;
    private boolean dropsIndex;
    private boolean dropsPrimary;
    private boolean isFrozen;

    ColumnDefinition(String name, ColumnType type, int length, int total, int places) {
        this(name, type, length, total, places, NO_PRECISION, Collections.<String>emptyList());
    }

    ColumnDefinition(String name, ColumnType type, int length, int total, int places, int precision,
            List<String> allowed) {
        this.name = SchemaNames.check("column", name);
        this.type = type;
        this.length = length;
        this.total = total;
        this.places = places;
        this.precision = precision;
        this.allowed = Collections.unmodifiableList(new ArrayList<>(allowed));
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
        this.isNotNullStated = !isNullable;
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
        this.usesCurrent = false;
        return this;
    }

    /**
     * Makes the database's current date or time the default.
     *
     * @return this definition
     * @apiNote On a {@code year} column the grammar writes the database's way of reading the current
     *     year, so {@link #defaultValue()} stays empty there; {@link #usesCurrent()} tells it apart.
     * @throws IllegalStateException if the column is not a date, time or year column, or the
     *     definition is frozen
     */
    public ColumnDefinition useCurrent() {
        ensureOpen();
        if (!type.isTemporal()) {
            throw new IllegalStateException("useCurrent applies only to date, time and year columns");
        }
        usesCurrent = true;
        if (type == ColumnType.DATE) {
            defaultValue = Expression.CURRENT_DATE;
        } else if (type == ColumnType.TIME || type == ColumnType.TIME_TZ) {
            defaultValue = Expression.CURRENT_TIME;
        } else if (type != ColumnType.YEAR) {
            defaultValue = Expression.CURRENT_TIMESTAMP;
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
        this.dropsUnique = false;
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
        this.dropsUnique = false;
        return this;
    }

    /**
     * Adds an index on this column, named {@code <table>_<column>_index}.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition index() {
        ensureOpen();
        this.isIndexed = true;
        this.plainIndex = null;
        this.dropsIndex = false;
        return this;
    }

    /**
     * Adds an index on this column with a chosen name.
     *
     * @param indexName the index name, never {@code null}, not empty, without control characters
     * @return this definition
     * @throws NullPointerException if {@code indexName} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition index(String indexName) {
        ensureOpen();
        this.plainIndex = SchemaNames.check("index", indexName);
        this.isIndexed = true;
        this.dropsIndex = false;
        return this;
    }

    /**
     * Makes this column the table's primary key.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition primary() {
        ensureOpen();
        this.isPrimary = true;
        this.dropsPrimary = false;
        return this;
    }

    /**
     * Drops or keeps this column's conventionally named unique index when the column is changed.
     *
     * <p>{@code unique(true)} is the same as {@link #unique()}; {@code unique(false)} drops the
     * index named {@code <table>_<column>_unique} and is allowed only together with
     * {@link #change()}. On SQLite a unique constraint written inside the table definition is not an
     * index and cannot be dropped this way.
     *
     * @param isWanted {@code false} to drop the index
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition unique(boolean isWanted) {
        if (isWanted) {
            return unique();
        }
        ensureOpen();
        this.isUnique = false;
        this.uniqueIndex = null;
        this.dropsUnique = true;
        return this;
    }

    /**
     * Drops or keeps this column's conventionally named index when the column is changed.
     *
     * <p>{@code index(true)} is the same as {@link #index()}; {@code index(false)} drops the index
     * named {@code <table>_<column>_index} and is allowed only together with {@link #change()}.
     *
     * @param isWanted {@code false} to drop the index
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition index(boolean isWanted) {
        if (isWanted) {
            return index();
        }
        ensureOpen();
        this.isIndexed = false;
        this.plainIndex = null;
        this.dropsIndex = true;
        return this;
    }

    /**
     * Makes this column the primary key, or drops the table's primary key when the column is
     * changed.
     *
     * <p>{@code primary(true)} is the same as {@link #primary()}; {@code primary(false)} is allowed
     * only together with {@link #change()}.
     *
     * @param isWanted {@code false} to drop the primary key
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition primary(boolean isWanted) {
        if (isWanted) {
            return primary();
        }
        ensureOpen();
        this.isPrimary = false;
        this.dropsPrimary = true;
        return this;
    }

    /**
     * Adds a {@code CHECK} constraint on this column. Several checks are kept in the order given.
     *
     * <p>The SQL is written into the statement as given, like an {@link Expression}: build it from
     * constants only, never from user input. On SQLite, adding a checked column to an existing table
     * copies the whole table, so add such columns in one {@code Schema.table} call.
     *
     * @param sql the condition, without the {@code check} keyword or outer parentheses, never
     *     {@code null} or blank
     * @return this definition
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition check(String sql) {
        ensureOpen();
        checks.add(trustedSql("check", sql));
        return this;
    }

    /**
     * Makes this a generated column the database computes on every read.
     *
     * <p>The SQL is written as given, like an {@link Expression}: build it from constants only, never from user input. A
     * generated column cannot have a default, {@code useCurrent}, auto-increment or a primary key.
     *
     * @param sql the expression, never {@code null} or blank
     * @return this definition
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition virtualAs(String sql) {
        ensureOpen();
        this.virtualAs = trustedSql("virtualAs", sql);
        return this;
    }

    /**
     * Makes this a generated column the database computes on write and stores.
     *
     * <p>The SQL is written as given, like an {@link Expression}: build it from constants only, never from user input. A
     * generated column cannot have a default, {@code useCurrent}, auto-increment or a primary key.
     *
     * @param sql the expression, never {@code null} or blank
     * @return this definition
     * @throws NullPointerException if {@code sql} is {@code null}
     * @throws IllegalArgumentException if {@code sql} is blank
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition storedAs(String sql) {
        ensureOpen();
        this.storedAs = trustedSql("storedAs", sql);
        return this;
    }

    /**
     * Redefines the existing column with this name instead of adding a new one.
     *
     * <p>The new definition replaces the old one in full: a modifier that is not stated again,
     * such as {@code nullable()} or a default, is gone after the change. On SQLite every change
     * rebuilds the table and copies all its rows, so put the changes for one table in one
     * {@code Schema.table} call. An auto-increment column cannot be changed.
     *
     * @return this definition
     * @throws IllegalStateException if the definition is frozen
     */
    public ColumnDefinition change() {
        ensureOpen();
        this.isChange = true;
        return this;
    }

    /**
     * Stores this money column as an exact decimal instead of whole minor units.
     *
     * <p>On PostgreSQL the column is {@code numeric(precision, scale)}. SQLite has no exact
     * decimal, so the column holds text in one fixed-scale spelling ({@code "1000.000000"} at scale
     * 6), enforced by a check; write it with {@link Money#toText} and read it with
     * {@link Money#fromText}. Text does not sort or sum as numbers in SQL, so on SQLite do those in
     * Java, or keep minor units, which sum and sort exactly everywhere.
     *
     * @param precision the total number of digits, more than the scale and at most 38
     * @return this definition
     * @throws IllegalStateException if this is not a money column, or the definition is frozen
     * @throws IllegalArgumentException if the precision is out of range
     */
    public ColumnDefinition asDecimal(int precision) {
        ensureOpen();
        if (type != ColumnType.MONEY) {
            throw new IllegalStateException("asDecimal applies only to money columns");
        }
        if (precision <= places || precision > MAX_DECIMAL_PRECISION) {
            throw new IllegalArgumentException("The precision must be more than the scale and at most "
                    + MAX_DECIMAL_PRECISION);
        }
        this.total = precision;
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
        if (!type.isInteger()) {
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

    /**
     * Returns the column's precision.
     *
     * <p>For {@link ColumnType#FLOAT} it is the binary digits of the mantissa, 1 to 53; for the time
     * and timestamp types it is the fractional-second digits, 0 to 6. Other types have none.
     *
     * @return the precision, or empty when none was set or the type has none
     */
    public OptionalInt precision() {
        return precision == NO_PRECISION ? OptionalInt.empty() : OptionalInt.of(precision);
    }

    /**
     * Returns the values an {@code enum} or {@code set} column allows.
     *
     * @return the values in declaration order, unmodifiable; empty for other types
     */
    public List<String> allowed() {
        return allowed;
    }

    /**
     * Tells whether {@link #useCurrent()} was called.
     *
     * @return {@code true} if the database fills in the current date, time or year
     */
    public boolean usesCurrent() {
        return usesCurrent;
    }

    /**
     * Tells whether the column has a plain index of its own.
     *
     * @return {@code true} after {@link #index()} or {@link #index(String)}
     */
    public boolean isIndexed() {
        return isIndexed;
    }

    /**
     * Tells whether the column is the primary key on its own.
     *
     * @return {@code true} after {@link #primary()}
     */
    public boolean isPrimary() {
        return isPrimary;
    }

    /**
     * Returns the column's {@code CHECK} conditions.
     *
     * @return the conditions in the order given, unmodifiable; empty when there are none
     */
    public List<String> checks() {
        return Collections.unmodifiableList(checks);
    }

    /**
     * Returns the expression of a virtual generated column.
     *
     * @return the expression set with {@link #virtualAs(String)}, or empty
     */
    public Optional<String> virtualAs() {
        return Optional.ofNullable(virtualAs);
    }

    /**
     * Returns the expression of a stored generated column.
     *
     * @return the expression set with {@link #storedAs(String)}, or empty
     */
    public Optional<String> storedAs() {
        return Optional.ofNullable(storedAs);
    }

    /**
     * Tells whether the database computes the column.
     *
     * @return {@code true} after {@link #virtualAs(String)} or {@link #storedAs(String)}
     */
    public boolean isGenerated() {
        return virtualAs != null || storedAs != null;
    }

    /**
     * Returns the SQL that stands for the type of a {@link ColumnType#RAW} column.
     *
     * @return the definition given to {@code Blueprint.rawColumn}, or empty for other types
     */
    public Optional<String> rawDefinition() {
        return Optional.ofNullable(rawDefinition);
    }

    /**
     * Tells whether {@code nullable(false)} was called.
     *
     * <p>Grammars use it for generated columns, which, as in Laravel, are not marked
     * {@code not null} unless asked: their value follows the columns they are computed from.
     *
     * @return {@code true} after {@code nullable(false)}
     */
    public boolean isNotNullStated() {
        return isNotNullStated;
    }

    /**
     * Tells whether the column redefines an existing one.
     *
     * @return {@code true} after {@link #change()}
     */
    public boolean isChange() {
        return isChange;
    }

    boolean dropsUnique() {
        return dropsUnique;
    }

    boolean dropsIndex() {
        return dropsIndex;
    }

    boolean dropsPrimary() {
        return dropsPrimary;
    }

    Optional<String> plainIndex() {
        return Optional.ofNullable(plainIndex);
    }

    Optional<String> uniqueIndex() {
        return Optional.ofNullable(uniqueIndex);
    }

    void rawDefinition(String sql) {
        this.rawDefinition = trustedSql("rawColumn", sql);
    }

    void freeze() {
        if (virtualAs != null && storedAs != null) {
            throw new IllegalArgumentException("Column " + name + " cannot be both virtualAs and storedAs");
        }
        if (isGenerated() && (defaultValue != null || usesCurrent || isAutoIncrement || isPrimary)) {
            throw new IllegalArgumentException("Generated column " + name
                    + " cannot have a default, useCurrent, auto-increment or a primary key");
        }
        isFrozen = true;
    }

    static String trustedSql(String what, String sql) {
        Objects.requireNonNull(sql, "sql");
        if (sql.trim().isEmpty()) {
            throw new IllegalArgumentException("The SQL of " + what + " is blank");
        }
        return sql;
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
