package org.aristonis.photisma.database.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Describes the columns and commands for one table, inside a {@link Schema} callback.
 *
 * <p>Column methods use Laravel's names and defaults. Each returns the new
 * {@link ColumnDefinition} for chaining modifiers.
 *
 * <p>A blueprint belongs to the callback it is handed to: it is not thread-safe, and it can no
 * longer be changed once the callback returns.
 */
public final class Blueprint {

    /** Laravel's default length of a {@code string} column. */
    public static final int DEFAULT_STRING_LENGTH = 255;
    /** Laravel's default total digits of a {@code decimal} column. */
    public static final int DEFAULT_DECIMAL_TOTAL = 8;
    /** Laravel's default digits after the point of a {@code decimal} column. */
    public static final int DEFAULT_DECIMAL_PLACES = 2;

    private final String table;
    private final boolean isCreating;
    private final List<ColumnDefinition> columns = new ArrayList<>();
    private final List<Command> commands = new ArrayList<>();
    private boolean isPrepared;

    Blueprint(String table, boolean isCreating) {
        this.table = table;
        this.isCreating = isCreating;
    }

    /**
     * Adds Laravel's {@code id}: an auto-increment big integer primary key named {@code id}.
     *
     * @return the column definition, never {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition id() {
        return id("id");
    }

    /**
     * Adds an auto-increment big integer primary key.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition id(String column) {
        return bigInteger(column).unsigned().autoIncrement();
    }

    /**
     * Adds a text column of at most {@value #DEFAULT_STRING_LENGTH} characters.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition string(String column) {
        return string(column, DEFAULT_STRING_LENGTH);
    }

    /**
     * Adds a text column with a maximum length.
     *
     * @param column the column name, never {@code null}
     * @param length the maximum length, at least 1
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code length} is below 1
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition string(String column, int length) {
        if (length < 1) {
            throw new IllegalArgumentException("String length must be at least 1");
        }
        return add(new ColumnDefinition(column, ColumnType.STRING, length, 0, 0));
    }

    /**
     * Adds a long text column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition text(String column) {
        return simple(column, ColumnType.TEXT);
    }

    /**
     * Adds a 32-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition integer(String column) {
        return simple(column, ColumnType.INTEGER);
    }

    /**
     * Adds a 64-bit integer column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition bigInteger(String column) {
        return simple(column, ColumnType.BIG_INTEGER);
    }

    /**
     * Adds a true or false column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition booleanColumn(String column) {
        return simple(column, ColumnType.BOOLEAN);
    }

    /**
     * Adds an exact number column with {@value #DEFAULT_DECIMAL_TOTAL} digits,
     * {@value #DEFAULT_DECIMAL_PLACES} of them after the point.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition decimal(String column) {
        return decimal(column, DEFAULT_DECIMAL_TOTAL, DEFAULT_DECIMAL_PLACES);
    }

    /**
     * Adds an exact number column.
     *
     * @param column the column name, never {@code null}
     * @param total the total number of digits, at least 1
     * @param places the digits after the point, from 0 to {@code total}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or the digits are out of range
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition decimal(String column, int total, int places) {
        if (total < 1 || places < 0 || places > total) {
            throw new IllegalArgumentException("Decimal needs total >= 1 and 0 <= places <= total");
        }
        return add(new ColumnDefinition(column, ColumnType.DECIMAL, 0, total, places));
    }

    /**
     * Adds a date column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition date(String column) {
        return simple(column, ColumnType.DATE);
    }

    /**
     * Adds a date and time column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition timestamp(String column) {
        return simple(column, ColumnType.TIMESTAMP);
    }

    /**
     * Adds a JSON column.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition json(String column) {
        return simple(column, ColumnType.JSON);
    }

    /**
     * Adds Laravel's nullable {@code created_at} and {@code updated_at} timestamp columns.
     *
     * @return the two definitions, in that order, as an unmodifiable list
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public List<ColumnDefinition> timestamps() {
        List<ColumnDefinition> added = new ArrayList<>(2);
        added.add(timestamp("created_at").nullable());
        added.add(timestamp("updated_at").nullable());
        return Collections.unmodifiableList(added);
    }

    /**
     * Returns the table name, with the connection's prefix.
     *
     * @return the name, never {@code null}
     */
    public String table() {
        return table;
    }

    /**
     * Tells whether this blueprint creates its table.
     *
     * @return {@code true} for {@link Schema#create}, {@code false} for {@link Schema#table}
     */
    public boolean isCreating() {
        return isCreating;
    }

    /**
     * Returns the columns in the order they were added.
     *
     * @return an unmodifiable list, never {@code null}
     */
    public List<ColumnDefinition> columns() {
        return Collections.unmodifiableList(columns);
    }

    /**
     * Returns the commands in the order a grammar compiles them.
     *
     * @return an unmodifiable list, never {@code null}; complete only once the schema builder has
     *     checked the blueprint
     */
    public List<Command> commands() {
        return Collections.unmodifiableList(commands);
    }

    void addCommand(String name, Map<String, ?> attributes) {
        commands.add(new Command(name, attributes));
    }

    /**
     * Checks the blueprint, adds the commands its columns imply and freezes it.
     *
     * @param maxIdentifierLength the grammar's longest identifier
     * @throws IllegalArgumentException if the blueprint is invalid
     */
    void prepare(int maxIdentifierLength) {
        if (isPrepared) {
            return;
        }
        if (isCreating && columns.isEmpty()) {
            throw new IllegalArgumentException("A table needs at least one column");
        }
        Set<String> seen = new HashSet<>();
        for (ColumnDefinition column : columns) {
            if (!seen.add(column.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The same column is added twice");
            }
        }
        for (ColumnDefinition column : columns) {
            column.freeze();
            if (!isCreating) {
                commands.add(new Command(Command.ADD, single(Command.COLUMN, column)));
            }
        }
        Set<String> indexes = new HashSet<>();
        for (ColumnDefinition column : columns) {
            if (column.isUnique()) {
                String index = indexName(column, maxIdentifierLength);
                if (!indexes.add(index.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("The same index name is used twice");
                }
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put(Command.INDEX, index);
                attributes.put(Command.COLUMNS, Collections.singletonList(column.name()));
                commands.add(new Command(Command.UNIQUE, attributes));
            }
        }
        isPrepared = true;
    }

    private String indexName(ColumnDefinition column, int maxIdentifierLength) {
        if (column.uniqueIndex().isPresent()) {
            String name = column.uniqueIndex().get();
            if (name.length() > maxIdentifierLength) {
                throw new IllegalArgumentException("An index name is longer than " + maxIdentifierLength + " characters");
            }
            return name;
        }
        String name = (table + "_" + column.name() + "_unique").toLowerCase(Locale.ROOT)
                .replace('-', '_').replace('.', '_');
        if (name.length() > maxIdentifierLength) {
            throw new IllegalArgumentException("The generated index name is longer than " + maxIdentifierLength
                    + " characters; pass a name to unique(name)");
        }
        return name;
    }

    private static Map<String, Object> single(String key, Object value) {
        return Collections.singletonMap(key, value);
    }

    private ColumnDefinition simple(String column, ColumnType type) {
        return add(new ColumnDefinition(column, type, 0, 0, 0));
    }

    private ColumnDefinition add(ColumnDefinition column) {
        if (isPrepared) {
            throw new IllegalStateException("The blueprint can no longer be changed");
        }
        columns.add(column);
        return column;
    }
}
