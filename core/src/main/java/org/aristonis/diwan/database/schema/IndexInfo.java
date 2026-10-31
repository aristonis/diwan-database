package org.aristonis.diwan.database.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
        this.name = Objects.requireNonNull(name, "name");
        this.columns = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(columns, "columns")));
        this.isUnique = isUnique;
        this.isPrimary = isPrimary;
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
                && columns.equals(that.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, columns, isUnique, isPrimary);
    }

    @Override
    public String toString() {
        return "IndexInfo[" + name + ", " + columns + (isPrimary ? ", primary" : isUnique ? ", unique" : "") + "]";
    }
}
