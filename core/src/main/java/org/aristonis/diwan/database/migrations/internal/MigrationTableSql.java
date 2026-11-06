package org.aristonis.diwan.database.migrations.internal;

import org.aristonis.diwan.database.Internal;

/** Builds the SQL for one migrations table, whose name is already checked. */
@Internal
public final class MigrationTableSql {

    private final String table;

    /**
     * Creates the SQL for a table.
     *
     * @param table the full table name, already passed through {@link MigrationNames#checkTable}
     */
    @Internal
    public MigrationTableSql(String table) {
        this.table = table;
    }

    @Internal
    public String ran() {
        return "select migration from " + table + " order by batch asc, migration asc";
    }

    @Internal
    public String ranWithBatches() {
        return "select migration, batch from " + table + " order by batch asc, migration asc";
    }

    @Internal
    public String latest() {
        return "select migration, batch, checksum from " + table
                + " where batch >= 1 order by batch desc, migration desc limit ?";
    }

    @Internal
    public String byBatch() {
        return "select migration, batch, checksum from " + table + " where batch = ? order by migration desc";
    }

    @Internal
    public String last() {
        return "select migration, batch, checksum from " + table + " where batch = (select max(batch) from " + table
                + ") order by migration desc";
    }

    @Internal
    public String lastBatch() {
        return "select max(batch) from " + table;
    }

    @Internal
    public String log() {
        return "insert into " + table + " (migration, batch, checksum) values (?, ?, ?)";
    }

    @Internal
    public String checksums() {
        return "select migration, checksum from " + table + " order by batch asc, migration asc";
    }

    @Internal
    public String setChecksum() {
        return "update " + table + " set checksum = ? where migration = ?";
    }

    @Internal
    public String delete() {
        return "delete from " + table + " where migration = ?";
    }

    @Internal
    public String table() {
        return table;
    }
}
