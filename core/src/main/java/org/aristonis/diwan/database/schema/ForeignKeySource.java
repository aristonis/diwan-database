package org.aristonis.diwan.database.schema;

/**
 * Describes the key another table points at, so {@link Blueprint#foreignIdFor(ForeignKeySource)}
 * can add the matching column without the caller repeating the names.
 *
 * <p>A model layer implements it; the schema package only reads the four values.
 */
public interface ForeignKeySource {

    /**
     * Returns the column other tables use to point at this key, such as {@code user_id}.
     *
     * @return the column name, never {@code null}
     */
    String foreignKey();

    /**
     * Returns the referenced table, without the connection's prefix: the blueprint adds the prefix
     * when it writes the foreign key.
     *
     * @return the table name, never {@code null}
     */
    String table();

    /**
     * Returns the referenced key column.
     *
     * @return the column name, never {@code null}
     */
    String keyName();

    /**
     * Returns the kind of the key, which picks the column type.
     *
     * @return the kind, never {@code null}
     */
    KeyKind keyKind();

    /** The kinds of key a foreign column can point at. */
    enum KeyKind {
        /** An integer key, stored as an unsigned big integer column. */
        INTEGER,
        /** A UUID key, stored as a UUID column. */
        UUID,
        /** A ULID key, stored as a 26-character column. */
        ULID
    }
}
