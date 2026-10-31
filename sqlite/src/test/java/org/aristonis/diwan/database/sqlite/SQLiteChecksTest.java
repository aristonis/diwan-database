package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteChecksTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void connect() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("books.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
    }

    private long number(String sql) {
        return ((Number) db.scalar(sql).get()).longValue();
    }

    @Test
    void aColumnCheckRefusesABadRow() {
        schema.create("entries", table -> {
            table.id();
            table.integer("amount").check("amount <> 0");
        });

        db.insert("insert into entries (amount) values (5)");
        assertThrows(QueryException.class, () -> db.insert("insert into entries (amount) values (0)"));
    }

    @Test
    void aNamedTableCheckRefusesABadRowUntilDropped() {
        schema.create("entries", table -> {
            table.id();
            table.string("side");
            table.check("entries_side_check", "side in ('debit', 'credit')");
        });
        assertThrows(QueryException.class, () -> db.insert("insert into entries (side) values ('x')"));

        schema.table("entries", table -> table.dropCheck("entries_side_check"));

        db.insert("insert into entries (side) values ('x')");
        assertEquals(1, number("select count(*) from entries"));
    }

    @Test
    void droppingAMissingCheckIsRefusedByName() {
        schema.create("entries", table -> table.id());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> schema.table("entries", table -> table.dropCheck("nope")));
        assertTrue(error.getMessage().contains("nope"));
    }

    @Test
    void generatedColumnsAreComputedAndFollowUpdates() {
        schema.create("lines", table -> {
            table.id();
            table.integer("qty");
            table.integer("price");
            table.integer("total").storedAs("qty * price");
            table.integer("twice").virtualAs("qty * 2");
        });
        db.insert("insert into lines (qty, price) values (3, 4)");
        assertEquals(12, number("select total from lines"));
        assertEquals(6, number("select twice from lines"));

        db.update("update lines set qty = 5");

        assertEquals(20, number("select total from lines"));
        assertEquals(10, number("select twice from lines"));
    }

    @Test
    void aGeneratedColumnOverANullableSourceAcceptsNull() {
        schema.create("lines", table -> {
            table.id();
            table.integer("qty").nullable();
            table.integer("twice").virtualAs("qty * 2");
            table.integer("kept").storedAs("coalesce(qty, 0)").nullable(false);
        });

        db.insert("insert into lines (qty) values (null)");

        assertEquals(1, number("select count(*) from lines where twice is null"));
    }

    @Test
    void aRawColumnGetsNoNullabilityOrDefaultAddedAfterIt() {
        List<LoggedQuery> sql = schema.pretend(s -> s.create("raw_t", table ->
                table.rawColumn("x", "text null").defaultValue("a").check("x <> ''")));

        assertTrue(sql.get(0).sql().endsWith("\"x\" text null check (x <> ''))"), sql.get(0).sql());
    }

    @Test
    void aRawColumnTheRebuildCouldNotCarryIsRefused() {
        assertThrows(UnsupportedOperationException.class,
                () -> schema.create("raw_t", table -> table.rawColumn("name", "text collate nocase")));
        schema.create("raw_t", table -> table.id());

        assertThrows(UnsupportedOperationException.class,
                () -> schema.table("raw_t", table -> table.rawColumn("total", "integer as (id * 2) stored")));
        assertThrows(UnsupportedOperationException.class,
                () -> schema.table("raw_t", table -> table.rawColumn("n", "integer check (n > 0)")));
    }

    @Test
    void aVirtualColumnIsAddedInPlace() {
        schema.create("lines", table -> {
            table.id();
            table.integer("qty");
        });
        db.insert("insert into lines (qty) values (2)");

        List<LoggedQuery> sql = schema.pretend(s -> s.table("lines", table -> table.integer("half").virtualAs("qty / 2")));
        schema.table("lines", table -> table.integer("half").virtualAs("qty / 2"));

        assertEquals(1, sql.size());
        assertTrue(sql.get(0).sql().startsWith("alter table"));
        assertEquals(1, number("select half from lines"));
    }

    @Test
    void generatedColumnsOnAnOldSqliteAreRefused() {
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));

        assertThrows(UnsupportedOperationException.class, () -> old.create("lines", table -> {
            table.integer("qty");
            table.integer("twice").virtualAs("qty * 2");
        }));
        assertFalse(schema.hasTable("lines"));
    }

    @Test
    void statementRunsAndPretendOnlyListsIt() {
        List<LoggedQuery> sql = schema.pretend(s -> s.statement("create table notes (body text)"));
        assertEquals("create table notes (body text)", sql.get(0).sql());
        assertFalse(schema.hasTable("notes"));

        schema.statement("create table notes (body text)");

        assertTrue(schema.hasTable("notes"));
    }

    @Test
    void driverNameLetsAMigrationBranch() {
        if ("pgsql".equals(schema.driverName())) {
            schema.statement("create table notes (body text collate \"C\")");
        } else {
            schema.statement("create table notes (body text)");
        }

        assertEquals("sqlite", schema.driverName());
        assertTrue(schema.hasTable("notes"));
    }

    @Test
    void aRawColumnIsCreatedAsWritten() {
        schema.create("prices", table -> {
            table.id();
            table.rawColumn("amount", "numeric(10, 2) default 1.5");
        });
        db.insert("insert into prices (id) values (1)");

        assertEquals(new BigDecimal("1.5"), new BigDecimal(db.scalar("select amount from prices").get().toString()));
    }
}
