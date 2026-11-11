package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Cursor;
import org.aristonis.diwan.database.query.CursorPage;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.Test;

/** Cursor pagination over a real PostgreSQL timetz column, which comes back as an OffsetTime. */
class PostgresTimetzCursorTest {

    private static final int PER_PAGE = 2;

    private static OffsetTime at(int hour, int offsetHours) {
        return OffsetTime.of(hour, 0, 0, 0, ZoneOffset.ofHours(offsetHours));
    }

    private static QueryFactory table(List<OffsetTime> times) {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table shifts (id serial primary key, starts timetz not null)");
        QueryFactory factory = PostgresQuery.of(db);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OffsetTime time : times) {
            rows.add(Map.of("starts", time));
        }
        factory.table("shifts").insert(rows);
        return factory;
    }

    private static Builder ordered(QueryFactory db) {
        return db.table("shifts").orderBy("starts").orderBy("id");
    }

    private static List<Long> ids(CursorPage page) {
        List<Long> values = new ArrayList<>();
        page.items().forEach(row -> values.add(((Number) row.get("id")).longValue()));
        return values;
    }

    private static List<Long> walk(QueryFactory db) {
        List<Long> all = new ArrayList<>();
        CursorPage page = ordered(db).cursorPaginate(PER_PAGE);
        all.addAll(ids(page));
        while (page.nextCursor().isPresent()) {
            page = ordered(db).cursorPaginate(PER_PAGE, Cursor.decode(page.nextCursor().get().encode()));
            all.addAll(ids(page));
        }
        return all;
    }

    private static List<Long> serverOrder(QueryFactory db) {
        List<Long> expected = new ArrayList<>();
        ordered(db).get().forEach(row -> expected.add(((Number) row.get("id")).longValue()));
        return expected;
    }

    @Test
    void aTimetzColumnPagesInOrder() {
        QueryFactory db = table(List.of(at(9, 0), at(10, 0), at(11, 0), at(12, 0), at(13, 0)));

        CursorPage first = ordered(db).cursorPaginate(PER_PAGE);
        CursorPage second = ordered(db).cursorPaginate(PER_PAGE, Cursor.decode(first.nextCursor().orElseThrow().encode()));

        assertEquals(List.of(1L, 2L), ids(first));
        assertEquals(List.of(3L, 4L), ids(second));
    }

    @Test
    void sameInstantDifferentOffsetsAcrossAPageBoundaryAreNeitherLostNorRepeated() {
        // 10:00+00, 11:00+01 and 12:00+02 are one instant; the boundary falls inside that group.
        QueryFactory db = table(List.of(at(9, 0), at(10, 0), at(11, 1), at(12, 2), at(13, 0)));

        List<Long> walked = walk(db);

        assertEquals(serverOrder(db), walked);
        assertEquals(5, walked.stream().distinct().count());
    }
}
