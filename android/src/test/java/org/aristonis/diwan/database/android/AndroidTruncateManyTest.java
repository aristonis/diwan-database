package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.database.sqlite.SQLiteDatabase;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DatabaseException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidTruncateManyTest {

    private SQLiteDatabase database;
    private QueryFactory query;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        database.setForeignKeyConstraintsEnabled(true);
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table invoices (id integer primary key autoincrement, label text)");
        db.statement("create table lines (id integer primary key autoincrement,"
                + " invoice_id integer not null references invoices (id))");
        db.statement("create table notes (id integer primary key autoincrement,"
                + " invoice_id integer not null references invoices (id))");
        query = SQLiteQuery.of(db);
        query.table("invoices").insert(Map.of("label", "a"));
        query.table("lines").insert(Map.of("invoice_id", 1));
        query.table("notes").insert(Map.of("invoice_id", 1));
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    @Test
    public void truncateManyEmptiesEveryTableAndRestartsIds() {
        query.truncate(List.of("invoices", "lines", "notes"));

        assertEquals(0L, query.table("invoices").count());
        assertEquals(0L, query.table("lines").count());
        assertEquals(1L, query.table("invoices").insertGetId(Map.of("label", "again")));
    }

    @Test
    public void aReferringTableOutsideTheListFailsAndRollsBack() {
        assertThrows(DatabaseException.class, () -> query.truncate(List.of("invoices", "lines")));

        assertEquals(1L, query.table("invoices").count());
        assertEquals(1L, query.table("lines").count());
    }

    @Test
    public void anEmptyListRunsNothing() {
        query.truncate(List.of());

        assertEquals(1L, query.table("invoices").count());
    }

    @Test
    public void truncateWorksWhenTheDatabaseHasNoSequenceTable() {
        SQLiteDatabase plain = SQLiteDatabase.create(null);
        try {
            Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(plain)).build();
            db.statement("create table plain (id integer primary key)");
            QueryFactory factory = SQLiteQuery.of(db);
            factory.table("plain").insert(Map.of("id", 1));

            factory.table("plain").truncate();
            factory.truncate(List.of("plain"));

            assertEquals(0L, factory.table("plain").count());
        } finally {
            plain.close();
        }
    }

    @Test
    public void cascadeEmptiesEveryReferringTableThroughAChain() {
        query.connection().statement("create table memos (id integer primary key autoincrement,"
                + " line_id integer not null references lines (id))");
        query.table("memos").insert(Map.of("line_id", 1));

        query.truncate(List.of("invoices"), true);

        assertEquals(0L, query.table("invoices").count());
        assertEquals(0L, query.table("lines").count());
        assertEquals(0L, query.table("notes").count());
        assertEquals(0L, query.table("memos").count());
    }

    @Test
    public void withoutCascadeAReferringTableStillFailsAndRollsBack() {
        assertThrows(DatabaseException.class, () -> query.truncate(List.of("invoices"), false));

        assertEquals(1L, query.table("invoices").count());
    }

    @Test
    public void truncateManyEmptiesASelfReferencingRestrictTable() {
        query.connection().statement("create table categories (id integer primary key autoincrement,"
                + " parent_id integer references categories (id) on delete restrict)");
        query.table("categories").insert(Map.of("id", 1));
        query.table("categories").insert(Map.of("id", 2, "parent_id", 1));

        query.truncate(List.of("categories", "invoices", "lines", "notes"));

        assertEquals(0L, query.table("categories").count());
        assertThrows(DatabaseException.class, () -> query.table("categories").insert(Map.of("parent_id", 99)));
    }

    @Test
    public void aReferringTableOutsideTheListStillFailsWithForeignKeysOff() {
        assertThrows(DatabaseException.class, () -> query.truncate(List.of("invoices", "lines")));

        assertEquals(1L, query.table("notes").count());
        assertEquals(1L, query.table("invoices").count());
    }
}
