package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteChecksRebuildTest {

    @TempDir
    Path folder;

    private Connection db;
    private Schema schema;

    @BeforeEach
    void createLedger() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("ledger.db"));
        dataSource.setEnforceForeignKeys(true);
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        schema = SQLiteSchema.of(db);
        schema.create("accounts", table -> table.id());
        schema.create("entries", table -> {
            table.id();
            table.integer("account_id");
            table.integer("qty");
            table.integer("price").check("price >= 0");
            table.integer("total").storedAs("qty * price");
            table.integer("twice").virtualAs("qty * 2");
            table.enumColumn("side", Arrays.asList("debit", "credit")).check("side <> 'credit' or qty > 0");
            table.string("note").nullable();
            table.check("entries_qty_check", "qty < 1000");
        });
        db.insert("insert into accounts (id) values (1)");
        db.insert("insert into entries (account_id, qty, price, side) values (1, 2, 5, 'debit'), (1, 3, 1, 'credit')");
    }

    private long number(String sql) {
        return ((Number) db.scalar(sql).get()).longValue();
    }

    private void assertEverythingCarried() {
        assertEquals(2, number("select count(*) from entries"));
        assertEquals(10, number("select total from entries where id = 1"));
        assertEquals(6, number("select twice from entries where id = 2"));
        assertThrows(QueryException.class, () -> db.insert(
                "insert into entries (account_id, qty, price, side) values (1, 1, -1, 'debit')"));
        assertThrows(QueryException.class, () -> db.insert(
                "insert into entries (account_id, qty, price, side) values (1, 5000, 1, 'debit')"));
        assertThrows(QueryException.class, () -> db.insert(
                "insert into entries (account_id, qty, price, side) values (1, 1, 1, 'other')"));
        assertThrows(QueryException.class, () -> db.insert(
                "insert into entries (account_id, qty, price, side) values (1, 0, 1, 'credit')"));
    }

    @Test
    void aKeyChangeCarriesChecksGeneratedColumnsAndTheEnum() {
        schema.table("entries", table -> table.foreign("account_id").references("id").on("accounts"));

        assertEverythingCarried();
        assertEquals(1, schema.foreignKeys("entries").size());
    }

    @Test
    void aChangeOfAnotherColumnCarriesEverything() {
        schema.table("entries", table -> table.text("note").nullable().change());

        assertEverythingCarried();
    }

    @Test
    void changingAGeneratedColumnWritesTheNewExpression() {
        schema.table("entries", table -> table.integer("total").storedAs("qty * price * 10").change());

        assertEquals(100, number("select total from entries where id = 1"));
    }

    @Test
    void addingACheckTestsTheRowsAlreadyThere() {
        assertThrows(QueryException.class,
                () -> schema.table("entries", table -> table.check("entries_price_check", "price > 3")));
        assertEquals(2, number("select count(*) from entries"));

        schema.table("entries", table -> table.check("entries_price_check", "price > 0"));
        assertThrows(QueryException.class, () -> db.insert(
                "insert into entries (account_id, qty, price, side) values (1, 1, 0, 'debit')"));
    }

    @Test
    void addingAStoredColumnComputesItForOldRows() {
        schema.table("entries", table -> table.integer("cost").storedAs("price * 100"));

        assertEquals(500, number("select cost from entries where id = 1"));
        assertEquals(2, number("select count(*) from entries"));
    }

    @Test
    void addingAColumnWithACheckRebuildsAndTestsIt() {
        assertThrows(QueryException.class,
                () -> schema.table("entries", table -> table.integer("rank").defaultValue(0).check("rank > 0")));

        schema.table("entries", table -> table.integer("rank").defaultValue(1).check("rank > 0"));
        assertEquals(1, number("select rank from entries where id = 2"));
    }

    @Test
    void handWrittenChecksWithTrickyTextAreCarried() {
        db.statement("create table notes (id integer primary key, body text check (body <> ')''x'),"
                + " constraint \"has_body\" check (length(body) > 0), check (id < 100))");
        db.insert("insert into notes (id, body) values (1, 'hi')");

        schema.table("notes", table -> table.string("extra").nullable().check("extra <> ''"));

        assertThrows(QueryException.class, () -> db.insert("insert into notes (id, body) values (2, ')''x')"));
        assertThrows(QueryException.class, () -> db.insert("insert into notes (id, body) values (3, '')"));
        assertThrows(QueryException.class, () -> db.insert("insert into notes (id, body) values (200, 'a')"));
        schema.table("notes", table -> table.dropCheck("has_body"));
        db.insert("insert into notes (id, body) values (4, '')");
    }

    @Test
    void renamingOrDroppingAMentionedColumnOnTheRebuildPathIsRefused() {
        assertThrows(UnsupportedOperationException.class, () -> schema.table("entries", table -> {
            table.text("note").nullable().change();
            table.renameColumn("qty", "quantity");
        }));
        assertThrows(UnsupportedOperationException.class, () -> schema.table("entries", table -> {
            table.text("note").nullable().change();
            table.dropColumn("price");
        }));
        assertTrue(schema.hasColumn("entries", "qty"));
    }

    @Test
    void aNativeRenameFollowedByARebuildIsRefusedWhenMentioned() {
        assertThrows(UnsupportedOperationException.class, () -> schema.table("entries", table -> {
            table.renameColumn("qty", "quantity");
            table.foreign("account_id").references("id").on("accounts");
        }));
        assertTrue(schema.hasColumn("entries", "qty"));
    }

    @Test
    void droppingAColumnWithOnlyItsOwnCheckIsAllowedOnTheRebuildPath() {
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));
        schema.create("tags", table -> {
            table.id();
            table.integer("weight").check("weight > 0");
        });

        old.table("tags", table -> table.dropColumn("weight"));

        assertTrue(!schema.hasColumn("tags", "weight"));
    }

    @Test
    void functionAndConstraintNamesDoNotBlockARename() {
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));
        schema.create("codes", table -> {
            table.id();
            table.string("code").check("length(code) = 3");
            table.integer("length");
            table.integer("rank");
            table.check("rank", "code <> 'xxx'");
        });

        old.table("codes", table -> {
            table.renameColumn("length", "size");
            table.renameColumn("rank", "position");
        });

        assertTrue(schema.hasColumn("codes", "size"));
        assertTrue(schema.hasColumn("codes", "position"));
    }

    @Test
    void aQualifiedNameInACheckStillBlocksTheRename() {
        Schema old = Schema.of(db, new SQLiteSchemaGrammar("3.22.0"));
        db.statement("create table pairs (id integer primary key, lo integer, hi integer, check (pairs.lo < hi))");

        assertThrows(UnsupportedOperationException.class,
                () -> old.table("pairs", table -> table.renameColumn("lo", "low")));
    }

    @Test
    void aCollationIsStillRefused() {
        db.statement("create table names (id integer primary key, name text collate nocase)");

        assertThrows(UnsupportedOperationException.class,
                () -> schema.table("names", table -> table.string("x").nullable().check("x <> ''")));
    }
}
