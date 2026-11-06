package org.aristonis.diwan.database.schema;

import org.aristonis.diwan.database.Expression;

import org.aristonis.diwan.database.schema.grammar.Command;

import org.aristonis.diwan.database.schema.internal.SchemaNames;

import java.util.Collections;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Locale;

import java.util.Map;

import java.util.Objects;

/** The index, key, check, drop and rename commands of a {@link Blueprint}. */
abstract class BlueprintKeys extends BlueprintState {

    BlueprintKeys(String table, String prefix, boolean isCreating) {
        super(table, prefix, isCreating);
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
        ForeignIdColumnDefinition definition = new ForeignIdColumnDefinition(self(), column);
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
     * Adds a column whose type and everything after it is SQL the caller writes, for what the
     * builder cannot say.
     *
     * <p>The SQL is written as given, like an {@link Expression}: build it from constants only, never from user input.
     * It says the column's nullability and default itself; {@code nullable()} and
     * {@code defaultValue()} add nothing, and only {@code check()} is written after it. On SQLite
     * it cannot hold COLLATE, which a later table rebuild could not carry, and on an existing table
     * it cannot hold a check or generated expression.
     *
     * @param column the column name, never {@code null}
     * @param sql the definition after the name, such as {@code "integer not null default 0"},
     *     never {@code null} or blank
     * @return the column definition, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code sql} is blank
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public ColumnDefinition rawColumn(String column, String sql) {
        ColumnDefinition definition = new ColumnDefinition(column, ColumnType.RAW, 0, 0, 0);
        definition.rawDefinition(sql);
        return add(definition);
    }

    /**
     * Adds a named {@code CHECK} constraint on the table.
     *
     * <p>The SQL is written as given, like an {@link Expression}: build it from constants only, never from user input.
     *
     * @param name the constraint name, never {@code null}; {@link #dropCheck(String)} drops it by this
     *     name
     * @param sql the condition, without the {@code check} keyword or outer parentheses, never
     *     {@code null} or blank
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code sql} is blank
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void check(String name, String sql) {
        ensureOpen();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.NAME, SchemaNames.check("check", name));
        attributes.put(Command.SQL, ColumnDefinition.trustedSql("check", sql));
        commands.add(new Command(Command.CHECK, attributes));
    }

    /**
     * Drops a named table {@code CHECK} constraint.
     *
     * @param name the constraint name, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is invalid
     * @throws IllegalStateException if the blueprint can no longer be changed
     */
    public void dropCheck(String name) {
        ensureOpen();
        commands.add(new Command(Command.DROP_CHECK, single(Command.NAME, SchemaNames.check("check", name))));
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

    // Laravel's createIndexName.
    String defaultName(String type, List<String> columns) {
        StringBuilder name = new StringBuilder(table);
        for (String column : columns) {
            name.append('_').append(column);
        }
        name.append('_').append(type);
        return name.toString().toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    void indexCommand(String type, List<String> columns, String name) {
        ensureOpen();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.COLUMNS, ForeignKeyDefinition.checkColumns(Objects.requireNonNull(columns, "columns")));
        if (name != null) {
            attributes.put(REQUESTED, SchemaNames.check("index", name));
        }
        commands.add(new Command(type, attributes));
    }

    void dropByName(String type, String name) {
        ensureOpen();
        commands.add(new Command(type, single(Command.INDEX, SchemaNames.check("index", name))));
    }

    void dropByColumns(String type, String suffix, List<String> columns) {
        ensureOpen();
        List<String> checked = ForeignKeyDefinition.checkColumns(Objects.requireNonNull(columns, "columns"));
        commands.add(droppedByColumns(type, suffix, checked));
    }

    void rename(String type, String kind, String from, String to) {
        ensureOpen();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.FROM, SchemaNames.check(kind, from));
        attributes.put(Command.TO, SchemaNames.check(kind, to));
        commands.add(new Command(type, attributes));
    }

    Command droppedByColumns(String type, String suffix, List<String> columns) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.INDEX, defaultName(suffix, columns));
        attributes.put(Command.COLUMNS, columns);
        return new Command(type, attributes);
    }
}
