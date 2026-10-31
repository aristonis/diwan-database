package org.aristonis.photisma.database.migrations;

import org.aristonis.photisma.database.driver.DatabaseFamily;

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

    String create() {
        return "create table " + table
                + " (migration varchar(" + MigrationNames.MAX_NAME_LENGTH + ") not null primary key,"
                + " batch integer not null)";
    }

    String drop() {
        return "drop table " + table;
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

    /**
     * Returns a query that counts tables with the bound name in the current schema.
     *
     * @param family the database family
     * @return the query, with the table name as its only binding
     * @throws UnsupportedOperationException if the family has no known catalog query
     */
    static String exists(DatabaseFamily family) {
        switch (family) {
            case SQLITE:
                return "select count(*) from sqlite_master where type = 'table' and name = ?";
            case POSTGRESQL:
                return "select count(*) from information_schema.tables"
                        + " where table_schema = current_schema() and table_name = ?";
            case MYSQL:
                return "select count(*) from information_schema.tables"
                        + " where table_schema = database() and table_name = ?";
            default:
                throw new UnsupportedOperationException(
                        "Checking for the migrations table is not supported on " + family);
        }
    }
}
