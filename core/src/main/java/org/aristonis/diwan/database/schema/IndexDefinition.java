package org.aristonis.diwan.database.schema;

import org.aristonis.diwan.database.schema.grammar.IndexCondition;
import org.aristonis.diwan.database.schema.internal.SchemaNames;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An index or unique index of a {@link Blueprint}, with chained calls for a partial index, column
 * order and collation:
 * {@code table.unique("email").where("deleted", false).desc("email")}.
 *
 * <p>Like a column, a definition changes only inside the schema callback and is frozen once the
 * schema builder has checked the blueprint. Conditions are joined by {@code and}.
 */
public final class IndexDefinition {

    private final List<String> columns;
    private final List<IndexCondition> conditions = new ArrayList<>();
    private final Map<String, String> orders = new LinkedHashMap<>();
    private final Map<String, String> collations = new LinkedHashMap<>();
    private boolean isFrozen;

    IndexDefinition(List<String> columns) {
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
    }

    /**
     * Limits the index to rows where a column equals a value; {@code null} means
     * {@link #whereNull(String)}.
     *
     * @param column the column name, never {@code null}; it need not be an indexed column
     * @param value a {@code String}, {@code Boolean}, integer type, {@code BigDecimal}, finite
     *     {@code Double} or {@code Float}, or {@code null}
     * @return this definition
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character, or the value
     *     has another type or is not finite
     * @throws IllegalStateException if the definition is frozen
     */
    public IndexDefinition where(String column, Object value) {
        ensureOpen();
        SchemaNames.check("column", column);
        if (value == null) {
            return whereNull(column);
        }
        checkValue(value);
        conditions.add(new IndexCondition(column, IndexCondition.Kind.EQUALS, value));
        return this;
    }

    /**
     * Limits the index to rows where a column is SQL {@code NULL}.
     *
     * @param column the column name, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the definition is frozen
     */
    public IndexDefinition whereNull(String column) {
        ensureOpen();
        conditions.add(new IndexCondition(SchemaNames.check("column", column), IndexCondition.Kind.NULL, null));
        return this;
    }

    /**
     * Limits the index to rows where a column is not SQL {@code NULL}.
     *
     * @param column the column name, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the definition is frozen
     */
    public IndexDefinition whereNotNull(String column) {
        ensureOpen();
        conditions.add(new IndexCondition(SchemaNames.check("column", column), IndexCondition.Kind.NOT_NULL, null));
        return this;
    }

    /**
     * Sorts an indexed column ascending, which is the database's default.
     *
     * @param column one of the index's columns, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the column is not in the index
     * @throws IllegalStateException if the definition is frozen
     */
    public IndexDefinition asc(String column) {
        return order(column, "asc");
    }

    /**
     * Sorts an indexed column descending.
     *
     * @param column one of the index's columns, never {@code null}
     * @return this definition
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the column is not in the index
     * @throws IllegalStateException if the definition is frozen
     */
    public IndexDefinition desc(String column) {
        return order(column, "desc");
    }

    /**
     * Sets the collation of an indexed column, by its database name such as {@code NOCASE}.
     *
     * @param column one of the index's columns, never {@code null}
     * @param collation the collation name, never {@code null}
     * @return this definition
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the column is not in the index, or the collation name is
     *     empty or has a quote or control character
     * @throws IllegalStateException if the definition is frozen
     */
    public IndexDefinition collate(String column, String collation) {
        ensureOpen();
        String own = ownColumn(column);
        collations.put(own, Collation.of(collation).name());
        return this;
    }

    /**
     * Returns the indexed columns.
     *
     * @return an unmodifiable list, never {@code null} or empty
     */
    public List<String> columns() {
        return columns;
    }

    List<IndexCondition> conditions() {
        return Collections.unmodifiableList(new ArrayList<>(conditions));
    }

    Map<String, String> orders() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(orders));
    }

    Map<String, String> collations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(collations));
    }

    void freeze() {
        isFrozen = true;
    }

    private IndexDefinition order(String column, String direction) {
        ensureOpen();
        orders.put(ownColumn(column), direction);
        return this;
    }

    private String ownColumn(String column) {
        SchemaNames.check("column", column);
        for (String own : columns) {
            if (own.equalsIgnoreCase(column)) {
                return own;
            }
        }
        throw new IllegalArgumentException("The column is not in the index " + columns);
    }

    private static void checkValue(Object value) {
        boolean isAllowed = value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger || value instanceof BigDecimal;
        if (value instanceof Double || value instanceof Float) {
            isAllowed = !Double.isNaN(((Number) value).doubleValue())
                    && !Double.isInfinite(((Number) value).doubleValue());
        }
        if (!isAllowed) {
            throw new IllegalArgumentException("An index condition value must be text, a boolean or a finite number, not "
                    + value.getClass().getSimpleName());
        }
    }

    private void ensureOpen() {
        if (isFrozen) {
            throw new IllegalStateException("The index on " + columns + " can no longer be changed");
        }
    }
}
