package org.aristonis.photisma.database.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    // Holds a requested index name until the blueprint is checked; never reaches a grammar.
    private static final String REQUESTED = "requestedName";

    private final String table;
    private final String prefix;
    private final boolean isCreating;
    private final List<ColumnDefinition> columns = new ArrayList<>();
    private final List<Command> commands = new ArrayList<>();
    private boolean isPrepared;

    Blueprint(String table, String prefix, boolean isCreating) {
        this.table = table;
        this.prefix = prefix;
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
     * Adds Laravel's {@code foreignId}: an unsigned big integer column, ready for
     * {@link ForeignIdColumnDefinition#constrained()}.
     *
     * @param column the column name, never {@code null}
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignIdColumnDefinition foreignId(String column) {
        ForeignIdColumnDefinition definition = new ForeignIdColumnDefinition(this, column);
        add(definition);
        return definition;
    }

    /**
     * Adds an index over columns, named {@code <table>_<columns>_index}.
     *
     * @param columns the columns in index order, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void index(String... columns) {
        index(list(columns), null);
    }

    /**
     * Adds an index over columns with a chosen name.
     *
     * @param columns the columns in index order, at least one, never {@code null}
     * @param name the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code columns} or a column name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void index(List<String> columns, String name) {
        indexCommand(Command.PLAIN_INDEX, columns, name);
    }

    /**
     * Adds a unique index over columns, named {@code <table>_<columns>_unique}.
     *
     * @param columns the columns in index order, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void unique(String... columns) {
        unique(list(columns), null);
    }

    /**
     * Adds a unique index over columns with a chosen name.
     *
     * @param columns the columns in index order, at least one, never {@code null}
     * @param name the index name, or {@code null} for Laravel's default
     * @throws NullPointerException if {@code columns} or a column name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void unique(List<String> columns, String name) {
        indexCommand(Command.UNIQUE, columns, name);
    }

    /**
     * Makes columns the table's primary key.
     *
     * @apiNote On SQLite, adding a primary key to an existing table rebuilds the table.
     * @param columns the key's columns in order, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void primary(String... columns) {
        primary(list(columns), null);
    }

    /**
     * Makes columns the table's primary key, with a chosen constraint name.
     *
     * @param columns the key's columns in order, at least one, never {@code null}
     * @param name the constraint name, or {@code null} for Laravel's default; SQLite ignores it
     * @throws NullPointerException if {@code columns} or a column name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void primary(List<String> columns, String name) {
        indexCommand(Command.PRIMARY, columns, name);
    }

    /**
     * Adds a foreign key from columns of this table; chain
     * {@link ForeignKeyDefinition#references(String...)} and {@link ForeignKeyDefinition#on(String)}.
     *
     * @apiNote On SQLite, adding a foreign key to an existing table rebuilds the table.
     * @param columns the referencing columns, at least one, never {@code null}
     * @return the foreign key, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition foreign(String... columns) {
        return foreign(list(columns), null);
    }

    /**
     * Adds a foreign key with a chosen name.
     *
     * @param columns the referencing columns, at least one, never {@code null}
     * @param name the key name, or {@code null} for Laravel's {@code <table>_<columns>_foreign}
     * @return the foreign key, never {@code null}
     * @throws NullPointerException if {@code columns} or a column name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignKeyDefinition foreign(List<String> columns, String name) {
        ensureOpen();
        ForeignKeyDefinition key = new ForeignKeyDefinition(Objects.requireNonNull(columns, "columns"), name);
        commands.add(new Command(Command.FOREIGN, single(Command.FOREIGN_KEY, key)));
        return key;
    }

    /**
     * Drops a plain index by name.
     *
     * @param name the index name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropIndex(String name) {
        dropByName(Command.DROP_INDEX, name);
    }

    /**
     * Drops the plain index Laravel names after these columns.
     *
     * @param columns the index's columns, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropIndex(List<String> columns) {
        dropByColumns(Command.DROP_INDEX, "index", columns);
    }

    /**
     * Drops a unique index by name.
     *
     * @param name the index name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropUnique(String name) {
        dropByName(Command.DROP_UNIQUE, name);
    }

    /**
     * Drops the unique index Laravel names after these columns.
     *
     * @param columns the index's columns, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropUnique(List<String> columns) {
        dropByColumns(Command.DROP_UNIQUE, "unique", columns);
    }

    /**
     * Drops the table's primary key.
     *
     * @apiNote On SQLite this rebuilds the table.
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropPrimary() {
        dropByName(Command.DROP_PRIMARY, defaultName("primary", Collections.<String>emptyList()));
    }

    /**
     * Drops the table's primary key by its constraint name.
     *
     * @param name the constraint name, never {@code null}; SQLite ignores it
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropPrimary(String name) {
        dropByName(Command.DROP_PRIMARY, name);
    }

    /**
     * Drops a foreign key by name.
     *
     * @apiNote SQLite keys have no names; there, use {@link #dropForeign(List)}.
     * @param name the key name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropForeign(String name) {
        dropByName(Command.DROP_FOREIGN, name);
    }

    /**
     * Drops the foreign key over these columns.
     *
     * @apiNote On SQLite this rebuilds the table.
     * @param columns the key's referencing columns, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropForeign(List<String> columns) {
        dropByColumns(Command.DROP_FOREIGN, "foreign", columns);
    }

    /**
     * Drops the foreign key on a column, then the column, as Laravel's
     * {@code dropConstrainedForeignId} does.
     *
     * @param column the column, never {@code null}
     * @throws NullPointerException if {@code column} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropConstrainedForeignId(String column) {
        dropForeign(Collections.singletonList(column));
        dropColumn(column);
    }

    /**
     * Renames an index.
     *
     * @param from the current name, never {@code null}
     * @param to the new name, never {@code null}
     * @throws NullPointerException if a name is {@code null}
     * @throws IllegalArgumentException if a name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void renameIndex(String from, String to) {
        rename(Command.RENAME_INDEX, "index", from, to);
    }

    /**
     * Drops columns.
     *
     * @apiNote A column still used by an index or foreign key is refused before any SQL runs;
     *     drop the index or key first.
     * @param columns the columns, at least one, never {@code null}
     * @throws NullPointerException if {@code columns} or a name is {@code null}
     * @throws IllegalArgumentException if no column is given, or a name is invalid or repeated
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropColumn(String... columns) {
        ensureOpen();
        List<String> checked = ForeignKeyDefinition.checkColumns(list(columns));
        commands.add(new Command(Command.DROP_COLUMN, single(Command.COLUMNS, checked)));
    }

    /**
     * Renames a column.
     *
     * @param from the current name, never {@code null}
     * @param to the new name, never {@code null}
     * @throws NullPointerException if a name is {@code null}
     * @throws IllegalArgumentException if a name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void renameColumn(String from, String to) {
        rename(Command.RENAME_COLUMN, "column", from, to);
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
     * <p>The final order is: create, the added columns, the explicit commands in the order they
     * were called, then the indexes and keys set on columns.
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
        List<Command> ordered = new ArrayList<>();
        int explicitStart = 0;
        if (!commands.isEmpty() && Command.CREATE.equals(commands.get(0).name())) {
            ordered.add(commands.get(0));
            explicitStart = 1;
        }
        for (ColumnDefinition column : columns) {
            column.freeze();
            if (!isCreating) {
                ordered.add(new Command(Command.ADD, single(Command.COLUMN, column)));
            }
        }
        Set<String> indexes = new HashSet<>();
        for (Command command : commands.subList(explicitStart, commands.size())) {
            ordered.add(settle(command, indexes, maxIdentifierLength));
        }
        for (ColumnDefinition column : columns) {
            List<String> own = Collections.singletonList(column.name());
            if (column.isPrimary()) {
                ordered.add(named(Command.PRIMARY, own, null, indexes, maxIdentifierLength));
            }
            if (column.isUnique()) {
                ordered.add(named(Command.UNIQUE, own, column.uniqueIndex().orElse(null), indexes, maxIdentifierLength));
            }
            if (column.isIndexed()) {
                ordered.add(named(Command.PLAIN_INDEX, own, column.plainIndex().orElse(null), indexes,
                        maxIdentifierLength));
            }
        }
        commands.clear();
        commands.addAll(ordered);
        isPrepared = true;
    }

    private Command settle(Command command, Set<String> indexes, int maxIdentifierLength) {
        switch (command.name()) {
            case Command.PLAIN_INDEX:
            case Command.UNIQUE:
            case Command.PRIMARY:
                if (command.has(Command.INDEX)) {
                    return command;
                }
                return named(command.name(), command.strings(Command.COLUMNS),
                        (String) command.get(REQUESTED).orElse(null), indexes, maxIdentifierLength);
            case Command.FOREIGN:
                ForeignKeyDefinition key = command.foreignKey();
                key.freeze(defaultName("foreign", key.columns()), prefix);
                checkLength(key.name(), key.requestedName() == null, maxIdentifierLength);
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put(Command.FOREIGN_KEY, key);
                attributes.put(Command.INDEX, key.name());
                attributes.put(Command.COLUMNS, key.columns());
                return new Command(Command.FOREIGN, attributes);
            default:
                return command;
        }
    }

    private Command named(String type, List<String> columns, String requested, Set<String> indexes,
            int maxIdentifierLength) {
        String name = requested != null ? requested : defaultName(type, columns);
        checkLength(name, requested == null, maxIdentifierLength);
        if (!indexes.add(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The same index name is used twice");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.INDEX, name);
        attributes.put(Command.COLUMNS, columns);
        return new Command(type, attributes);
    }

    private static String checkLength(String name, boolean isGenerated, int maxIdentifierLength) {
        if (name.length() <= maxIdentifierLength) {
            return name;
        }
        if (isGenerated) {
            throw new IllegalArgumentException("The generated index name is longer than " + maxIdentifierLength
                    + " characters; pass a name");
        }
        throw new IllegalArgumentException("An index name is longer than " + maxIdentifierLength + " characters");
    }

    // Laravel's createIndexName.
    private String defaultName(String type, List<String> columns) {
        StringBuilder name = new StringBuilder(table);
        for (String column : columns) {
            name.append('_').append(column);
        }
        name.append('_').append(type);
        return name.toString().toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private void indexCommand(String type, List<String> columns, String name) {
        ensureOpen();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.COLUMNS, ForeignKeyDefinition.checkColumns(Objects.requireNonNull(columns, "columns")));
        if (name != null) {
            attributes.put(REQUESTED, SchemaNames.check("index", name));
        }
        commands.add(new Command(type, attributes));
    }

    private void dropByName(String type, String name) {
        ensureOpen();
        commands.add(new Command(type, single(Command.INDEX, SchemaNames.check("index", name))));
    }

    private void dropByColumns(String type, String suffix, List<String> columns) {
        ensureOpen();
        List<String> checked = ForeignKeyDefinition.checkColumns(Objects.requireNonNull(columns, "columns"));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.INDEX, defaultName(suffix, checked));
        attributes.put(Command.COLUMNS, checked);
        commands.add(new Command(type, attributes));
    }

    private void rename(String type, String kind, String from, String to) {
        ensureOpen();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.FROM, SchemaNames.check(kind, from));
        attributes.put(Command.TO, SchemaNames.check(kind, to));
        commands.add(new Command(type, attributes));
    }

    private static List<String> list(String[] columns) {
        return Arrays.asList(Objects.requireNonNull(columns, "columns"));
    }

    private static Map<String, Object> single(String key, Object value) {
        return Collections.singletonMap(key, value);
    }

    private ColumnDefinition simple(String column, ColumnType type) {
        return add(new ColumnDefinition(column, type, 0, 0, 0));
    }

    private ColumnDefinition add(ColumnDefinition column) {
        ensureOpen();
        columns.add(column);
        return column;
    }

    private void ensureOpen() {
        if (isPrepared) {
            throw new IllegalStateException("The blueprint can no longer be changed");
        }
    }
}
