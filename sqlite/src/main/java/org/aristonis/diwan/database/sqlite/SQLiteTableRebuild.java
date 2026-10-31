package org.aristonis.diwan.database.sqlite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Blueprint;
import org.aristonis.diwan.database.schema.ColumnDefinition;
import org.aristonis.diwan.database.schema.ColumnType;
import org.aristonis.diwan.database.schema.Command;
import org.aristonis.diwan.database.schema.ForeignKeyAction;
import org.aristonis.diwan.database.schema.ForeignKeyDefinition;
import org.aristonis.diwan.database.schema.ForeignKeysOffRequiredException;

/**
 * Compiles the commands of one blueprint that changes an existing SQLite table, rebuilding the
 * table when SQLite cannot make a change in place.
 *
 * <p>Commands are handled in order against a {@link SQLiteTableState}. Until the first change that
 * needs a rebuild, each command that SQLite can run in place becomes its own statement. From then
 * on, every command only updates the state, and one rebuild at the end carries the whole result:
 * the new table, the copied rows, the indexes and the auto-increment counter.
 */
final class SQLiteTableRebuild {

    private static final String TEMP_PREFIX = "__temp__";

    private final SQLiteSchemaGrammar grammar;
    private final Blueprint blueprint;
    private final Connection connection;
    private final SQLiteTableState state;
    private final List<String> statements = new ArrayList<>();
    private boolean isRebuildPending;

    SQLiteTableRebuild(SQLiteSchemaGrammar grammar, Blueprint blueprint, Connection connection) {
        this.grammar = grammar;
        this.blueprint = blueprint;
        this.connection = connection;
        this.state = SQLiteTableState.load(connection, blueprint.table(), grammar);
    }

    List<String> compile() {
        for (Command command : blueprint.commands()) {
            apply(command);
        }
        if (isRebuildPending) {
            refuseReferencedInsideTransaction();
            statements.addAll(rebuild());
        }
        return Collections.unmodifiableList(statements);
    }

    private void apply(Command command) {
        switch (command.name()) {
            case Command.ADD:
                ColumnDefinition column = command.column();
                boolean isEnum = column.type() == ColumnType.ENUM;
                state.addColumn(column.name(), isEnum ? "varchar" : grammar.typeOf(column), column.allowed(),
                        grammar.modifiersOf(blueprint, column), !isRebuildPending);
                inPlace(grammar.compileOne(blueprint, command, connection));
                break;
            case Command.CHANGE:
                ColumnDefinition changed = command.column();
                state.changeColumn(changed.name(), changed.type() == ColumnType.ENUM ? "varchar"
                        : grammar.typeOf(changed), changed.allowed(), grammar.modifiersOf(blueprint, changed));
                isRebuildPending = true;
                break;
            case Command.PLAIN_INDEX:
            case Command.UNIQUE:
                state.addIndex(command.string(Command.INDEX), command.strings(Command.COLUMNS),
                        Command.UNIQUE.equals(command.name()));
                inPlace(grammar.compileOne(blueprint, command, connection));
                break;
            case Command.DROP_INDEX:
            case Command.DROP_UNIQUE:
                state.dropIndex(command.string(Command.INDEX));
                inPlace(grammar.compileOne(blueprint, command, connection));
                break;
            case Command.RENAME_INDEX:
                renameIndex(command.string(Command.FROM), command.string(Command.TO));
                break;
            case Command.RENAME_COLUMN:
                renameColumn(command.string(Command.FROM), command.string(Command.TO));
                break;
            case Command.DROP_COLUMN:
                dropColumns(command.strings(Command.COLUMNS));
                break;
            case Command.PRIMARY:
                state.setPrimaryKey(command.strings(Command.COLUMNS));
                isRebuildPending = true;
                break;
            case Command.DROP_PRIMARY:
                state.dropPrimaryKey();
                isRebuildPending = true;
                break;
            case Command.FOREIGN:
                state.addKey(key(command.foreignKey()));
                isRebuildPending = true;
                break;
            case Command.DROP_FOREIGN:
                if (!command.has(Command.COLUMNS)) {
                    throw new UnsupportedOperationException("SQLite foreign keys have no names; drop the key by its"
                            + " columns with dropForeign(List)");
                }
                state.dropKey(command.strings(Command.COLUMNS));
                isRebuildPending = true;
                break;
            default:
                inPlace(grammar.compileOne(blueprint, command, connection));
                break;
        }
    }

    private void inPlace(List<String> sql) {
        if (!isRebuildPending) {
            statements.addAll(sql);
        }
    }

    private void renameIndex(String from, String to) {
        SQLiteTableState.Index index = state.requireIndex(from);
        index.name = to;
        inPlace(Arrays.asList("drop index " + grammar.quote(from), createIndex(index)));
    }

    private void renameColumn(String from, String to) {
        boolean isNative = !isRebuildPending && grammar.supportsRenameColumn(connection);
        if (!isNative) {
            refuseIfReferenced(from);
        }
        state.renameColumn(from, to, isNative);
        if (isNative) {
            statements.add("alter table " + table() + " rename column " + grammar.quote(from) + " to "
                    + grammar.quote(to));
        } else {
            isRebuildPending = true;
        }
    }

