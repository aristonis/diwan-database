package org.aristonis.diwan.database.sqlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.Connection;

/**
 * Orders tables so that each comes before the listed tables it refers to, read from
 * {@code pragma_foreign_key_list}: the order in which rows can be deleted, or tables dropped,
 * without a foreign key stopping the next statement. References to tables outside the list and
 * self-references are ignored.
 */
final class SQLiteForeignKeyOrder {

    private static final String MAIN = "main";

    private SQLiteForeignKeyOrder() {
    }

    /** One table: its real name, and its schema or {@code null} for the default one. */
    static final class Ref {
        final String schema;
        final String name;

        Ref(String schema, String name) {
            this.schema = schema;
            this.name = name;
        }

        String schemaOrMain() {
            return schema == null ? MAIN : schema;
        }
    }

    /**
     * Returns the positions of the tables in an order where every table precedes the listed tables
     * it refers to.
     *
     * @param tables the tables, never {@code null}
     * @param connection the connection the tables live on, never {@code null}
     * @return the positions of {@code tables} in that order, or {@code null} when the tables refer to
     *     each other in a cycle
     */
    static List<Integer> childrenFirst(List<Ref> tables, Connection connection) {
        int size = tables.size();
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            indexes.put(key(tables.get(i).schemaOrMain(), tables.get(i).name), i);
        }
        List<Set<Integer>> parents = new ArrayList<>();
        int[] childCount = new int[size];
        for (int i = 0; i < size; i++) {
            Set<Integer> found = new LinkedHashSet<>();
            Ref child = tables.get(i);
            for (String parent : referencedTables(child, connection)) {
                Integer index = indexes.get(key(child.schemaOrMain(), parent));
                if (index != null && index != i && found.add(index)) {
                    childCount[index]++;
                }
            }
            parents.add(found);
        }
        List<Integer> ordered = new ArrayList<>(size);
        boolean[] isDone = new boolean[size];
        boolean isProgress = true;
        while (isProgress) {
            isProgress = false;
            for (int i = 0; i < size; i++) {
                if (!isDone[i] && childCount[i] == 0) {
                    isDone[i] = true;
                    isProgress = true;
                    ordered.add(i);
                    for (int parent : parents.get(i)) {
                        childCount[parent]--;
                    }
                }
            }
        }
        return ordered.size() == size ? ordered : null;
    }

    /**
     * Tells whether a listed table has a key declared {@code on delete restrict} that refers to a
     * listed table, itself included: the keys SQLite checks row by row, which no delete order or
     * deferral gets past.
     */
    static boolean hasRestrictWithin(List<Ref> tables, Connection connection) {
        Set<String> listed = new LinkedHashSet<>();
        for (Ref table : tables) {
            listed.add(key(table.schemaOrMain(), table.name));
        }
        for (Ref table : tables) {
            List<Map<String, Object>> keys = table.schema == null
                    ? connection.select("select \"table\", on_delete from pragma_foreign_key_list(?)", table.name)
                    : connection.select("select \"table\", on_delete from pragma_foreign_key_list(?, ?)",
                            table.name, table.schema);
            for (Map<String, Object> foreignKey : keys) {
                if ("RESTRICT".equalsIgnoreCase(String.valueOf(foreignKey.get("on_delete")))
                        && listed.contains(key(table.schemaOrMain(), String.valueOf(foreignKey.get("table"))))) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<String> referencedTables(Ref table, Connection connection) {
        List<Map<String, Object>> rows = table.schema == null
                ? connection.select("select \"table\" from pragma_foreign_key_list(?)", table.name)
                : connection.select("select \"table\" from pragma_foreign_key_list(?, ?)", table.name, table.schema);
        List<String> parents = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            parents.add(String.valueOf(row.get("table")));
        }
        return parents;
    }

    static String key(String schema, String name) {
        return (schema + "." + name).toLowerCase(Locale.ROOT);
    }
}
