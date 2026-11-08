package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DatabaseException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteTruncateManyTest {

    @TempDir
    Path folder;

    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void createTables() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("truncate.db"));
        dataSource.setEnforceForeignKeys(true);
        connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        connection.statement("create table invoices (id integer primary key autoincrement, label text)");
        connection.statement("create table lines (id integer primary key autoincrement,"
                + " invoice_id integer not null references invoices (id), cents integer)");
        connection.statement("create table notes (id integer primary key autoincrement,"
                + " invoice_id integer not null references invoices (id))");
        db.table("invoices").insert(Map.of("label", "a"));
        db.table("lines").insert(Map.of("invoice_id", 1, "cents", 5));
        db.table("notes").insert(Map.of("invoice_id", 1));
    }

    @Test
    void emptiesEveryListedTableAndRestartsTheirIds() {
        db.truncate(List.of("invoices", "lines", "notes"));

        assertEquals(0L, db.table("invoices").count());
        assertEquals(0L, db.table("lines").count());
        assertEquals(0L, db.table("notes").count());
        assertEquals(1L, db.table("invoices").insertGetId(Map.of("label", "again")));
    }

    @Test
    void theOrderOfTheTablesDoesNotMatter() {
        db.truncate(List.of("invoices", "lines", "notes"), true);

        assertEquals(0L, db.table("invoices").count());
        assertEquals(0L, db.table("lines").count());
    }

    @Test
    void foreignKeysStayEnforcedAfterwards() {
        db.truncate(List.of("invoices", "lines", "notes"));

        assertThrows(DatabaseException.class, () -> db.table("lines").insert(Map.of("invoice_id", 99, "cents", 1)));
    }

    @Test
    void aTableOutsideTheListThatStillRefersFailsAndRollsBack() {
        assertThrows(DatabaseException.class, () -> db.truncate(List.of("invoices", "lines")));

        assertEquals(1L, db.table("invoices").count());
        assertEquals(1L, db.table("lines").count());
        assertEquals(1L, db.table("notes").count());
    }

    @Test
    void aListedParentBeforeItsChildSucceeds() {
        db.truncate(List.of("invoices", "lines", "notes"));

        assertEquals(0L, db.table("invoices").count());
        assertEquals(0L, db.table("lines").count());
    }

    @Test
    void aCycleBetweenListedTablesStillWorks() {
        connection.statement("create table ping (id integer primary key, pong_id integer"
                + " references pong (id) deferrable initially deferred)");
        connection.statement("create table pong (id integer primary key, ping_id integer"
                + " references ping (id) deferrable initially deferred)");

        db.truncate(List.of("ping", "pong"));

        assertEquals(0L, db.table("ping").count());
        assertEquals(0L, db.table("pong").count());
    }

    @Test
    void anEmptyListRunsNothing() {
        db.truncate(List.of());

        assertEquals(1L, db.table("invoices").count());
    }

    @Test
    void aNullListOrABadNameIsRefused() {
        assertThrows(NullPointerException.class, () -> db.truncate(null));
        assertThrows(IllegalArgumentException.class, () -> db.truncate(Arrays.asList("lines", null)));
        assertThrows(IllegalArgumentException.class, () -> db.truncate(List.of("lines", "  ")));
        assertEquals(1L, db.table("lines").count());
    }

    @Test
    void cascadeEmptiesEveryTableThatReferToTheListedOneThroughAChain() {
        connection.statement("create table memos (id integer primary key autoincrement,"
                + " line_id integer not null references lines (id))");
        db.table("memos").insert(Map.of("line_id", 1));

        db.truncate(List.of("invoices"), true);

        assertEquals(0L, db.table("invoices").count());
        assertEquals(0L, db.table("lines").count());
        assertEquals(0L, db.table("notes").count());
        assertEquals(0L, db.table("memos").count());
        assertEquals(1L, db.table("lines").insertGetId(Map.of("invoice_id", db.table("invoices")
                .insertGetId(Map.of("label", "b")), "cents", 1)));
    }

    @Test
    void withoutCascadeOnlyTheListedTablesAreEmptied() {
        assertThrows(DatabaseException.class, () -> db.truncate(List.of("invoices"), false));

        assertEquals(1L, db.table("invoices").count());
        assertEquals(1L, db.table("lines").count());
    }

    @Test
    void cascadeLeavesUnrelatedTablesAlone() {
        connection.statement("create table loose (id integer primary key)");
        db.table("loose").insert(Map.of("id", 7));

        db.truncate(List.of("invoices"), true);

        assertEquals(1L, db.table("loose").count());
    }

    @Test
    void cascadeAndTruncateUseThePrefixOnce() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("prefixed.db"));
        dataSource.setEnforceForeignKeys(true);
        Connection prefixed = Connection.builder().name("prefixed").dataSource(dataSource).tablePrefix("app_").build();
        prefixed.statement("create table app_invoices (id integer primary key autoincrement, label text)");
        prefixed.statement("create table app_lines (id integer primary key autoincrement,"
                + " invoice_id integer not null references app_invoices (id))");
        QueryFactory app = SQLiteQuery.of(prefixed);
        app.table("invoices").insert(Map.of("label", "a"));
        app.table("lines").insert(Map.of("invoice_id", 1));

        app.truncate(List.of("invoices"), true);

        assertEquals(0L, app.table("invoices").count());
        assertEquals(0L, app.table("lines").count());
        app.table("invoices").insert(Map.of("label", "b"));
        app.truncate(List.of("lines", "invoices"));
        assertEquals(0L, app.table("invoices").count());
    }
}
