package org.aristonis.diwan.database.pgsql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Blueprint;
import org.aristonis.diwan.database.schema.ColumnDefinition;
import org.aristonis.diwan.database.schema.ColumnType;
import org.aristonis.diwan.database.schema.Command;
import org.aristonis.diwan.database.schema.DatabaseVersion;
import org.aristonis.diwan.database.schema.ForeignKeyAction;
import org.aristonis.diwan.database.schema.ForeignKeyDefinition;
import org.aristonis.diwan.database.schema.ForeignKeyInfo;
import org.aristonis.diwan.database.schema.IndexInfo;
import org.aristonis.diwan.database.schema.SchemaGrammar;
import org.aristonis.diwan.database.schema.SchemaQuery;

/**
 * Turns a blueprint into PostgreSQL statements, following Laravel's PostgreSQL grammar.
 *
 * <p>Keys, unique constraints and foreign keys are added with {@code alter table} after the
 * {@code create table}; the connection runs a blueprint's statements in one transaction, so a
 * failure leaves nothing behind. Tables are read in the connection's current schema.
 */
public class PostgresSchemaGrammar extends SchemaGrammar {

    private static final int MAX_IDENTIFIER_LENGTH = 63;
    private static final int VIRTUAL_COLUMNS_SINCE = 18;
    private static final String CURRENT_YEAR = "EXTRACT(YEAR FROM CURRENT_DATE)";

