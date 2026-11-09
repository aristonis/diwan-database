package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DatabaseException;
import org.aristonis.diwan.database.exception.ForeignKeyViolationException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Truncate over keys declared {@code on delete restrict}, which SQLite checks row by row. */
class SQLiteTruncateRestrictTest {

    @TempDir
    Path folder;

    private Connection connection;
    private QueryFactory db;

    @BeforeEach
    void createTables() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("restrict.db"));
        dataSource.setEnforceForeignKeys(true);
        dataSource.setTransactionMode("IMMEDIATE");
        connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        connection.statement("create table categories (id integer primary key autoincrement,"
                + " parent_id integer references categories (id) on delete restrict)");
        connection.statement("create table templates (id integer primary key autoincrement,"
                + " category_id integer not null references categories (id) on delete restrict,"
                + " base_id integer references templates (id) on delete restrict)");
        db.table("categories").insert(Map.of("id", 1));
        db.table("categories").insert(Map.of("id", 2, "parent_id", 1));
        db.table("templates").insert(Map.of("id", 1, "category_id", 2));
        db.table("templates").insert(Map.of("id", 2, "category_id", 2, "base_id", 1));
    }

    @Test
    void truncateManyEmptiesSelfReferencingRestrictTables() {
        db.truncate(List.of("categories", "templates"));

        assertEquals(0L, db.table("categories").count());
        assertEquals(0L, db.table("templates").count());
    }

    @Test
    void truncateOfOneSelfReferencingRestrictTableEmptiesIt() {
        db.table("templates").truncate();

        assertEquals(0L, db.table("templates").count());
        assertEquals(1L, db.table("templates").insertGetId(Map.of("category_id", 1)));
    }

    @Test
    void foreignKeysStayEnforcedAfterwards() {
        db.truncate(List.of("categories", "templates"));

        assertThrows(ForeignKeyViolationException.class, () -> db.table("templates").insert(Map.of("category_id", 99)));
    }

    @Test
    void aTableOutsideTheListThatStillRefersFailsAndRollsBack() {
        ForeignKeyViolationException error = assertThrows(ForeignKeyViolationException.class,
                () -> db.truncate(List.of("categories")));

        String reason = ((org.aristonis.diwan.database.exception.DriverException) error.getCause())
                .databaseMessage().orElse("");
        assertTrue(reason.contains("templates"), reason);
        assertEquals(2L, db.table("categories").count());
        assertEquals(2L, db.table("templates").count());
        assertThrows(ForeignKeyViolationException.class, () -> db.table("templates").insert(Map.of("category_id", 99)));
    }

    @Test
    void anOrphanLeftBeforeInAnUnrelatedTableDoesNotBlockTheTruncate() {
        connection.statement("create table owners (id integer primary key)");
        connection.statement("create table pets (owner_id integer references owners (id))");
        connection.session(session -> {
            session.statement("pragma foreign_keys = off");
            session.insert("insert into pets values (42)");
            session.statement("pragma foreign_keys = on");
            return null;
        });

        db.truncate(List.of("categories", "templates"));

        assertEquals(0L, db.table("categories").count());
        assertEquals(1L, db.table("pets").count());
    }

    @Test
    void insideATransactionASelfReferencingRestrictKeyStillFails() {
        assertThrows(DatabaseException.class, () -> connection.transaction(tx -> {
            SQLiteQuery.of(tx).truncate(List.of("categories", "templates"));
            return null;
        }));

        assertEquals(2L, db.table("categories").count());
    }

    @Test
    void aCascadeChildOutsideTheListIsStillEmptiedByItsKey() {
        connection.statement("create table invoices (id integer primary key)");
        connection.statement("create table lines (invoice_id integer references invoices (id) on delete cascade)");
        connection.statement("create table memos (invoice_id integer references invoices (id) on delete set null)");
        db.table("invoices").insert(Map.of("id", 1));
        db.table("lines").insert(Map.of("invoice_id", 1));
        db.table("memos").insert(Map.of("invoice_id", 1));

        db.truncate(List.of("invoices"));
        db.table("invoices").insert(Map.of("id", 5));
        db.table("invoices").truncate();

        assertEquals(0L, db.table("lines").count());
        assertEquals(1L, db.table("memos").whereNull("invoice_id").count());
    }
}
