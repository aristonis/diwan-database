package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.CursorPage;
import org.aristonis.diwan.database.query.LengthAwarePage;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.query.SimplePage;
import org.junit.jupiter.api.Test;

class PostgresCursorIntegrationTest {

    private static final String SERIES = "select pg_backend_pid() as pid, g from generate_series(1, 200000) g";
    private static final Duration QUICK = Duration.ofSeconds(10);

    /** A database with a one-column table for writes, and an admin connection to watch it from outside. */
    private record Fixture(Connection db, Connection watcher, OpenCount opened) {
    }

    /** Counts the physical connections the data source handed out and not yet closed. */
    private static final class OpenCount {
        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger total = new AtomicInteger();
    }

    private static Fixture fixture() {
        String name = PostgresServer.newDatabaseName();
        OpenCount count = new OpenCount();
        Connection db = Connection.builder().name("pgsql").dataSource(counting(PostgresServer.dataSource(name), count))
                .build();
        Connection watcher = Connection.builder().name("watch").dataSource(PostgresServer.dataSource(name)).build();
        db.statement("create table notes (body text)");
        return new Fixture(db, watcher, count);
    }

    private static DataSource counting(DataSource source, OpenCount count) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(source, method, args);
                    if (result instanceof java.sql.Connection connection) {
                        count.open.incrementAndGet();
                        count.total.incrementAndGet();
                        return countingConnection(connection, count);
                    }
                    return result;
                });
    }

    private static java.sql.Connection countingConnection(java.sql.Connection connection, OpenCount count) {
        AtomicInteger closed = new AtomicInteger();
        return (java.sql.Connection) Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close") && closed.getAndIncrement() == 0) {
                        count.open.decrementAndGet();
                    }
                    return invoke(connection, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static String state(Connection watcher, Object pid) {
        return String.valueOf(watcher.selectOne("select state from pg_stat_activity where pid = ?",
                ((Number) pid).intValue()).orElseThrow().get("state"));
    }

    private static long notes(Connection watcher) {
        return ((Number) watcher.selectOne("select count(*) as n from notes").orElseThrow().get("n")).longValue();
    }

    // ---- streaming and autocommit ----

    @Test
    void rootCursorStreamsInItsOwnTransactionAndClosesEarlyQuickly() {
        Fixture f = fixture();

        Object pid = assertTimeoutPreemptively(QUICK, () -> {
            try (QueryCursor<Map<String, Object>> cursor = f.db().cursor(SERIES)) {
                Iterator<Map<String, Object>> rows = cursor.iterator();
                Map<String, Object> first = rows.next();
                assertEquals(1, ((Number) first.get("g")).intValue());
                assertEquals(2, ((Number) rows.next().get("g")).intValue());
                // A buffered read would have finished its statement; a streaming portal stays open.
                assertEquals("idle in transaction", state(f.watcher(), first.get("pid")));
                return first.get("pid");
            }
        });

        assertEquals(0, f.opened().open.get());
        assertEquals(List.of(), f.watcher().select("select 1 from pg_stat_activity where pid = ?",
                ((Number) pid).intValue()));
    }

    @Test
    void sessionIsBackInAutocommitAfterAnEarlyClose() {
        Fixture f = fixture();

        f.db().session(c -> {
            Object pid;
            try (QueryCursor<Map<String, Object>> cursor = c.cursor(SERIES)) {
                pid = cursor.iterator().next().get("pid");
                assertEquals("idle in transaction", state(f.watcher(), pid));
            }
            assertEquals("idle", state(f.watcher(), pid));
            c.insert("insert into notes (body) values (?)", "after close");
            assertEquals(1L, notes(f.watcher()));
            return null;
        });
    }

    @Test
    void sessionIsBackInAutocommitAfterExhaustion() {
        Fixture f = fixture();

        f.db().session(c -> {
            int rows = 0;
            Object pid = null;
            QueryCursor<Map<String, Object>> cursor =
                    c.cursor("select pg_backend_pid() as pid, g from generate_series(1, 1200) g");
            Iterator<Map<String, Object>> iterator = cursor.iterator();
            while (iterator.hasNext()) {
                Map<String, Object> row = iterator.next();
                pid = row.get("pid");
                rows++;
            }
            assertEquals(1200, rows);
            assertFalse(iterator.hasNext());
            assertEquals("idle", state(f.watcher(), pid));
            c.insert("insert into notes (body) values (?)", "after exhaustion");
            assertEquals(1L, notes(f.watcher()));
            return null;
        });
    }

    // ---- inside a transaction ----

    @Test
    void cursorInsideATransactionReadsInItAndDoesNotCommit() {
        Fixture f = fixture();

        assertThrows(IllegalStateException.class, () -> f.db().transaction(c -> {
            c.insert("insert into notes (body) values (?)", "rolled back");
            try (QueryCursor<Map<String, Object>> cursor = c.cursor("select count(*) as n from notes")) {
                assertEquals(1L, ((Number) cursor.iterator().next().get("n")).longValue());
            }
            assertEquals(0L, notes(f.watcher()));
            throw new IllegalStateException("roll back");
        }));

        assertEquals(0L, notes(f.watcher()));
        assertEquals(0, f.opened().open.get());
    }

    @Test
    void statementWhileACursorIsOpenInATransactionIsRefused() {
        Fixture f = fixture();

        f.db().transaction(c -> {
            try (QueryCursor<Map<String, Object>> cursor = c.cursor(SERIES)) {
                cursor.iterator().next();
                IllegalStateException error = assertThrows(IllegalStateException.class,
                        () -> c.select("select 1"));
                assertEquals("A cursor is open on this connection; close it first", error.getMessage());
            }
            assertEquals(1, ((Number) c.selectOne("select 1 as one").orElseThrow().get("one")).intValue());
            return null;
        });
        assertEquals(0, f.opened().open.get());
    }

    // ---- failure and leaks ----

    @Test
    void failureOnALaterRowIsAQueryExceptionAndTheSessionStaysUsable() {
        Fixture f = fixture();

        f.db().session(c -> {
            QueryCursor<Map<String, Object>> cursor =
                    c.cursor("select 1 / (g - 150000) as v from generate_series(1, 200000) g");
            Iterator<Map<String, Object>> rows = cursor.iterator();
            assertThrows(QueryException.class, () -> {
                while (rows.hasNext()) {
                    rows.next();
                }
            });
            cursor.close();
            c.insert("insert into notes (body) values (?)", "after failure");
            assertEquals(1L, notes(f.watcher()));
            return null;
        });
        assertEquals(0, f.opened().open.get());
    }

    @Test
    void fiftyCursorsOfEveryEndingLeaveNoConnectionOpen() {
        Fixture f = fixture();

        for (int run = 0; run < 50; run++) {
            switch (run % 3) {
                case 0 -> {
                    try (QueryCursor<Map<String, Object>> cursor = f.db().cursor(SERIES)) {
                        cursor.iterator().next();
                    }
                }
                case 1 -> {
                    int rows = 0;
                    for (Map<String, Object> ignored : f.db().cursor("select g from generate_series(1, 600) g")) {
                        rows++;
                    }
                    assertEquals(600, rows);
                }
                default -> assertThrows(QueryException.class, () -> {
                    try (QueryCursor<Map<String, Object>> cursor =
                            f.db().cursor("select 1 / (g - 900) as v from generate_series(1, 1000) g")) {
                        cursor.forEach(row -> { });
                    }
                });
            }
            assertEquals(0, f.opened().open.get(), "open after run " + run);
        }
        assertEquals(1L, ((Number) f.watcher().selectOne("select count(*) as n from pg_stat_activity"
                + " where datname = current_database()").orElseThrow().get("n")).longValue());
    }

    // ---- chunking and pages over two order columns, one a timestamp ----

    private static QueryFactory events() {
        Connection connection = PostgresServer.newDatabase();
        PostgresSchema.of(connection).create("events", t -> {
            t.id();
            t.timestamp("at");
            t.string("name");
        });
        QueryFactory db = PostgresQuery.of(connection);
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0, 0, 123_456_000);
        for (int i = 1; i <= 7; i++) {
            // Pairs share a timestamp, so the second order column decides, and the order differs from id.
            rows.add(Map.of("at", base.minusMinutes(i / 2), "name", "e" + i));
        }
        db.table("events").insert(rows);
        return db;
    }

    private static List<Object> names(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("name")).toList();
    }

    private static final List<Object> ORDERED = List.of("e6", "e7", "e4", "e5", "e2", "e3", "e1");

    private static Builder ordered(QueryFactory db) {
        return db.table("events").orderBy("at").orderBy("id");
    }

    @Test
    void chunkLazyAndCursorFollowTwoOrderColumns() {
        QueryFactory db = events();
        List<Object> chunked = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        List<Object> lazy = new ArrayList<>();
        List<Object> streamed = new ArrayList<>();

        ordered(db).chunk(3, page -> {
            sizes.add(page.size());
            chunked.addAll(names(page));
            return true;
        });
        ordered(db).lazy(2).forEach(row -> lazy.add(row.get("name")));
        try (QueryCursor<Map<String, Object>> cursor = ordered(db).cursor()) {
            cursor.forEach(row -> streamed.add(row.get("name")));
        }
        List<Object> viaStream;
        try (java.util.stream.Stream<Map<String, Object>> stream = ordered(db).cursor().stream()) {
            viaStream = stream.map(row -> row.get("name")).collect(java.util.stream.Collectors.toList());
        }

        assertEquals(List.of(3, 3, 1), sizes);
        assertEquals(ORDERED, chunked);
        assertEquals(ORDERED, lazy);
        assertEquals(ORDERED, streamed);
        assertEquals(ORDERED, viaStream);
    }

    @Test
    void chunkByIdWalksTheKeyBothWays() {
        QueryFactory db = events();
        List<Object> up = new ArrayList<>();
        List<Object> down = new ArrayList<>();

        db.table("events").chunkById(3, page -> up.addAll(names(page)));
        db.table("events").chunkByIdDesc(3, page -> down.addAll(names(page)));

        assertEquals(List.of("e1", "e2", "e3", "e4", "e5", "e6", "e7"), up);
        assertEquals(List.of("e7", "e6", "e5", "e4", "e3", "e2", "e1"), down);
    }

    @Test
    void paginateAndSimplePaginateCountAndSlice() {
        QueryFactory db = events();

        LengthAwarePage page = ordered(db).paginate(3, 3);
        SimplePage simple = ordered(db).simplePaginate(3, 2);

        assertEquals(7L, page.total());
        assertEquals(3, page.lastPage());
        assertEquals(List.of("e1"), names(page.items()));
        assertFalse(page.hasMorePages());
        assertEquals(List.of("e5", "e2", "e3"), names(simple.items()));
        assertTrue(simple.hasMorePages());
    }

    @Test
    void paginateCountsGroupedAndUnionQueriesOnPostgres() {
        QueryFactory db = events();

        LengthAwarePage grouped = db.table("events").select("at").groupBy("at").orderBy("at").paginate(2);
        LengthAwarePage union = db.table("events").where("id", "<", 3)
                .union(db.table("events").where("id", ">", 5)).orderBy("id").paginate(10);

        assertEquals(4L, grouped.total());
        assertEquals(4L, union.total());
        assertEquals(List.of("e1", "e2", "e6", "e7"), names(union.items()));
    }

    @Test
    void cursorPaginateGoesForwardAndBackWithATimestampInTheToken() {
        QueryFactory db = events();
        List<List<Object>> forward = new ArrayList<>();
        List<CursorPage> pages = new ArrayList<>();

        CursorPage page = ordered(db).cursorPaginate(3);
        pages.add(page);
        forward.add(names(page.items()));
        while (page.nextCursor().isPresent()) {
            String token = page.nextCursor().get().encode();
            page = ordered(db).cursorPaginate(3, org.aristonis.diwan.database.query.Cursor.decode(token));
            pages.add(page);
            forward.add(names(page.items()));
        }
        CursorPage last = pages.get(pages.size() - 1);
        String back = last.previousCursor().orElseThrow().encode();
        CursorPage previous = ordered(db).cursorPaginate(3,
                org.aristonis.diwan.database.query.Cursor.decode(back));

        assertEquals(List.of(List.of("e6", "e7", "e4"), List.of("e5", "e2", "e3"), List.of("e1")), forward);
        assertEquals(List.of("e5", "e2", "e3"), names(previous.items()));
        assertTrue(previous.previousCursor().isPresent());
    }
}
