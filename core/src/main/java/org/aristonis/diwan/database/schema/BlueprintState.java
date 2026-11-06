package org.aristonis.diwan.database.schema;

import org.aristonis.diwan.database.schema.grammar.Command;

import java.util.ArrayList;

import java.util.Arrays;

import java.util.Collections;

import java.util.HashSet;

import java.util.List;

import java.util.Map;

import java.util.Objects;

import java.util.Set;

/** The state every part of a {@link Blueprint} shares: the table, its columns and commands, and the checks they all use. */
abstract class BlueprintState {

    /** Laravel's default length of a {@code string} column. */
    public static final int DEFAULT_STRING_LENGTH = 255;
    /** Laravel's default total digits of a {@code decimal} column. */
    public static final int DEFAULT_DECIMAL_TOTAL = 8;
    /** Laravel's default digits after the point of a {@code decimal} column. */
    public static final int DEFAULT_DECIMAL_PLACES = 2;

    /** Laravel's default precision of a {@code float} column, in binary digits. */
    public static final int DEFAULT_FLOAT_PRECISION = 53;
    /** The largest {@code float} precision, in binary digits. */
    public static final int MAX_FLOAT_PRECISION = 53;
    /** The largest fractional-second precision of a time or timestamp column. */
    public static final int MAX_TIME_PRECISION = 6;
    /** The length of a ULID, which Laravel stores as {@code char(26)}. */
    public static final int ULID_LENGTH = 26;
    /** The length of Laravel's {@code remember_token} column. */
    public static final int REMEMBER_TOKEN_LENGTH = 100;

    enum MorphKey { NUMERIC, UUID, ULID }

    // Holds a requested index name until the blueprint is checked; never reaches a grammar.
    static final String REQUESTED = "requestedName";

    final String table;
    final String prefix;
    final boolean isCreating;
    final List<ColumnDefinition> columns = new ArrayList<>();
    final List<Command> commands = new ArrayList<>();
    boolean isPrepared;

    /** The blueprint itself, typed as the public class. */
    abstract Blueprint self();

    BlueprintState(String table, String prefix, boolean isCreating) {
        this.table = table;
        this.prefix = prefix;
        this.isCreating = isCreating;
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

    static int checkTimePrecision(int precision) {
        if (precision < 0 || precision > MAX_TIME_PRECISION) {
            throw new IllegalArgumentException("Time precision must be from 0 to " + MAX_TIME_PRECISION);
        }
        return precision;
    }

    static List<String> checkAllowed(List<String> values) {
        List<String> allowed = new ArrayList<>(Objects.requireNonNull(values, "allowed"));
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed value is needed");
        }
        Set<String> seen = new HashSet<>();
        for (String value : allowed) {
            if (value == null) {
                throw new IllegalArgumentException("An allowed value is null");
            }
            if (!seen.add(value)) {
                throw new IllegalArgumentException("An allowed value is listed twice");
            }
        }
        return allowed;
    }

    static List<String> list(String[] columns) {
        return Arrays.asList(Objects.requireNonNull(columns, "columns"));
    }

    static Map<String, Object> single(String key, Object value) {
        return Collections.singletonMap(key, value);
    }

    ColumnDefinition simple(String column, ColumnType type) {
        return add(new ColumnDefinition(column, type, 0, 0, 0));
    }

    ColumnDefinition add(ColumnDefinition column) {
        ensureOpen();
        columns.add(column);
        return column;
    }

    void ensureOpen() {
        if (isPrepared) {
            throw new IllegalStateException("The blueprint can no longer be changed");
        }
    }
}
