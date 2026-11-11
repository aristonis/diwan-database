package org.aristonis.diwan.database.pgsql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.DatabaseVersion;

/**
 * Moves the sequences owned by the columns of some tables to just past the highest committed
 * value, or to the start value when the table is empty. A sequence is not transactional, so a
 * rolled-back insert leaves its counter advanced; this puts it back.
 *
 * <p>Only sequences that belong to a column of the named tables (identity columns and
 * {@code serial} columns) are touched. A sequence that is merely used as a default, or shared with
 * another table, is left alone.
 */
final class PostgresIdentityRestart {

    private static final int SEQUENCE_CATALOG_SINCE = 10;

    // Both names are schema-qualified, so the statements that follow reach the same objects whatever
    // the search_path is by then.
    private static final String OWNED_SEQUENCES = "select quote_ident(sn.nspname) || '.' || quote_ident(sc.relname) as seq,"
            + " quote_ident(tn.nspname) || '.' || quote_ident(t.relname) as tbl,"
            + " a.attname as col, s.seqstart, s.seqincrement"
            + " from pg_depend d join pg_sequence s on s.seqrelid = d.objid"
            + " join pg_class sc on sc.oid = d.objid join pg_namespace sn on sn.oid = sc.relnamespace"
            + " join pg_class t on t.oid = d.refobjid join pg_namespace tn on tn.oid = t.relnamespace"
            + " join pg_attribute a on a.attrelid = d.refobjid and a.attnum = d.refobjsubid"
            + " where d.classid = 'pg_class'::regclass and d.refclassid = 'pg_class'::regclass"
            + " and d.deptype in ('a', 'i') and d.refobjid in (";

    private PostgresIdentityRestart() {
    }

    /**
     * Restarts the owned sequences of the tables.
     *
     * @param connection the connection to run on, never {@code null}
     * @param grammar the grammar that quotes the table names and adds the prefix, never {@code null}
     * @param tables the table names, never {@code null} or empty, none {@code null} or blank,
     *     optionally {@code schema.table}
     * @throws UnsupportedOperationException if the server is older than PostgreSQL 10
     * @throws org.aristonis.diwan.database.exception.QueryException if a table does not exist or
     *     the database refuses a statement
     */
    static void run(Connection connection, PostgresQueryGrammar grammar, List<String> tables) {
        if (tables.isEmpty()) {
            return;
        }
        String version = PostgresVersion.of(connection);
        if (!DatabaseVersion.atLeast(version, SEQUENCE_CATALOG_SINCE)) {
            throw new UnsupportedOperationException(
                    "restartIdentities needs PostgreSQL 10 or later, found " + version);
        }
        List<Object> names = new ArrayList<>();
        for (String table : tables) {
            names.add(grammar.wrapTable(table));
        }
        for (Map<String, Object> sequence : connection.select(ownedSequencesSql(names.size()), names)) {
            restart(connection, grammar, sequence);
        }
    }

    private static String ownedSequencesSql(int tableCount) {
        return OWNED_SEQUENCES + String.join(", ", Collections.nCopies(tableCount, "?::regclass")) + ")";
    }

    private static void restart(Connection connection, PostgresQueryGrammar grammar, Map<String, Object> sequence) {
        long increment = ((Number) sequence.get("seqincrement")).longValue();
        long start = ((Number) sequence.get("seqstart")).longValue();
        String edge = increment < 0 ? "min" : "max";
        String sql = "select setval(?::regclass, coalesce((select " + edge + "(" + quoted(String.valueOf(sequence.get("col")))
                + ") from " + sequence.get("tbl") + ") + ?, ?), false)";
        connection.select(sql, sequence.get("seq"), increment, start);
    }

    // The name comes from the catalog whole, so it is quoted as one identifier: a dot or " as " in it
    // is part of the name, not a qualifier or an alias.
    private static String quoted(String column) {
        return '"' + column.replace("\"", "\"\"") + '"';
    }
}