    /** Creates the grammar. */
    public PostgresSchemaGrammar() {
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Refuses virtual generated columns before PostgreSQL 18, which has only stored ones,
     *     and a connection whose {@code search_path} names no existing schema, where every table
     *     would look missing.
     */
    @Override
    public List<String> compile(Blueprint blueprint, Connection connection) {
        if (!connection.scalar("select current_schema()").isPresent()) {
            throw new IllegalStateException("The connection's search_path names no existing schema; create the"
                    + " schema first or fix search_path");
        }
        for (ColumnDefinition column : blueprint.columns()) {
            if (column.virtualAs().isPresent()) {
                String version = String.valueOf(connection.scalar("show server_version").orElse("0"));
                if (!DatabaseVersion.atLeast(version, VIRTUAL_COLUMNS_SINCE)) {
                    throw new UnsupportedOperationException("Virtual generated columns need PostgreSQL 18 or later;"
                            + " this database runs " + version + "; use storedAs");
                }
                break;
            }
        }
        return super.compile(blueprint, connection);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Adds the statements that set column comments after a create, add or change.
     */
    @Override
    protected List<String> compileCommand(Blueprint blueprint, Command command, Connection connection) {
        List<String> statements = super.compileCommand(blueprint, command, connection);
        if (Command.CREATE.equals(command.name())) {
            List<String> all = new ArrayList<>(statements);
            for (ColumnDefinition column : blueprint.columns()) {
                addComment(all, blueprint, column);
            }
            return all;
        }
        if (Command.ADD.equals(command.name()) || Command.CHANGE.equals(command.name())) {
            List<String> all = new ArrayList<>(statements);
            addComment(all, blueprint, command.column());
            return all;
        }
        return statements;
    }

    private void addComment(List<String> statements, Blueprint blueprint, ColumnDefinition column) {
        if (column.comment().isPresent()) {
            // An empty comment removes it, as PostgreSQL treats '' and null alike here.
            String text = column.comment().get();
            statements.add("comment on column " + wrapTable(blueprint.table()) + "." + wrap(column.name()) + " is "
                    + (text.isEmpty() ? "null" : literal(text)));
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code "pgsql"}
     */
    @Override
    public String driverName() {
        return "pgsql";
    }

    /**
     * {@inheritDoc}
     *
     * @return 63, PostgreSQL's limit
     */
    @Override
    public int maxIdentifierLength() {
        return MAX_IDENTIFIER_LENGTH;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    protected boolean supportsColumnClauses() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code create table <table> (<columns>, <checks>)}
     */
    @Override
    protected String compileCreate(Blueprint blueprint, Command command, Connection connection) {
        StringBuilder sql = new StringBuilder();
        for (ColumnDefinition column : blueprint.columns()) {
            separate(sql).append(column(blueprint, column));
        }
        for (Command check : blueprint.commands()) {
            if (Command.CHECK.equals(check.name())) {
                separate(sql).append(tableCheck(check));
            }
        }
        return "create table " + wrapTable(blueprint.table()) + " (" + sql + ")";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> add column <column>}
     */
    @Override
    protected String compileAdd(Blueprint blueprint, Command command, Connection connection) {
        return "alter table " + wrapTable(blueprint.table()) + " add column " + column(blueprint, command.column());
    }

    /**
     * {@inheritDoc}
     *
     * @return one {@code alter table} that sets the type (converting with {@code using}), the
     *     nullability and the default; enum, raw and generated columns and checks are refused
     */
    @Override
    protected List<String> compileChange(Blueprint blueprint, Command command, Connection connection) {
        ColumnDefinition column = command.column();
        if (column.type() == ColumnType.ENUM || column.type() == ColumnType.RAW || column.isGenerated()
                || !column.checks().isEmpty()) {
            throw new UnsupportedOperationException("PostgreSQL change() does not support enum, raw or generated"
                    + " columns or checks; column " + column.name());
        }
        String name = wrap(column.name());
        String type = type(column);
        String alter = "alter column " + name;
        StringBuilder sql = new StringBuilder("alter table ").append(wrapTable(blueprint.table())).append(' ')
                .append(alter).append(" type ").append(type).append(" using ").append(name).append("::")
                .append(type);
        sql.append(", ").append(alter).append(column.isNullable() ? " drop not null" : " set not null");
        String defaultSql = defaultSql(column);
        sql.append(", ").append(alter).append(defaultSql == null ? " drop default" : " set default " + defaultSql);
        return Collections.singletonList(sql.toString());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code drop table <table>}
     */
    @Override
    protected String compileDrop(Blueprint blueprint, Command command, Connection connection) {
        return "drop table " + wrapTable(blueprint.table());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code drop table if exists <table>}
     */
    @Override
    protected String compileDropIfExists(Blueprint blueprint, Command command, Connection connection) {
        return "drop table if exists " + wrapTable(blueprint.table());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> rename to <new name>}
     */
    @Override
    protected String compileRename(Blueprint blueprint, Command command, Connection connection) {
        return "alter table " + wrapTable(blueprint.table()) + " rename to " + wrapTable(command.string(Command.TO));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> add constraint <name> unique (<columns>)}
     */
    @Override
    protected String compileUnique(Blueprint blueprint, Command command, Connection connection) {
        return "alter table " + wrapTable(blueprint.table()) + " add constraint " + wrap(command.string(Command.INDEX))
                + " unique (" + columnize(command.strings(Command.COLUMNS)) + ")";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code create index <name> on <table> (<columns>)}
     */
    @Override
    protected String compileIndex(Blueprint blueprint, Command command, Connection connection) {
        return "create index " + wrap(command.string(Command.INDEX)) + " on " + wrapTable(blueprint.table()) + " ("
                + columnize(command.strings(Command.COLUMNS)) + ")";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code drop index <name>} for a plain index; a unique index is a constraint, so
     *     {@code alter table <table> drop constraint <name>}
     */
    @Override
    protected String compileDropIndex(Blueprint blueprint, Command command, Connection connection) {
        if (Command.DROP_UNIQUE.equals(command.name())) {
            return dropConstraint(blueprint, command.string(Command.INDEX));
        }
        return "drop index " + wrap(command.string(Command.INDEX));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> add primary key (<columns>)}, unless an auto-increment
     *     column already made itself the key
     */
    @Override
    protected List<String> compilePrimary(Blueprint blueprint, Command command, Connection connection) {
        return Collections.singletonList("alter table " + wrapTable(blueprint.table()) + " add primary key ("
                + columnize(command.strings(Command.COLUMNS)) + ")");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> drop constraint <key>}, with the key's name read from the
     *     database, since PostgreSQL keeps the name it gave ({@code <table>_pkey}) when the table is
     *     renamed later; whatever name was asked for is ignored, as Laravel does
     */
    @Override
    protected List<String> compileDropPrimary(Blueprint blueprint, Command command, Connection connection) {
        Object name = connection.scalar("select c.conname from pg_constraint c join pg_class t on t.oid = c.conrelid"
                + " where c.contype = 'p' and t.relname = ? and t.relnamespace = (select oid from pg_namespace where nspname = current_schema())",
                blueprint.table()).orElse(blueprint.table() + "_pkey");
        return Collections.singletonList(dropConstraint(blueprint, name.toString()));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> add constraint <name> foreign key (...) references ...}
     */
    @Override
    protected List<String> compileForeign(Blueprint blueprint, Command command, Connection connection) {
        ForeignKeyDefinition key = command.foreignKey();
        StringBuilder sql = new StringBuilder("alter table ").append(wrapTable(blueprint.table()))
                .append(" add constraint ").append(wrap(command.string(Command.INDEX))).append(" foreign key (")
                .append(columnize(key.columns())).append(") references ").append(wrapTable(key.referencedTable()))
                .append(" (").append(columnize(key.referencedColumns())).append(')');
        key.deleteAction().map(ForeignKeyAction::sql).ifPresent(action -> sql.append(" on delete ").append(action));
        key.updateAction().map(ForeignKeyAction::sql).ifPresent(action -> sql.append(" on update ").append(action));
        return Collections.singletonList(sql.toString());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> drop constraint <name>}
     */
    @Override
    protected List<String> compileDropForeign(Blueprint blueprint, Command command, Connection connection) {
        return Collections.singletonList(dropConstraint(blueprint, command.string(Command.INDEX)));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter index <from> rename to <to>}
     */
    @Override
    protected List<String> compileRenameIndex(Blueprint blueprint, Command command, Connection connection) {
        return Collections.singletonList("alter index " + wrap(command.string(Command.FROM)) + " rename to "
                + wrap(command.string(Command.TO)));
    }

    /**
     * {@inheritDoc}
     *
     * @return one {@code alter table} dropping every column
     */
    @Override
    protected List<String> compileDropColumn(Blueprint blueprint, Command command, Connection connection) {
        StringBuilder sql = new StringBuilder("alter table ").append(wrapTable(blueprint.table())).append(' ');
        List<String> columns = command.strings(Command.COLUMNS);
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append("drop column ").append(wrap(columns.get(i)));
        }
        return Collections.singletonList(sql.toString());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> rename column <from> to <to>}
     */
    @Override
    protected List<String> compileRenameColumn(Blueprint blueprint, Command command, Connection connection) {
        return Collections.singletonList("alter table " + wrapTable(blueprint.table()) + " rename column "
                + wrap(command.string(Command.FROM)) + " to " + wrap(command.string(Command.TO)));
    }

    /**
     * {@inheritDoc}
     *
     * @return no statement on a new table, whose create statement holds the check;
     *     {@code alter table <table> add constraint <name> check (...)} on an existing one
     */
    @Override
    protected List<String> compileCheck(Blueprint blueprint, Command command, Connection connection) {
        if (blueprint.isCreating()) {
            return Collections.emptyList();
        }
        return Collections.singletonList("alter table " + wrapTable(blueprint.table()) + " add " + tableCheck(command));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code alter table <table> drop constraint <name>}
     */
    @Override
    protected List<String> compileDropCheck(Blueprint blueprint, Command command, Connection connection) {
        return Collections.singletonList(dropConstraint(blueprint, command.string(Command.NAME)));
    }

    /**
     * {@inheritDoc}
     *
     * @return a count over {@code pg_class} in the current schema; unlike
     *     {@code information_schema}, it sees tables the role has no rights on
     */
    @Override
    public SchemaQuery compileTableExists(Connection connection, String table) {
        return new SchemaQuery("select count(*) from pg_class where relname = ? and relkind in ('r', 'p')"
                + " and relnamespace = (select oid from pg_namespace where nspname = current_schema())", Collections.singletonList(table));
    }

    /**
     * {@inheritDoc}
     *
     * @return a query over {@code pg_attribute} for the table in the current schema
     */
    @Override
    public SchemaQuery compileColumnNames(Connection connection, String table) {
        return new SchemaQuery("select a.attname from pg_attribute a join pg_class c on c.oid = a.attrelid"
                + " where c.relname = ? and c.relnamespace = (select oid from pg_namespace where nspname = current_schema()) and a.attnum > 0"
                + " and not a.attisdropped order by a.attnum", Collections.singletonList(table));
    }

    /**
     * {@inheritDoc}
     *
     * @return the table's indexes from {@code pg_index}, the primary key and unique constraints
     *     among them; key columns only (no {@code include} columns), an expression shown as its SQL
     */
    @Override
    public List<IndexInfo> indexes(Connection connection, String table) {
        List<IndexInfo> found = new ArrayList<>();
        for (Map<String, Object> row : connection.select("select ic.relname as name,"
                + " string_agg(coalesce(a.attname, pg_get_indexdef(i.indexrelid, k.ord::int, true)), ','"
                + " order by k.ord) as columns, i.indisunique as is_unique,"
                + " i.indisprimary as is_primary from pg_index i"
                + " join pg_class tc on tc.oid = i.indrelid"
                + " join pg_class ic on ic.oid = i.indexrelid"
                + " join lateral unnest(i.indkey) with ordinality as k(num, ord) on true"
                + " left join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.num and k.num <> 0"
                + " where tc.relname = ? and tc.relnamespace = (select oid from pg_namespace where nspname = current_schema())"
                + " and k.ord <= i.indnkeyatts"
                + " group by ic.relname, i.indisunique, i.indisprimary order by ic.relname", table)) {
            found.add(new IndexInfo(String.valueOf(row.get("name")), split(row.get("columns")),
                    Boolean.TRUE.equals(row.get("is_unique")), Boolean.TRUE.equals(row.get("is_primary"))));
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * {@inheritDoc}
     *
     * @return the table's foreign keys from {@code pg_constraint}, actions in lower case
     */
    @Override
    public List<ForeignKeyInfo> foreignKeys(Connection connection, String table) {
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

    /**
     * {@inheritDoc}
     *
     * @return Laravel's PostgreSQL type for the column; an enum is {@code varchar(255)} with a check
     *     on its values, and {@code set} is refused, since PostgreSQL has nothing like it
     */
    @Override
    protected String type(ColumnDefinition column) {
        switch (column.type()) {
            case STRING:
                return "varchar(" + column.length() + ")";
            case CHAR:
                return "char(" + column.length() + ")";
            case TINY_TEXT:
                return "varchar(255)";
            case TEXT:
            case MEDIUM_TEXT:
            case LONG_TEXT:
                return "text";
            case INTEGER:
            case MEDIUM_INTEGER:
                return serialOr(column, "serial", "integer");
            case BIG_INTEGER:
                return serialOr(column, "bigserial", "bigint");
            case SMALL_INTEGER:
            case TINY_INTEGER:
                return serialOr(column, "smallserial", "smallint");
            case YEAR:
                return "integer";
            case BOOLEAN:
                return "boolean";
            case DECIMAL:
                return "decimal(" + column.total() + ", " + column.places() + ")";
            case FLOAT:
                return column.precision().isPresent() ? "float(" + column.precision().getAsInt() + ")" : "float";
            case DOUBLE:
                return "double precision";
            case DATE:
                return "date";
            case TIME:
                return "time" + precision(column) + " without time zone";
            case TIME_TZ:
                return "time" + precision(column) + " with time zone";
            case TIMESTAMP:
            case DATE_TIME:
                return "timestamp" + precision(column) + " without time zone";
            case TIMESTAMP_TZ:
            case DATE_TIME_TZ:
                return "timestamp" + precision(column) + " with time zone";
            case BINARY:
                return "bytea";
            case UUID:
                return "uuid";
            case IP_ADDRESS:
                return "inet";
            case MAC_ADDRESS:
                return "macaddr";
            case JSON:
                return "json";
            case JSONB:
                return "jsonb";
            case ENUM:
                return "varchar(255) check (" + wrap(column.name()) + " in (" + values(column.allowed()) + "))";
            case RAW:
                return column.rawDefinition().orElseThrow(IllegalStateException::new);
            case INSTANT:
                return "timestamp(6) with time zone";
            case MONEY:
                return column.total() == 0 ? "bigint" : "numeric(" + column.total() + ", " + column.places() + ")";
            default:
                return super.type(column);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return in Laravel's order: nullability (on a generated column only after
     *     {@code nullable(false)}), the default, the generated expression, {@code primary key} for an
     *     auto-increment column, then each check; a raw column gets only its checks
     */
    @Override
    protected String modifiers(Blueprint blueprint, ColumnDefinition column) {
        StringBuilder sql = new StringBuilder();
        if (column.type() != ColumnType.RAW) {
            if (column.isGenerated()) {
                sql.append(column.isNotNullStated() ? " not null" : "");
            } else {
                sql.append(column.isNullable() ? " null" : " not null");
            }
            String defaultSql = defaultSql(column);
            if (defaultSql != null) {
                sql.append(" default ").append(defaultSql);
            }
            column.virtualAs().ifPresent(expression -> sql.append(" generated always as (").append(expression)
                    .append(") virtual"));
            column.storedAs().ifPresent(expression -> sql.append(" generated always as (").append(expression)
                    .append(") stored"));
            if (column.isAutoIncrement() && !hasPrimaryCommand(blueprint)) {
                sql.append(" primary key");
            }
        }
        if (column.type() == ColumnType.MONEY && column.isUnsigned()) {
            sql.append(" check (").append(wrap(column.name())).append(" >= 0)");
        }
        for (String check : column.checks()) {
            sql.append(" check (").append(check).append(')');
        }
        return sql.toString();
    }

    /**
     * {@inheritDoc}
     *
     * @return booleans as {@code true} or {@code false}; everything else as the base writes it
     */
    @Override
    protected String literal(Object value) {
        if (value instanceof Boolean) {
            return value.toString();
        }
        return super.literal(value);
    }

    private String defaultSql(ColumnDefinition column) {
        if (column.defaultValue().isPresent()) {
            return literal(column.defaultValue().get());
        }
        if (column.type() == ColumnType.YEAR && column.usesCurrent()) {
            return CURRENT_YEAR;
        }
        return null;
    }

    private static String serialOr(ColumnDefinition column, String serial, String plain) {
        return column.isAutoIncrement() && !column.isChange() ? serial : plain;
    }

    private static String precision(ColumnDefinition column) {
        return column.precision().isPresent() ? "(" + column.precision().getAsInt() + ")" : "";
    }

    private String values(List<String> allowed) {
        StringBuilder sql = new StringBuilder();
        for (String value : allowed) {
            if (sql.length() > 0) {
                sql.append(", ");
            }
            sql.append(literal(value));
        }
        return sql.toString();
    }

    private String tableCheck(Command check) {
        return "constraint " + wrap(check.string(Command.NAME)) + " check (" + check.string(Command.SQL) + ")";
    }

    private String dropConstraint(Blueprint blueprint, String name) {
        return "alter table " + wrapTable(blueprint.table()) + " drop constraint " + wrap(name);
    }

    private static boolean hasPrimaryCommand(Blueprint blueprint) {
        for (Command command : blueprint.commands()) {
            if (Command.PRIMARY.equals(command.name())) {
                return true;
            }
        }
        return false;
    }

    private static StringBuilder separate(StringBuilder sql) {
        return sql.length() > 0 ? sql.append(", ") : sql;
    }

    private static List<String> split(Object joined) {
        if (joined == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(joined.toString().split(","))));
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
