package org.aristonis.diwan.database.schema.grammar;

import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.TableInfo;

/**
 * Builds the queries and listings that read a database's schema.
 *
 * <p>Split out of {@link SchemaGrammar} to keep that class small; it is not part of the public API.
 */
abstract class InspectionGrammar extends ConstraintGrammar {

    /** Creates the inspection layer of a grammar. */
    protected InspectionGrammar() {
    }

    /**
     * Lists the indexes of a table, the primary key included.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection to read with, never {@code null}
     * @param table the full table name, prefix included, never {@code null}
     * @return the indexes, unmodifiable, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public List<IndexInfo> indexes(Connection connection, String table) {
        throw unsupported("Listing indexes");
    }

    /**
     * Lists the foreign keys of a table.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection to read with, never {@code null}
     * @param table the full table name, prefix included, never {@code null}
     * @return the foreign keys, unmodifiable, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String table) {
        throw unsupported("Listing foreign keys");
    }

    /**
     * Returns a query that counts the tables with a name.
     *
     * @implSpec The base refuses the check.
     *
     * @param connection the connection, never {@code null}; lets a grammar bind the current schema
     *     or database as well
     * @param table the full table name, prefix included, never {@code null}
     * @return the query, never {@code null}; its single value is the count
     * @throws UnsupportedOperationException if the grammar does not support the check
     */
    public SchemaQuery compileTableExists(Connection connection, String table) {
        throw unsupported("Checking for a table");
    }

    /**
     * Returns a query whose first column lists the column names of a table.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection, never {@code null}; lets a grammar bind the current schema
     *     or database as well
     * @param table the full table name, prefix included, never {@code null}
     * @return the query, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public SchemaQuery compileColumnNames(Connection connection, String table) {
        throw unsupported("Listing columns");
    }

    /**
     * Returns a query that lists the user tables, one row each with the columns {@code name} and
     * {@code schema}.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection, never {@code null}; lets a grammar choose by database version
     * @return the query, never {@code null}; ordered by schema, then name
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public SchemaQuery compileTables(Connection connection) {
        throw unsupported("Listing tables");
    }

    /**
     * Returns a query that lists the views, one row each with the columns {@code name} and
     * {@code schema}.
     *
     * @implSpec The base refuses the listing.
     *
     * @param connection the connection, never {@code null}; lets a grammar choose by database version
     * @return the query, never {@code null}; ordered by schema, then name
     * @throws UnsupportedOperationException if the grammar does not support the listing
     */
    public SchemaQuery compileViews(Connection connection) {
        throw unsupported("Listing views");
    }

    /**
     * Tells whether a listed table belongs to the database engine or an extension, so that dropping
     * or emptying every table must leave it alone.
     *
     * @implSpec The base returns {@code false}.
     *
     * @param table the table as {@link #compileTables} lists it, never {@code null}
     * @return {@code true} if the table is internal
     */
    public boolean isInternalTable(TableInfo table) {
        return false;
    }
}
