package org.aristonis.diwan.database.pgsql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.ColumnGeneration;
import org.aristonis.diwan.database.schema.ColumnInfo;
import org.aristonis.diwan.database.schema.DatabaseVersion;
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
    private static final int IDENTITY_SINCE = 10;
    private static final int GENERATED_SINCE = 12;

    private static final String CURRENT_SCHEMA_OID = "(select oid from pg_namespace where nspname = current_schema())";

    /**
     * Returns the condition that finds a relation (table, view, ...) by name in the current schema.
     *
     * @param alias the alias of the {@code pg_class} row in the query
     * @return the condition, with one {@code ?} for the relation name
     */
    static String relationInCurrentSchema(String alias) {
        return alias + ".relname = ? and " + alias + ".relnamespace = " + CURRENT_SCHEMA_OID;
    }

    /** Returns a query that counts the table in the current schema. */
    SchemaQuery compileTableExists(Connection connection, String table) {
        return new SchemaQuery("select count(*) from pg_class c where " + relationInCurrentSchema("c")
                + " and c.relkind in ('r', 'p')", Collections.singletonList(table));
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
                + " where " + relationInCurrentSchema("c") + " and a.attnum > 0"
                + " and not a.attisdropped order by a.attnum", Collections.singletonList(table));
    }

    /**
     * Reads the table's or view's columns in the current schema; the identity and generation
     * columns of the catalog are selected only on servers that have them.
     */
    List<ColumnInfo> columns(Connection connection, String table) {
        List<ColumnInfo> found = new ArrayList<>();
        for (Map<String, Object> row : connection.select(columnsSql(PostgresVersion.of(connection)), table)) {
            found.add(column(row));
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * Builds the column query for a server version; the identity and generation columns of the
     * catalog are selected only where the server has them, and read as empty text elsewhere.
     */
    static String columnsSql(String version) {
        String identity = DatabaseVersion.atLeast(version, IDENTITY_SINCE) ? "a.attidentity" : "'' as attidentity";
        String generated = DatabaseVersion.atLeast(version, GENERATED_SINCE) ? "a.attgenerated" : "'' as attgenerated";
        return "select a.attname, t.typname,"
                + " format_type(a.atttypid, a.atttypmod) as type,"
                + " (select cl.collname from pg_collation cl where cl.oid = a.attcollation"
                + " and a.attcollation <> t.typcollation) as collation,"
                + " not a.attnotnull as nullable, pg_get_expr(d.adbin, d.adrelid) as dflt, " + identity + ", " + generated
                + ", col_description(a.attrelid, a.attnum) as comment"
                + " from pg_attribute a join pg_class c on c.oid = a.attrelid join pg_type t on t.oid = a.atttypid"
                + " left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum"
                + " where " + relationInCurrentSchema("c") + " and a.attnum > 0 and not a.attisdropped"
                + " order by a.attnum";
    }

    private static ColumnInfo column(Map<String, Object> row) {
        String expression = text(row.get("dflt"));
        String identity = String.valueOf(row.get("attidentity"));
        String generated = String.valueOf(row.get("attgenerated"));
        boolean isGenerated = "s".equals(generated) || "v".equals(generated);
        boolean isAutoIncrement = "a".equals(identity) || "d".equals(identity)
                || (expression != null && expression.startsWith("nextval("));
        return new ColumnInfo(String.valueOf(row.get("attname")), String.valueOf(row.get("typname")),
                String.valueOf(row.get("type")), text(row.get("collation")), Boolean.TRUE.equals(row.get("nullable")),
                isGenerated ? null : expression, isAutoIncrement, text(row.get("comment")),
                isGenerated ? new ColumnGeneration("s".equals(generated), expression == null ? "" : expression) : null);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
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
                + " where c.contype = 'f' and " + relationInCurrentSchema("tc")
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
