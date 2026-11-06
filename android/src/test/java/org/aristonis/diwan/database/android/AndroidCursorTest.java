package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.RowCursor;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.query.CursorPage;
import org.aristonis.diwan.database.query.LengthAwarePage;
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
public class AndroidCursorTest {

    private static final int ROWS = 5;
    private static final int LEAK_RUNS = 50;
    private static final long BUMP = 10L;

    private SQLiteDatabase database;
    private Connection db;
    private QueryFactory query;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table items (id integer primary key autoincrement, name text, qty integer)");
        query = SQLiteQuery.of(db);
        for (int i = 1; i <= ROWS; i++) {
            db.insert("insert into items (name, qty) values (?, ?)", "n" + i, i);
        }
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    private static List<Object> names(Iterable<Map<String, Object>> rows) {
        List<Object> names = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            names.add(row.get("name"));
        }
        return names;
    }

    @Test
    public void cursorReadsAllRowsInOrderWithColumnsInSelectOrder() {
        try (QueryCursor<Map<String, Object>> rows = db.cursor("select name, id from items order by id")) {
            Iterator<Map<String, Object>> it = rows.iterator();
            Map<String, Object> first = it.next();
            assertEquals(List.of("name", "id"), new ArrayList<>(first.keySet()));
            assertThrows(UnsupportedOperationException.class, () -> first.put("x", 1));
            List<Object> rest = names(() -> it);
            assertEquals("n1", first.get("name"));
            assertEquals(List.of("n2", "n3", "n4", "n5"), rest);
        }
    }

    @Test
    public void cursorStaysOpenAfterTheFirstRowWithoutATransaction() {
        try (QueryCursor<Map<String, Object>> rows = db.cursor("select * from items order by id")) {
            rows.iterator().next();

            assertFalse(database.inTransaction());
        }
    }

    @Test
    public void cursorClosesOnExhaustionAndOnEarlyClose() {
        Iterator<Map<String, Object>> full = db.cursor("select * from items").iterator();
        names(() -> full);
        assertFalse(full.hasNext());

        QueryCursor<Map<String, Object>> early = db.cursor("select * from items");
        Iterator<Map<String, Object>> earlyRows = early.iterator();
        earlyRows.next();
        early.close();
        early.close();
        assertFalse(earlyRows.hasNext());
        assertThrows(NoSuchElementException.class, earlyRows::next);
    }

    @Test
    public void writesMadeWhileTheCursorIsOpenPersistAfterClose() {
        try (QueryCursor<Map<String, Object>> rows = db.cursor("select id, qty from items order by id")) {
            for (Map<String, Object> row : rows) {
                db.affectingStatement("update items set qty = ? where id = ?",
                        ((Number) row.get("qty")).longValue() + BUMP, row.get("id"));
            }
        }

        assertFalse(database.inTransaction());
        assertEquals(15L + ROWS * BUMP, query.table("items").sum("qty").longValue());
    }

    @Test
    public void cursorInsideATransactionReadsItsRowsAndLeavesItOpen() {
        db.transaction(tx -> {
            tx.insert("insert into items (name, qty) values (?, ?)", "uncommitted", 99);
            List<Object> seen;
            try (QueryCursor<Map<String, Object>> rows = tx.cursor("select name from items order by id")) {
                seen = names(rows);
            }
            assertTrue(seen.contains("uncommitted"));
            assertTrue(database.inTransaction());
            tx.insert("insert into items (name, qty) values (?, ?)", "after", 100);
            return null;
        });

        assertEquals(ROWS + 2, query.table("items").count());
    }

    private static Throwable onAnotherThread(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        other.start();
        other.join();
        return failure.get();
    }

    @Test
    public void cursorRefusesUseFromAnotherThread() throws InterruptedException {
        try (QueryCursor<Map<String, Object>> rows = db.cursor("select * from items")) {
            Iterator<Map<String, Object>> it = rows.iterator();
            assertTrue(onAnotherThread(it::next) instanceof IllegalStateException);
        }
    }

    @Test
    public void driverCursorRefusesOtherThreadsAndKeepsServingItsOwner() throws InterruptedException {
        DriverSession session = AndroidSqliteDriver.of(database).open();
        try (RowCursor rows = session.cursor("select name from items order by id", new ArrayList<>())) {
            assertTrue(onAnotherThread(rows::next) instanceof IllegalStateException);
            assertTrue(onAnotherThread(rows::hasNext) instanceof IllegalStateException);
            assertTrue(onAnotherThread(rows::close) instanceof IllegalStateException);

            assertEquals("n1", rows.next().get("name"));
            assertFalse(rows.isClosed());
        } finally {
            session.close();
        }
    }

    @Test
    public void cursorIteratesOnlyOnceAndRefusesRemove() {
        try (QueryCursor<Map<String, Object>> rows = db.cursor("select * from items")) {
            Iterator<Map<String, Object>> it = rows.iterator();
            assertThrows(IllegalStateException.class, rows::iterator);
            assertThrows(IllegalStateException.class, rows::stream);
            assertThrows(UnsupportedOperationException.class, it::remove);
        }
    }

    @Test
    public void manyEarlyClosedCursorsLeakNothing() {
        for (int i = 0; i < LEAK_RUNS; i++) {
            try (QueryCursor<Map<String, Object>> rows = db.cursor("select * from items order by id")) {
                rows.iterator().next();
            }
        }

        assertFalse(database.inTransaction());
        db.affectingStatement("update items set qty = 0");
        assertEquals(0L, query.table("items").sum("qty").longValue());
    }

    @Test
    public void builderCursorChunkAndLazyWork() {
        try (QueryCursor<Map<String, Object>> rows = query.table("items").orderBy("id").cursor()) {
            assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), names(rows));
        }
        List<Integer> sizes = new ArrayList<>();
        query.table("items").orderBy("id").chunk(2, page -> sizes.add(page.size()));
        assertEquals(List.of(2, 2, 1), sizes);
        List<Object> byId = new ArrayList<>();
        query.table("items").chunkById(2, page -> byId.addAll(names(page)));
        assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), byId);
        assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), names(query.table("items").orderBy("id").lazy(2)));
        assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), names(query.table("items").lazyById(2)));
    }

    @Test
    public void paginateAndCursorPaginateWork() {
        LengthAwarePage page = query.table("items").orderBy("id").paginate(2, 2);
        assertEquals(List.of("n3", "n4"), names(page.items()));
        assertEquals(ROWS, page.total());
        assertEquals(3, page.lastPage());

        CursorPage first = query.table("items").orderBy("id").cursorPaginate(2);
        assertEquals(List.of("n1", "n2"), names(first.items()));
        CursorPage second = query.table("items").orderBy("id").cursorPaginate(2, first.nextCursor().orElseThrow());
        assertEquals(List.of("n3", "n4"), names(second.items()));
    }
}
