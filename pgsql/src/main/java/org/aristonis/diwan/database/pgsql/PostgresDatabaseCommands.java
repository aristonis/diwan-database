package org.aristonis.diwan.database.pgsql;

import java.util.Collections;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.schema.DatabaseVersion;
import org.aristonis.diwan.database.schema.grammar.SchemaQuery;

/**
 * Builds the statements that create and drop a database, and refuses the clauses an older server
 * does not know. Every name is passed through the quoting function the grammar supplies, so a name
 * is never joined into the statement as raw text.
 */
final class PostgresDatabaseCommands {

    /** First PostgreSQL version that accepts {@code strategy} on {@code create database}. */
    static final int STRATEGY_SINCE = 15;

    /** First PostgreSQL version that accepts {@code with (force)} on {@code drop database}. */
    static final int FORCE_SINCE = 13;

    private static final String EXISTS_SQL = "select 1 from pg_database where datname = ?";

    private final UnaryOperator<String> wrap;

    /**
     * Creates the builder.
     *
     * @param wrap the function that quotes one identifier, never {@code null}
     */
    PostgresDatabaseCommands(UnaryOperator<String> wrap) {
        this.wrap = Objects.requireNonNull(wrap, "wrap");
    }

    /**
     * Compiles {@code create database}. No encoding clause is sent: the new database takes its
     * template's encoding.
     *
     * @param connection the connection that reads the server version when a strategy is given, never
     *     {@code null}
     * @param name the new database name, never {@code null}
     * @param template the template database to copy, or {@code null} for the server default
     * @param strategy how the template is copied, or {@code null} to leave it to the server
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if a strategy is given and the server is older than
     *     PostgreSQL 15
     */
    String compileCreate(Connection connection, String name, String template, CopyStrategy strategy) {
        StringBuilder sql = new StringBuilder("create database ").append(wrap.apply(name));
        if (template != null) {
            sql.append(" template ").append(wrap.apply(template));
        }
        if (strategy != null) {
            String version = PostgresVersion.of(connection);
            if (!DatabaseVersion.atLeast(version, STRATEGY_SINCE)) {
                throw new UnsupportedOperationException("A copy strategy needs PostgreSQL " + STRATEGY_SINCE
                        + " or later; this database runs " + version);
            }
            sql.append(" strategy ").append(strategy == CopyStrategy.WAL_LOG ? "wal_log" : "file_copy");
        }
        return sql.toString();
    }

    /**
     * Compiles {@code drop database if exists}.
     *
     * @param connection the connection that reads the server version when {@code force} is set,
     *     never {@code null}
     * @param name the database name, never {@code null}
     * @param force {@code true} to disconnect other sessions first
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if {@code force} is set and the server is older than
     *     PostgreSQL 13
     */
    String compileDropIfExists(Connection connection, String name, boolean force) {
        String sql = "drop database if exists " + wrap.apply(name);
        if (!force) {
            return sql;
        }
        String version = PostgresVersion.of(connection);
        if (!DatabaseVersion.atLeast(version, FORCE_SINCE)) {
            throw new UnsupportedOperationException("Dropping a database with force needs PostgreSQL "
                    + FORCE_SINCE + " or later; this database runs " + version);
        }
        return sql + " with (force)";
    }

    /**
     * Compiles the query that finds a database by name.
     *
     * @param name the database name, bound and not quoted, never {@code null}
     * @return the query, never {@code null}
     */
    static SchemaQuery compileExists(String name) {
        return new SchemaQuery(EXISTS_SQL, Collections.singletonList(name));
    }

    /**
     * Tells whether the server accepts a copy strategy.
     *
     * @param connection the connection to read the version with, never {@code null}
     * @return {@code true} on PostgreSQL 15 or later
     */
    static boolean supportsCopyStrategy(Connection connection) {
        return DatabaseVersion.atLeast(PostgresVersion.of(connection), STRATEGY_SINCE);
    }
}
