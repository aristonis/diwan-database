package org.aristonis.diwan.database.migrations;

/** Builds the SQL for one migrations table, whose name is already checked. */
final class MigrationTableSql {

    private final String table;

    /**
     * Creates the SQL for a table.
     *
     * @param table the full table name, already passed through {@link MigrationNames#checkTable}
     */
    MigrationTableSql(String table) {
        this.table = table;
    }

    String ran() {
        return "select migration from " + table + " order by batch asc, migration asc";
    }

    String ranWithBatches() {
        return "select migration, batch from " + table + " order by batch asc, migration asc";
    }

    String latest() {
        return "select migration, batch from " + table
                + " where batch >= 1 order by batch desc, migration desc limit ?";
    }

    String byBatch() {
        return "select migration, batch from " + table + " where batch = ? order by migration desc";
    }

    String last() {
        return "select migration, batch from " + table + " where batch = (select max(batch) from " + table
                + ") order by migration desc";
    }

    String lastBatch() {
        return "select max(batch) from " + table;
    }

    String log() {
        return "insert into " + table + " (migration, batch) values (?, ?)";
    }

    String delete() {
        return "delete from " + table + " where migration = ?";
    }

    String table() {
        return table;
    }
}
