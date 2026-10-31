package org.aristonis.diwan.database.migrations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Schema;

/**
 * Keeps the migrations table through a {@link Schema} and its connection.
 *
 * <p>The table has Laravel's layout: an auto-increment {@code id}, a unique {@code migration}
 * name of up to 191 characters and a {@code batch} number. The schema builder creates, checks and
 * drops it; reads and writes are plain SQL with every value sent as a binding, and only the checked
 * table name is part of the SQL text.
 *
 * <p>Instances are immutable and as safe to share between threads as the connection they use.
 *
 * <p>Tested against SQLite. The reads use {@code limit}, so SQL Server and Oracle are not supported
 * yet.
 *
 * @implNote Inside {@link Connection#pretend} nothing reaches the database, so reads return empty
 *     results and {@link #repositoryExists()} returns {@code false}. Read the repository outside
 *     the pretend callback.
 */
public final class DatabaseMigrationRepository implements MigrationRepository {

    /** The table name used when none is given, as in Laravel. */
    public static final String DEFAULT_TABLE = "migrations";

    private final Schema schema;
    private final Connection connection;
    private final String baseTable;
    private final MigrationTableSql sql;

    /**
     * Creates a repository on the {@value #DEFAULT_TABLE} table.
     *
     * @param schema the schema builder of the connection that holds the table, never {@code null};
     *     the connection's table prefix is added to the name
     * @throws NullPointerException if {@code schema} is {@code null}
     * @throws IllegalArgumentException if the prefixed name breaks the rule of
     *     {@link #DatabaseMigrationRepository(Schema, String)}
     */
    public DatabaseMigrationRepository(Schema schema) {
        this(schema, DEFAULT_TABLE);
    }

    /**
     * Creates a repository on a named table.
     *
     * @param schema the schema builder of the connection that holds the table, never {@code null};
     *     the connection's table prefix is added to the name
     * @param table the table name without prefix, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the prefixed name is not lowercase letters, digits and
     *     underscores starting with a letter or underscore, or is longer than 63 characters
     */
    public DatabaseMigrationRepository(Schema schema, String table) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.connection = schema.connection();
        this.baseTable = Objects.requireNonNull(table, "table");
        this.sql = new MigrationTableSql(MigrationNames.checkTable(connection.getTablePrefix() + table));
    }

    /**
     * Returns the schema builder the repository works through.
     *
     * @return the schema builder, never {@code null}
     */
    public Schema schema() {
        return schema;
    }

    /**
     * Returns the table name without the connection's prefix.
     *
     * @return the name, never {@code null}
     */
    public String baseTable() {
        return baseTable;
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
        schema.create(baseTable, table -> {
            table.id();
            table.string("migration", MigrationNames.MAX_NAME_LENGTH).unique();
            table.integer("batch");
        });
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
        return schema.hasTable(baseTable);
    }

    @Override
    public void deleteRepository() {
        schema.drop(baseTable);
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
