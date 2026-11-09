package org.aristonis.diwan.database.pgsql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.TableInfo;
import org.aristonis.diwan.database.schema.grammar.SchemaQuery;

/**
 * Holds the catalog queries that schema inspection runs on PostgreSQL: tables, views, columns,
 * indexes, foreign keys and the internal PostGIS table.
 *
 * <p>The queries sit apart from the DDL in {@link PostgresSchemaGrammar}, which only turns a
 * blueprint into statements. {@link PostgresSchemaGrammar} keeps the public overrides and delegates
 * each one here; the methods are package-private, so nothing outside the package calls them.
 */
final class PostgresSchemaInspection {

    private static final String SPATIAL_REF_SYS = "spatial_ref_sys";

    /** Returns a query that counts the table in the current schema. */
    SchemaQuery compileTableExists(Connection connection, String table) {
        return new SchemaQuery("select count(*) from pg_class where relname = ? and relkind in ('r', 'p')"
                + " and relnamespace = (select oid from pg_namespace where nspname = current_schema())", Collections.singletonList(table));
    }

    /** Returns a query that lists the ordinary and partitioned tables on the search path. */
    SchemaQuery compileTables(Connection connection) {
        return new SchemaQuery("select c.relname as name, n.nspname as schema from pg_class c"
                + " join pg_namespace n on n.oid = c.relnamespace where c.relkind in ('r', 'p')"
                + " and n.nspname = any(current_schemas(false)) order by n.nspname, c.relname",
                Collections.emptyList());
    }

    /** Returns a query that lists the views on the search path. */
    SchemaQuery compileViews(Connection connection) {
        return new SchemaQuery("select viewname as name, schemaname as schema from pg_views"
                + " where schemaname = any(current_schemas(false)) order by schemaname, viewname",
                Collections.emptyList());
    }

    /** Returns whether the table is PostGIS's internal {@code spatial_ref_sys}. */
    boolean isInternalTable(TableInfo table) {
        return SPATIAL_REF_SYS.equals(table.name());
    }

    /** Returns a query that lists the table's column names in definition order. */
    SchemaQuery compileColumnNames(Connection connection, String table) {
        return new SchemaQuery("select a.attname from pg_attribute a join pg_class c on c.oid = a.attrelid"
                + " where c.relname = ? and c.relnamespace = (select oid from pg_namespace where nspname = current_schema()) and a.attnum > 0"
                + " and not a.attisdropped order by a.attnum", Collections.singletonList(table));
    }

    /** Reads the table's indexes through {@link PostgresIndexReader}. */
    List<IndexInfo> indexes(Connection connection, String table) {
        return PostgresIndexReader.read(connection, table);
    }

    /** Reads the table's foreign keys from {@code pg_constraint}. */
    List<ForeignKeyInfo> foreignKeys(Connection connection, String table) {
        List<ForeignKeyInfo> found = new ArrayList<>();
        for (Map<String, Object> row : connection.select("select"
                + " (select string_agg(a.attname, ',' order by k.ord) from unnest(c.conkey) with ordinality"
                + " as k(num, ord) join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.num) as columns,"
                + " ft.relname as foreign_table,"
                + " (select string_agg(a.attname, ',' order by k.ord) from unnest(c.confkey) with ordinality"
                + " as k(num, ord) join pg_attribute a on a.attrelid = c.confrelid and a.attnum = k.num)"
                + " as foreign_columns, c.confupdtype as on_update, c.confdeltype as on_delete"
                + " from pg_constraint c join pg_class tc on tc.oid = c.conrelid"
                + " join pg_class ft on ft.oid = c.confrelid"
                + " where c.contype = 'f' and tc.relname = ? and tc.relnamespace = (select oid from pg_namespace where nspname = current_schema())"
                + " order by c.conname", table)) {
            found.add(new ForeignKeyInfo(split(row.get("columns")), String.valueOf(row.get("foreign_table")),
                    split(row.get("foreign_columns")), action(row.get("on_update")), action(row.get("on_delete"))));
        }
        return Collections.unmodifiableList(found);
    }

    private static List<String> split(Object joined) {
        return PostgresIndexReader.split(joined, ",");
    }

    // pg_constraint stores actions as one letter.
    private static String action(Object code) {
        switch (String.valueOf(code)) {
            case "c":
                return "cascade";
            case "n":
                return "set null";
            case "d":
                return "set default";
            case "r":
                return "restrict";
            default:
                return "no action";
        }
    }
}
