package org.aristonis.diwan.database.schema;

import java.util.Objects;

/**
 * One table as the database reports it, from {@link Schema#getTables()}.
 *
 * <p>The name is the real name, table prefix included. Instances are immutable.
 */
public final class TableInfo {

    private final String name;
    private final String schema;

    /**
     * Creates the description.
     *
     * @param name the table name, never {@code null}
     * @param schema the database schema that holds it, such as {@code main} or {@code public},
     *     never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public TableInfo(String name, String schema) {
        this.name = Objects.requireNonNull(name, "name");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /**
     * Returns the table name.
     *
     * @return the name, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * Returns the database schema that holds the table.
     *
     * @return the schema name, never {@code null}
     */
    public String schema() {
        return schema;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TableInfo)) {
            return false;
        }
        TableInfo that = (TableInfo) other;
        return name.equals(that.name) && schema.equals(that.schema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, schema);
    }

    @Override
    public String toString() {
        return "TableInfo{schema=" + schema + ", name=" + name + "}";
    }
}
