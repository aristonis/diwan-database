package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.ColumnGeneration;
import org.aristonis.diwan.database.schema.ColumnInfo;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Reading column details on SQLite: types, defaults, collation, auto-increment and generated columns. */
class SQLiteColumnsTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createDatabase() {
        db = connection("");
        schema = SQLiteSchema.of(db);
    }

    private Connection connection(String prefix) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("columns.db"));
        return Connection.builder().name("sqlite").tablePrefix(prefix).dataSource(dataSource).build();
    }

    private ColumnInfo column(String table, String name) {
        for (ColumnInfo info : schema.getColumns(table)) {
            if (info.name().equals(name)) {
                return info;
            }
        }
        throw new AssertionError("No column " + name + " in " + table);
    }

    @Test
    void aVarcharColumnReportsTypeDefaultAndNullability() {
        db.statement("create table t (name varchar(255) not null default 'x', age int, note TEXT collate nocase)");

        ColumnInfo name = column("t", "name");
        ColumnInfo age = column("t", "age");

        assertEquals("varchar(255)", name.type());
        assertEquals("varchar", name.typeName());
        assertFalse(name.isNullable());
        assertEquals(Optional.of("'x'"), name.defaultValue());
        assertEquals(Optional.empty(), name.collation());
        assertTrue(age.isNullable());
        assertEquals("int", age.type());
        assertEquals(Optional.empty(), age.defaultValue());
        assertEquals(Optional.empty(), age.generation());
    }

    @Test
    void aColumnReportsItsOwnCollationAndTypeIsLowerCase() {
        db.statement("create table t (note TEXT collate nocase, plain TEXT)");

        assertEquals(Optional.of("nocase"), column("t", "note").collation());
        assertEquals("text", column("t", "note").type());
        assertEquals(Optional.empty(), column("t", "plain").collation());
    }

    @Test
    void aTemporaryTableReportsCollationAndGenerationExpression() {
        db.transaction(tx -> {
            tx.statement("create temp table tmp (note TEXT collate nocase, a int, b int generated always as (a + 1) stored)");
            Schema inTransaction = SQLiteSchema.of(tx);
            List<ColumnInfo> columns = inTransaction.getColumns("tmp");

            assertEquals(Optional.of("nocase"), columns.get(0).collation());
            assertEquals("a + 1", columns.get(2).generation().get().expression());
            return null;
        });
    }

    @Test
    void aTableNameInAnotherCaseStillReportsCollation() {
        db.statement("create table users (note TEXT collate nocase)");

        List<ColumnInfo> columns = schema.getColumns("USERS");

        assertEquals(Optional.of("nocase"), columns.get(0).collation());
    }

    @Test
    void columnsComeInDeclarationOrder() {
        db.statement("create table t (c int, a int, b int)");

        assertEquals(Arrays.asList("c", "a", "b"), schema.getColumnListing("t"));
    }

    @Test
    void anUnknownTableHasNoColumns() {
        assertEquals(Collections.emptyList(), schema.getColumns("missing"));
    }

    @Test
    void anIntegerPrimaryKeyIsAutoIncrement() {
        db.statement("create table t (id integer primary key, n integer)");

        assertTrue(column("t", "id").isAutoIncrement());
        assertFalse(column("t", "n").isAutoIncrement());
    }

    @Test
    void aCompositeIntegerPrimaryKeyIsNotAutoIncrement() {
        db.statement("create table t (a integer, b integer, primary key (a, b))");

        assertFalse(column("t", "a").isAutoIncrement());
        assertFalse(column("t", "b").isAutoIncrement());
    }

    @Test
    void aBigintPrimaryKeyIsNotAutoIncrement() {
        db.statement("create table t (id bigint primary key)");

        assertFalse(column("t", "id").isAutoIncrement());
    }

    @Test
    void anIntegerPrimaryKeyDescIsAutoIncrement() {
        db.statement("create table t (id integer primary key desc)");

        assertTrue(column("t", "id").isAutoIncrement());
    }

    @Test
    void aVirtualGeneratedColumnHasItsExpression() {
        db.statement("create table t (qty integer, twice integer generated always as (qty * 2) virtual)");

        ColumnGeneration generation = column("t", "twice").generation().orElseThrow();

        assertFalse(generation.isStored());
        assertEquals("qty * 2", generation.expression());
        assertEquals(Optional.empty(), column("t", "twice").defaultValue());
        assertEquals(Optional.empty(), column("t", "qty").generation());
    }

    @Test
    void aStoredGeneratedColumnHasItsExpressionAndNoDefault() {
        db.statement("create table t (qty integer, price integer, total integer as (qty * price) stored)");

        ColumnInfo total = column("t", "total");

        assertTrue(total.generation().orElseThrow().isStored());
        assertEquals("qty * price", total.generation().orElseThrow().expression());
        assertEquals(Optional.empty(), total.defaultValue());
    }

    @Test
    void theExpressionKeepsNestedParentheses() {
        db.statement("create table t (a integer, b integer,"
                + " c integer generated always as (coalesce((a + b) * 2, max(a, (b)))) stored)");

        assertEquals("coalesce((a + b) * 2, max(a, (b)))",
                column("t", "c").generation().orElseThrow().expression());
    }

    @Test
    void aColumnNeverHasAComment() {
        db.statement("create table t (a integer)");

        assertEquals(Optional.empty(), column("t", "a").comment());
    }

    @Test
    void aViewListsItsColumns() {
        db.statement("create table t (id integer primary key, name varchar(20))");
        db.statement("create view v as select id, name from t");

        List<String> names = schema.getColumnListing("v");

        assertEquals(Arrays.asList("id", "name"), names);
        assertEquals("varchar(20)", column("v", "name").type());
        assertEquals(Optional.empty(), column("v", "name").collation());
        assertFalse(column("v", "id").isAutoIncrement());
    }

    @Test
    void theTablePrefixIsApplied() {
        Connection prefixed = connection("app_");
        db.statement("create table app_t (a integer)");

        assertEquals(Collections.singletonList("a"), SQLiteSchema.of(prefixed).getColumnListing("t"));
    }

    @Test
    void theHiddenColumnsOfAFullTextTableAreLeftOut() {
        try {
            db.statement("create virtual table docs using fts5(body, title)");
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "fts5 is not compiled into this SQLite: " + e.getMessage());
        }

        assertEquals(Arrays.asList("body", "title"), schema.getColumnListing("docs"));
    }

    @Test
    void quotedNamesAreMatchedWithoutTheirQuotes() {
        db.statement("create table t (\"a b\" text collate nocase, `c` integer as (1 + 1) virtual,"
                + " [d] text collate rtrim)");

        assertEquals(Optional.of("nocase"), column("t", "a b").collation());
        assertEquals("1 + 1", column("t", "c").generation().orElseThrow().expression());
        assertEquals(Optional.of("rtrim"), column("t", "d").collation());
    }

    @Test
    void aConstraintClauseDoesNotShiftTheColumns() {
        db.statement("create table t (a text collate nocase, b integer as (length(a)) stored,"
                + " constraint u unique (a), check (length(a) > 0))");

        assertEquals(Optional.of("nocase"), column("t", "a").collation());
        assertEquals("length(a)", column("t", "b").generation().orElseThrow().expression());
    }

    @Test
    void commentsInTheCreateSqlAreIgnored() {
        db.statement("create table t (\n"
                + "  a text -- collate binary, not here\n"
                + "    collate nocase, /* b text collate rtrim, */\n"
                + "  b integer /* as (nope) */ as (1 + 2) virtual\n"
                + ")");

        assertEquals(Optional.of("nocase"), column("t", "a").collation());
        assertEquals("1 + 2", column("t", "b").generation().orElseThrow().expression());
        assertEquals(Optional.empty(), column("t", "b").collation());
    }

    @Test
    void theOldVersionPathReadsTableInfoAndStillListsColumns() {
        db.statement("create table t (id integer primary key, name varchar(10) not null)");
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.24.0"));

        List<ColumnInfo> columns = old.getColumns("t");

        assertEquals(2, columns.size());
        assertTrue(columns.get(0).isAutoIncrement());
        assertEquals("varchar", columns.get(1).typeName());
        assertEquals(Optional.empty(), columns.get(1).generation());
    }
}