    private void dropColumns(List<String> names) {
        boolean isNative = !isRebuildPending && grammar.supportsDropColumn(connection);
        for (String name : names) {
            if (!isNative) {
                refuseIfReferenced(name);
            }
            state.dropColumn(name);
            if (isNative) {
                statements.add("alter table " + table() + " drop column " + grammar.quote(name));
            }
        }
        if (!isNative) {
            isRebuildPending = true;
        }
    }

    // A rebuild rewrites only this table, so other tables' keys would still name the old column.
    private void refuseIfReferenced(String column) {
        if (grammar.referencedColumns(connection, state.table).contains(column.toLowerCase(Locale.ROOT))) {
            throw new UnsupportedOperationException("Column " + column + " of table " + state.table + " is referenced"
                    + " by another table's foreign key; this SQLite version can only change it by a rebuild, which"
                    + " would leave that key pointing at the old name");
        }
    }

    private static SQLiteTableState.Key key(ForeignKeyDefinition definition) {
        return new SQLiteTableState.Key(new ArrayList<>(definition.columns()), definition.referencedTable(),
                new ArrayList<>(definition.referencedColumns()),
                definition.updateAction().map(ForeignKeyAction::sql).orElse(null),
                definition.deleteAction().map(ForeignKeyAction::sql).orElse(null));
    }

    // With enforcement on, dropping a table that others point at applies their delete actions, and
    // SQLite ignores the switch inside a transaction, so the rebuild would delete or null their rows.
    private void refuseReferencedInsideTransaction() {
        if (connection.transactionLevel() == 0 || !grammar.isForeignKeysOn(connection)) {
            return;
        }
        if (!grammar.referencingTables(connection, state.table).isEmpty()) {
            throw new ForeignKeysOffRequiredException("Table " + state.table + " must be rebuilt, and other tables point at"
                    + " it; inside a transaction with foreign keys on, SQLite would apply their delete actions."
                    + " Run the change outside the transaction, through Schema.withoutForeignKeyConstraints, or in a"
                    + " migration");
        }
    }

    private List<String> rebuild() {
        String temp = grammar.quote(TEMP_PREFIX + state.table);
        List<String> sql = new ArrayList<>();
        sql.add("create table " + temp + " (" + definitions() + ")");
        StringBuilder targets = new StringBuilder();
        StringBuilder sources = new StringBuilder();
        for (SQLiteTableState.Column column : state.columns) {
            if (column.source == null) {
                continue;
            }
            if (targets.length() > 0) {
                targets.append(", ");
                sources.append(", ");
            }
            targets.append(grammar.quote(column.name));
            sources.append(grammar.quote(column.source));
        }
        sql.add("insert into " + temp + " (" + targets + ") select " + sources + " from " + table());
        sql.add("drop table " + table());
        sql.add("alter table " + temp + " rename to " + table());
        for (SQLiteTableState.Index index : state.indexes) {
            sql.add(createIndex(index));
        }
        if (state.autoIncrementColumn != null && state.sequence >= 0) {
            sql.addAll(restoreSequence());
        }
        state.settle();
        return sql;
    }

    private String definitions() {
        StringBuilder sql = new StringBuilder();
        for (SQLiteTableState.Column column : state.columns) {
            separate(sql).append(grammar.quote(column.name));
            if (!column.type.isEmpty()) {
                sql.append(' ').append(column.type);
            }
            if (!column.allowed.isEmpty()) {
                sql.append(' ').append(grammar.enumCheck(column.name, column.allowed));
            }
            if (column.name.equalsIgnoreCase(state.autoIncrementColumn == null ? "" : state.autoIncrementColumn)) {
                sql.append(" primary key autoincrement");
            }
            sql.append(column.rest);
        }
        if (!state.primaryKey.isEmpty()) {
            separate(sql).append("primary key (").append(columnize(state.primaryKey)).append(')');
        }
        for (List<String> unique : state.uniqueConstraints) {
            separate(sql).append("unique (").append(columnize(unique)).append(')');
        }
        for (SQLiteTableState.Key key : state.keys) {
            separate(sql).append(grammar.foreignKeySql(key.columns, key.table, key.references, key.onDelete,
                    key.onUpdate));
        }
        return sql.toString();
    }

    // The drop deletes the table's counter row; without this, ids of deleted rows above the highest
    // remaining one would be handed out again.
    private List<String> restoreSequence() {
        String name = "'" + state.table.replace("'", "''") + "'";
        List<String> sql = new ArrayList<>(2);
        sql.add("update sqlite_sequence set seq = " + state.sequence + " where name = " + name + " and seq < "
                + state.sequence);
        sql.add("insert into sqlite_sequence (name, seq) select " + name + ", " + state.sequence
                + " where not exists (select 1 from sqlite_sequence where name = " + name + ")");
        return sql;
    }

    private String createIndex(SQLiteTableState.Index index) {
        return "create " + (index.isUnique ? "unique " : "") + "index " + grammar.quote(index.name) + " on " + table()
                + " (" + columnize(index.columns) + ")";
    }

    private String columnize(List<String> names) {
        StringBuilder sql = new StringBuilder();
        for (String name : names) {
            separate(sql).append(grammar.quote(name));
        }
        return sql.toString();
    }

    private static StringBuilder separate(StringBuilder sql) {
        if (sql.length() > 0) {
            sql.append(", ");
        }
        return sql;
    }

    private String table() {
        return grammar.quote(state.table);
    }
}
