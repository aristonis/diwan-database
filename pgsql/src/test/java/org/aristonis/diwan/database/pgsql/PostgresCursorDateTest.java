package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Cursor;
import org.aristonis.diwan.database.query.CursorPage;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

/** Cursor pagination over a real PostgreSQL date column, which comes back as a LocalDate. */
class PostgresCursorDateTest {

    private static final int PER_PAGE = 2;

    private static QueryFactory journal() {
        Connection db = PostgresServer.newDatabase();
        PostgresSchema.of(db).create("journal", t -> {
            t.id();
            t.date("day");
        });
        QueryFactory factory = PostgresQuery.of(db);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            entries.add(Map.of("day", LocalDate.of(2024, 3, i / 2 + 1)));
        }
        factory.table("journal").insert(entries);
        return factory;
    }

    private static Builder ordered(QueryFactory db) {
        return db.table("journal").orderBy("day", "desc").orderBy("id", "desc");
    }

    private static List<Object> ids(CursorPage page) {
        List<Object> values = new ArrayList<>();
        page.items().forEach(row -> values.add(((Number) row.get("id")).longValue()));
        return values;
    }

    @Test
    void datePagesWalkForwardAndBack() {
        QueryFactory db = journal();
        List<List<Object>> forward = new ArrayList<>();
        List<CursorPage> pages = new ArrayList<>();

        CursorPage page = ordered(db).cursorPaginate(PER_PAGE);
        pages.add(page);
        forward.add(ids(page));
        while (page.nextCursor().isPresent()) {
            page = ordered(db).cursorPaginate(PER_PAGE, Cursor.decode(page.nextCursor().get().encode()));
            pages.add(page);
            forward.add(ids(page));
        }
        List<List<Object>> backward = new ArrayList<>();
        CursorPage back = pages.get(pages.size() - 1);
        backward.add(0, ids(back));
        while (back.previousCursor().isPresent()) {
            back = ordered(db).cursorPaginate(PER_PAGE, Cursor.decode(back.previousCursor().get().encode()));
            backward.add(0, ids(back));
        }

        assertEquals(List.of(List.<Object>of(7L, 6L), List.<Object>of(5L, 4L), List.<Object>of(3L, 2L),
                List.<Object>of(1L)), forward);
        assertEquals(forward, backward);
    }

    @Test
    void datePagesWalkBack() {
        QueryFactory db = journal();
        CursorPage page = ordered(db).cursorPaginate(PER_PAGE);
        page = ordered(db).cursorPaginate(PER_PAGE, Cursor.decode(page.nextCursor().orElseThrow().encode()));
        page = ordered(db).cursorPaginate(PER_PAGE, Cursor.decode(page.nextCursor().orElseThrow().encode()));
        assertEquals(List.<Object>of(3L, 2L), ids(page));

        CursorPage previous = ordered(db).cursorPaginate(PER_PAGE,
                Cursor.decode(page.previousCursor().orElseThrow().encode()));

        assertEquals(List.<Object>of(5L, 4L), ids(previous));
    }
}
