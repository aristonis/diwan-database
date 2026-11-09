package org.aristonis.diwan.database.pgsql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.IndexInfo;

/**
 * Reads a table's indexes from {@code pg_index}, with order, collation and partial-index condition.
 */
final class PostgresIndexReader {

    /** Separates the per-column values of one index; a control character no name holds. */
    private static final String SEPARATOR = "\u001f";

    private PostgresIndexReader() {
    }

    /**
     * Reads the indexes of a table in the current schema.
     *
     * @param connection the connection to read with, never {@code null}
     * @param table the table name, never {@code null}
     * @return the indexes by name, never {@code null}
     */
    static List<IndexInfo> read(Connection connection, String table) {
        // indoption bit 0x0001 is INDOPTION_DESC (src/include/catalog/pg_index.h); int2vector and
        // oidvector subscripts start at 0. A collation is reported only when it is not the
        // column's own (pg_attribute.attcollation); an expression column has none to compare.
        String sep = "chr(31)";
        List<IndexInfo> found = new ArrayList<>();
        for (Map<String, Object> row : connection.select("select ic.relname as name,"
                + " string_agg(coalesce(a.attname, pg_get_indexdef(i.indexrelid, k.ord::int, true)), " + sep
                + " order by k.ord) as columns,"
                + " string_agg(case when (i.indoption[k.ord - 1] & 1) = 1 then 'desc' else 'asc' end, " + sep
                + " order by k.ord) as orders,"
                + " string_agg(case when a.attname is not null and i.indcollation[k.ord - 1] <> a.attcollation"
                + " then (select cl.collname from pg_collation cl where cl.oid = i.indcollation[k.ord - 1])"
                + " else '' end, " + sep + " order by k.ord) as collations,"
                + " max(pg_get_expr(i.indpred, i.indrelid)) as predicate,"
                + " i.indisunique as is_unique, i.indisprimary as is_primary from pg_index i"
                + " join pg_class tc on tc.oid = i.indrelid"
                + " join pg_class ic on ic.oid = i.indexrelid"
                + " join lateral unnest(i.indkey) with ordinality as k(num, ord) on true"
                + " left join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.num and k.num <> 0"
                + " where " + PostgresSchemaInspection.relationInCurrentSchema("tc")
                + " and k.ord <= i.indnkeyatts"
                + " group by ic.relname, i.indisunique, i.indisprimary order by ic.relname", table)) {
            Object predicate = row.get("predicate");
            found.add(new IndexInfo(String.valueOf(row.get("name")), split(row.get("columns"), SEPARATOR),
                    Boolean.TRUE.equals(row.get("is_unique")), Boolean.TRUE.equals(row.get("is_primary")),
                    split(row.get("orders"), SEPARATOR), split(row.get("collations"), SEPARATOR),
                    predicate == null ? null : predicate.toString()));
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * Splits a joined value, keeping empty parts.
     *
     * @param joined the joined text, or {@code null}
     * @param separator the separator, as plain text
     * @return the parts, empty for {@code null}
     */
    static List<String> split(Object joined, String separator) {
        if (joined == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(
                joined.toString().split(java.util.regex.Pattern.quote(separator), -1))));
    }
}
