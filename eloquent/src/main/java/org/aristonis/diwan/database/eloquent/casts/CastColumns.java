package org.aristonis.diwan.database.eloquent.casts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the columns a {@link Cast#set} call writes when one attribute spans several columns.
 *
 * <p>Instances are immutable and safe to share across threads, provided the column values are.
 */
public final class CastColumns {

    private final Map<String, Object> columns;

    private CastColumns(Map<String, Object> columns) {
        this.columns = columns;
    }

    /**
     * Creates a column set from a copy of the given map.
     *
     * <p>Later changes to {@code columns} do not affect the result. Values may be null.
     *
     * @param columns the column names mapped to their storage values, in write order
     * @return the column set
     * @throws NullPointerException if {@code columns} is null
     * @throws IllegalArgumentException if {@code columns} is empty or has a null or empty key
     */
    public static CastColumns of(Map<String, ?> columns) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("CastColumns needs at least one column");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : columns.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("CastColumns column name must be non-empty");
            }
            copy.put(name, entry.getValue());
        }
        return new CastColumns(Collections.unmodifiableMap(copy));
    }

    /**
     * Returns the columns to write.
     *
     * @return an unmodifiable map of column names to storage values, in the source map's
     *     iteration order
     */
    public Map<String, Object> columns() {
        return columns;
    }
}
