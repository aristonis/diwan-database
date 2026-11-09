package org.aristonis.diwan.database.schema;

/** The kind of index {@link Schema#hasIndex(String, String, IndexType)} looks for. */
public enum IndexType {
    /** The index is the primary key. */
    PRIMARY,
    /** The index refuses duplicate values; a primary key counts. */
    UNIQUE
}
