package org.aristonis.photisma.database.schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.aristonis.photisma.database.Connection;

/**
 * Refuses to drop a column an index or foreign key still uses, before any SQL runs.
 *
 * <p>Databases refuse it too, but late and with a message that does not say which index is in the
 * way. Indexes and keys the same blueprint drops earlier do not count.
 */
final class DropColumnGuard {

    private DropColumnGuard() {
    }

    static void check(Blueprint blueprint, SchemaGrammar grammar, Connection reader) {
        List<IndexInfo> indexes = null;
        List<ForeignKeyInfo> keys = null;
        Set<String> droppedIndexes = new HashSet<>();
        Set<List<String>> droppedKeys = new HashSet<>();
        boolean isPrimaryDropped = false;
        for (Command command : blueprint.commands()) {
            switch (command.name()) {
                case Command.DROP_INDEX:
                case Command.DROP_UNIQUE:
                    droppedIndexes.add(command.string(Command.INDEX).toLowerCase(Locale.ROOT));
                    break;
                case Command.DROP_PRIMARY:
                    isPrimaryDropped = true;
                    break;
                case Command.DROP_FOREIGN:
                    if (command.has(Command.COLUMNS)) {
                        droppedKeys.add(lower(command.strings(Command.COLUMNS)));
                    }
                    break;
                case Command.DROP_COLUMN:
                    if (indexes == null) {
                        indexes = grammar.indexes(reader, blueprint.table());
                        keys = grammar.foreignKeys(reader, blueprint.table());
                    }
                    for (String column : command.strings(Command.COLUMNS)) {
                        refuseIfIndexed(column, indexes, droppedIndexes, isPrimaryDropped);
                        refuseIfKeyed(column, keys, droppedKeys);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static void refuseIfIndexed(String column, List<IndexInfo> indexes, Set<String> dropped,
            boolean isPrimaryDropped) {
        for (IndexInfo index : indexes) {
            boolean isGone = index.isPrimary() ? isPrimaryDropped
                    : dropped.contains(index.name().toLowerCase(Locale.ROOT));
            if (!isGone && lower(index.columns()).contains(column.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Column " + column + " is used by "
                        + (index.isPrimary() ? "the primary key" : "index " + index.name())
                        + "; drop it before the column");
            }
        }
    }

    private static void refuseIfKeyed(String column, List<ForeignKeyInfo> keys, Set<List<String>> dropped) {
        for (ForeignKeyInfo key : keys) {
            List<String> columns = lower(key.columns());
            if (!dropped.contains(columns) && columns.contains(column.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Column " + column + " is used by the foreign key on "
                        + key.columns() + "; drop it before the column");
            }
        }
    }

    private static List<String> lower(List<String> names) {
        List<String> lowered = new ArrayList<>(names.size());
        for (String name : names) {
            lowered.add(name.toLowerCase(Locale.ROOT));
        }
        return lowered;
    }
}
