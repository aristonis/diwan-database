package org.aristonis.diwan.database.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One index of a table as the database reports it, from {@link Schema#indexes(String)}.
 *
 * <p>Instances are immutable.
 */
public final class IndexInfo {

    private final String name;
    private final List<String> columns;
    private final boolean isUnique;
    private final boolean isPrimary;
    private final List<String> orders;
    private final List<String> collations;
    private final String where;

    /**
     * Creates the description.
     *
     * @param name the index name, never {@code null}
     * @param columns the columns in index order, never {@code null}; copied
     * @param isUnique whether the index is unique
     * @param isPrimary whether the index is the primary key
     * @throws NullPointerException if an argument is {@code null}
     */
    public IndexInfo(String name, List<String> columns, boolean isUnique, boolean isPrimary) {
        this(name, columns, isUnique, isPrimary, Collections.nCopies(Objects.requireNonNull(columns, "columns").size(), "asc"),
                Collections.nCopies(columns.size(), ""), null);
    }

    /**
     * Creates the description with column order, collations and a partial-index condition.
     *
     * @param name the index name, never {@code null}
     * @param columns the columns in index order, never {@code null}; copied
     * @param isUnique whether the index is unique
     * @param isPrimary whether the index is the primary key
     * @param orders {@code "asc"} or {@code "desc"} for each column, never {@code null}; copied
     * @param collations the collation name of each column, {@code ""} for the default, never {@code null}; copied
     * @param where the SQL text of the partial-index condition, or {@code null} for a full index
     * @throws NullPointerException if an argument other than {@code where} is {@code null}
     * @throws IllegalArgumentException if {@code orders} or {@code collations} differ in size from {@code columns}
     */
    public IndexInfo(String name, List<String> columns, boolean isUnique, boolean isPrimary, List<String> orders,
            List<String> collations, String where) {
        this.name = Objects.requireNonNull(name, "name");
        this.columns = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(columns, "columns")));
        this.isUnique = isUnique;
        this.isPrimary = isPrimary;
        this.orders = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(orders, "orders")));
        this.collations = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(collations, "collations")));
        if (this.orders.size() != this.columns.size() || this.collations.size() != this.columns.size()) {
            throw new IllegalArgumentException("orders and collations need one entry per column");
        }
        this.where = where;
    }

    /**
     * Returns the index name.
     *
     * @return the name, never {@code null}; {@code primary} for a primary key without its own index
     */
    public String name() {
        return name;
    }

    /**
     * Returns the indexed columns.
     *
     * @return an unmodifiable list in index order, never {@code null}
     */
    public List<String> columns() {
        return columns;
    }

    /**
     * Tells whether the index refuses duplicate values.
     *
     * @return {@code true} for a unique index or primary key
     */
    public boolean isUnique() {
        return isUnique;
    }

    /**
     * Tells whether the index is the primary key.
     *
     * @return {@code true} for the primary key
     */
    public boolean isPrimary() {
        return isPrimary;
    }

    /**
     * Returns the sort order of each indexed column.
     *
     * @return an unmodifiable list of {@code "asc"} or {@code "desc"}, one per column, never {@code null}
     */
    public List<String> orders() {
        return orders;
    }

    /**
     * Returns the collation of each indexed column.
     *
     * @return an unmodifiable list, one per column, {@code ""} for the column's default, never {@code null}
     */
    public List<String> collations() {
        return collations;
    }

    /**
     * Returns the condition of a partial index.
     *
     * <p>The text is the database's own: PostgreSQL normalises it ({@code (live = true)}), SQLite
     * keeps the text the index was created with, without comments. Compare it within one database,
     * not across them.
     *
     * @return the SQL text after {@code where}, or empty for a full index
     */
    public Optional<String> where() {
        return Optional.ofNullable(where);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IndexInfo)) {
            return false;
        }
        IndexInfo that = (IndexInfo) other;
        return isUnique == that.isUnique && isPrimary == that.isPrimary && name.equals(that.name)
                && columns.equals(that.columns) && orders.equals(that.orders) && collations.equals(that.collations)
                && Objects.equals(where, that.where);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, columns, isUnique, isPrimary, orders, collations, where);
    }

    @Override
    public String toString() {
        return "IndexInfo[" + name + ", " + columns + (isPrimary ? ", primary" : isUnique ? ", unique" : "")
                + (where == null ? "" : ", where " + where) + "]";
    }
}
