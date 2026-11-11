package org.aristonis.diwan.database.schema;

import org.aristonis.diwan.database.schema.grammar.Command;

import java.util.ArrayList;

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
public final class Blueprint extends BlueprintHelpers {

    Blueprint(String table, String prefix, boolean isCreating) {
        super(table, prefix, isCreating);
    }

    @Override
    Blueprint self() {
        return this;
    }

    /**
     * Makes the table a temporary table, which the database drops when the connection closes.
     *
     * @apiNote A temporary table lives on one connection: use it inside a transaction, or on a
     *     database that keeps a single connection.
     * @throws IllegalStateException if the blueprint changes an existing table, or can no longer
     *     be changed
     */
    public void temporary() {
        ensureOpen();
        if (!isCreating) {
            throw new IllegalStateException("temporary() applies only to Schema.create; table " + table
                    + " already exists");
        }
        isTemporary = true;
    }

    /**
     * Sets the comment of the table, on create or on change.
     *
     * @apiNote A grammar without table comments, such as SQLite's, writes nothing.
     * @param text the comment, never {@code null}; empty removes the comment where the database
     *     supports one
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void comment(String text) {
        ensureOpen();
        commands.add(new Command(Command.TABLE_COMMENT, single(Command.COMMENT, Objects.requireNonNull(text, "text"))));
    }

    /**
     * Adds the foreign column for a key, named by the source's {@link ForeignKeySource#foreignKey()}.
     *
     * @param source the key the column points at, never {@code null}
     * @return the column definition; {@link ForeignIdColumnDefinition#constrained()} adds the key
     *     to the source's table, never {@code null}
     * @throws NullPointerException if {@code source} or one of its values is {@code null}
     * @throws IllegalArgumentException if a name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignIdColumnDefinition foreignIdFor(ForeignKeySource source) {
        return foreignIdFor(source, null);
    }

    /**
     * Adds the foreign column for a key, as {@link #foreignIdFor(ForeignKeySource)} does, under a
     * chosen name. The column is an unsigned big integer, a UUID or a 26-character column by the
     * source's {@link ForeignKeySource#keyKind()}; no foreign key is added until
     * {@link ForeignIdColumnDefinition#constrained()} is called.
     *
     * @param source the key the column points at, never {@code null}
     * @param column the column name; {@code null} means the source's foreign key name
     * @return the column definition, never {@code null}
     * @throws NullPointerException if {@code source} or one of its values is {@code null}
     * @throws IllegalArgumentException if a name is empty or has a control character
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ForeignIdColumnDefinition foreignIdFor(ForeignKeySource source, String column) {
        Objects.requireNonNull(source, "source");
        String name = column != null ? column : Objects.requireNonNull(source.foreignKey(), "foreignKey");
        String sourceTable = Objects.requireNonNull(source.table(), "table");
        String sourceKey = Objects.requireNonNull(source.keyName(), "keyName");
        ForeignIdColumnDefinition definition;
        switch (Objects.requireNonNull(source.keyKind(), "keyKind")) {
            case INTEGER:
                definition = foreignId(name);
                break;
            case ULID:
                definition = foreignUlid(name);
                break;
            default:
                definition = foreignUuid(name);
                break;
        }
        definition.referencesModel(sourceTable, sourceKey);
        return definition;
    }

    /**
     * Checks the blueprint, adds the commands its columns imply and freezes it.
     *
     * <p>The final order is: create, the added and changed columns, the explicit commands in the
     * order they were called, the indexes dropped on changed columns, then the indexes and keys set
     * on columns.
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
                throw new IllegalArgumentException("The same column is added or changed twice");
            }
            checkChange(column);
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
                ordered.add(new Command(column.isChange() ? Command.CHANGE : Command.ADD,
                        single(Command.COLUMN, column)));
            }
        }
        Set<String> indexes = new HashSet<>();
        for (Command command : commands.subList(explicitStart, commands.size())) {
            ordered.add(settle(command, indexes, maxIdentifierLength));
        }
        // Drops first, so a primary key can move from one changed column to another in either order.
        for (ColumnDefinition column : columns) {
            addFluentDrops(ordered, column, Collections.singletonList(column.name()));
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

    void checkChange(ColumnDefinition column) {
        boolean dropsFluent = column.dropsUnique() || column.dropsIndex() || column.dropsPrimary();
        if (!column.isChange()) {
            if (dropsFluent) {
                throw new IllegalArgumentException("unique(false), index(false) and primary(false) need change() on"
                        + " column " + column.name());
            }
            return;
        }
        if (isCreating) {
            throw new IllegalArgumentException("change() applies only to an existing table; column " + column.name()
                    + " is in a new one");
        }
        if (column.isAutoIncrement()) {
            throw new IllegalArgumentException("A column cannot be changed to auto-increment: " + column.name());
        }
        for (Command command : commands) {
            if (Command.RENAME_COLUMN.equals(command.name())
                    && (command.string(Command.FROM).equalsIgnoreCase(column.name())
                    || command.string(Command.TO).equalsIgnoreCase(column.name()))) {
                // Whether the change means the old or the new name depends on order the blueprint does not keep.
                throw new IllegalArgumentException("Column " + column.name() + " is changed and renamed in one"
                        + " call; split them into two calls");
            }
        }
    }

    void addFluentDrops(List<Command> ordered, ColumnDefinition column, List<String> own) {
        if (column.dropsPrimary()) {
            ordered.add(new Command(Command.DROP_PRIMARY,
                    single(Command.INDEX, defaultName("primary", Collections.<String>emptyList()))));
        }
        if (column.dropsUnique()) {
            ordered.add(droppedByColumns(Command.DROP_UNIQUE, "unique", own));
        }
        if (column.dropsIndex()) {
            ordered.add(droppedByColumns(Command.DROP_INDEX, "index", own));
        }
    }

    Command settle(Command command, Set<String> indexes, int maxIdentifierLength) {
        switch (command.name()) {
            case Command.PLAIN_INDEX:
            case Command.UNIQUE:
            case Command.PRIMARY:
                if (command.has(Command.INDEX)) {
                    return command;
                }
                Command settled = named(command.name(), command.strings(Command.COLUMNS),
                        (String) command.get(REQUESTED).orElse(null), indexes, maxIdentifierLength);
                return withIndexParts(settled, command);
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

    // Freezes the definition and copies its non-empty parts, and the raw flag, onto the compiled command.
    private static Command withIndexParts(Command settled, Command original) {
        Object definitionValue = original.get(DEFINITION).orElse(null);
        if (!(definitionValue instanceof IndexDefinition)) {
            return settled;
        }
        IndexDefinition definition = (IndexDefinition) definitionValue;
        definition.freeze();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.INDEX, settled.string(Command.INDEX));
        attributes.put(Command.COLUMNS, settled.strings(Command.COLUMNS));
        if (original.has(Command.RAW)) {
            attributes.put(Command.RAW, Boolean.TRUE);
        }
        if (!definition.conditions().isEmpty()) {
            attributes.put(Command.WHERE, definition.conditions());
        }
        if (!definition.orders().isEmpty()) {
            attributes.put(Command.ORDERS, definition.orders());
        }
        if (!definition.collations().isEmpty()) {
            attributes.put(Command.COLLATIONS, definition.collations());
        }
        return new Command(settled.name(), attributes);
    }

    Command named(String type, List<String> columns, String requested, Set<String> indexes,
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

    // Counted in UTF-8 bytes, as PostgreSQL counts: a 40-letter Arabic name is 80 bytes and would
    // be cut silently, so a later drop by its full name would miss it.
    static String checkLength(String name, boolean isGenerated, int maxIdentifierLength) {
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxIdentifierLength) {
            return name;
        }
        if (isGenerated) {
            throw new IllegalArgumentException("The generated index name is longer than " + maxIdentifierLength
                    + " bytes (UTF-8); pass a name");
        }
        throw new IllegalArgumentException("An index name is longer than " + maxIdentifierLength + " bytes (UTF-8)");
    }
}
