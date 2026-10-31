package org.aristonis.photisma.database.migrations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.photisma.database.Connection;
import org.aristonis.photisma.database.driver.DatabaseFamily;

/**
 * Keeps the migrations table through a {@link Connection}, with plain SQL that runs on SQLite,
 * PostgreSQL and MySQL.
 *
 * <p>The table has two columns: {@code migration}, the name, which is the primary key, and
 * {@code batch}. Every value is sent as a binding; only the checked table name is part of the SQL
 * text.
 *
 * <p>Instances are immutable and as safe to share between threads as the connection they use.
 *
 * <p>Tested against SQLite. The SQL is written for PostgreSQL and MySQL as well; SQL Server and
 * Oracle are not supported yet, because they lack {@code limit} and the table check knows only the
 * three databases above.
 *
 * @implNote Inside {@link Connection#pretend} nothing reaches the database, so reads return empty
 *     results and {@link #repositoryExists()} returns {@code false}. Read the repository outside
 *     the pretend callback.
 */
public final class DatabaseMigrationRepository implements MigrationRepository {

    /** The table name used when none is given, as in Laravel. */
    public static final String DEFAULT_TABLE = "migrations";

    private final Connection connection;
    private final MigrationTableSql sql;

    /**
     * Creates a repository on the {@value #DEFAULT_TABLE} table.
     *
     * @param connection the connection, never {@code null}; its table prefix is added to the name
     * @throws NullPointerException if {@code connection} is {@code null}
     * @throws IllegalArgumentException if the prefixed name breaks the rule of
     *     {@link #DatabaseMigrationRepository(Connection, String)}
     */
    public DatabaseMigrationRepository(Connection connection) {
        this(connection, DEFAULT_TABLE);
    }

    /**
     * Creates a repository on a named table.
     *
     * @param connection the connection, never {@code null}; its table prefix is added to the name
     * @param table the table name without prefix, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the prefixed name is not lowercase letters, digits and
     *     underscores starting with a letter or underscore, or is longer than 63 characters
     */
    public DatabaseMigrationRepository(Connection connection, String table) {
        this.connection = Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(table, "table");
        this.sql = new MigrationTableSql(MigrationNames.checkTable(connection.getTablePrefix() + table));
    }

    /**
     * Returns the full table name, prefix included.
     *
     * @return the table name, never {@code null}
     */
    public String table() {
        return sql.table();
    }

    @Override
    public List<String> getRan() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : connection.select(sql.ran())) {
            names.add(text(column(row, 0)));
        }
        return Collections.unmodifiableList(names);
    }

    @Override
    public List<MigrationEntry> getMigrations(int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("Steps must be at least 1");
        }
        return entries(connection.select(sql.latest(), steps));
    }

    @Override
    public List<MigrationEntry> getMigrationsByBatch(int batch) {
        return entries(connection.select(sql.byBatch(), batch));
    }

    @Override
    public List<MigrationEntry> getLast() {
        return entries(connection.select(sql.last()));
    }

    @Override
    public Map<String, Integer> getMigrationBatches() {
        Map<String, Integer> batches = new LinkedHashMap<>();
        for (Map<String, Object> row : connection.select(sql.ranWithBatches())) {
            batches.put(text(column(row, 0)), intValue(column(row, 1)));
        }
        return Collections.unmodifiableMap(batches);
    }

    @Override
    public void log(String name, int batch) {
        MigrationEntry entry = new MigrationEntry(name, batch);
        connection.insert(sql.log(), entry.name(), entry.batch());
    }

    @Override
    public void delete(String name) {
        connection.delete(sql.delete(), Objects.requireNonNull(name, "name"));
    }

    @Override
    public int getLastBatchNumber() {
        return connection.scalar(sql.lastBatch()).map(DatabaseMigrationRepository::intValue).orElse(0);
    }

    @Override
    public int getNextBatchNumber() {
        return getLastBatchNumber() + 1;
    }

    @Override
    public void createRepository() {
        connection.statement(sql.create());
    }

    /**
     * {@inheritDoc}
     *
     * @implNote The first call on a connection may borrow one extra database connection to read the
     *     database's name. With a pool of one connection, make that first call outside a
     *     transaction.
     */
    @Override
    public boolean repositoryExists() {
        String query = MigrationTableSql.exists(DatabaseFamily.of(connection.getDriverInfo()));
        return connection.scalar(query, sql.table()).map(DatabaseMigrationRepository::intValue).orElse(0) > 0;
    }

    @Override
    public void deleteRepository() {
        connection.statement(sql.drop());
    }

    private static List<MigrationEntry> entries(List<Map<String, Object>> rows) {
        List<MigrationEntry> entries = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            entries.add(new MigrationEntry(text(column(row, 0)), intValue(column(row, 1))));
        }
        return Collections.unmodifiableList(entries);
    }

    // Read by position: some databases report column labels in upper case, and this class writes
    // every select list itself.
    private static Object column(Map<String, Object> row, int index) {
        int i = 0;
        for (Object value : row.values()) {
            if (i++ == index) {
                return value;
            }
        }
        throw new IllegalStateException("The migrations table returned fewer columns than selected");
    }

    private static String text(Object value) {
        if (value == null) {
            throw new IllegalStateException("The migrations table returned an empty migration name");
        }
        return value.toString();
    }

    // Drivers return integers as Integer, Long or BigDecimal, and a cursor-based driver may return
    // text, depending on the database.
    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("The migrations table returned a batch that is not a number", e);
            }
        }
        throw new IllegalStateException("The migrations table returned a batch that is not a number");
    }
}
