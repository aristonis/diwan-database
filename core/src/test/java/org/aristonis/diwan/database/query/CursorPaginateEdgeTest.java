package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.RecordingConnection.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Cursor pagination golden SQL for mixed orders, expression names, empty pages and unions with joins. */
class CursorPaginateEdgeTest {

    private static final String TS = "2024-05-01 10:00:00";

    private final RecordingConnection db = new RecordingConnection();

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void mixedOrdersFlipTheOperatorPerColumn() {
        Builder query = db.db().table("t").orderBy("a").orderBy("b", "desc").orderBy("c");

        query.cursorPaginate(2, new Cursor(params("a", 1, "b", 2, "c", 3), true));

        assertEquals("select * from \"t\" where (\"a\" > ? or (\"a\" = ? and (\"b\" < ? or (\"b\" = ? and "
                + "(\"c\" > ?))))) order by \"a\" asc, \"b\" desc, \"c\" asc limit 3", db.last().sql());
        assertEquals(List.of(1L, 1L, 2L, 2L, 3L), db.last().bindings());
    }

    @Test
    void mixedOrdersAreFlippedAgainForThePreviousPage() {
        Builder query = db.db().table("t").orderBy("a").orderBy("b", "desc").orderBy("c");

        query.cursorPaginate(2, new Cursor(params("a", 1, "b", 2, "c", 3), false));

        assertEquals("select * from \"t\" where (\"a\" < ? or (\"a\" = ? and (\"b\" > ? or (\"b\" = ? and "
                + "(\"c\" < ?))))) order by \"a\" desc, \"b\" asc, \"c\" desc limit 3", db.last().sql());
        assertEquals(List.of(1L, 1L, 2L, 2L, 3L), db.last().bindings());
    }

    @Test
    void anOrderColumnWithParenthesesIsQuotedAsAName() {
        String column = "id) or 1=1 or (id";
        Builder query = db.db().table("foobar").orderBy(column).orderBy("id");

        query.cursorPaginate(16, new Cursor(params(column, "bar", "id", "foo"), true));

        assertEquals("select * from \"foobar\" where (\"id) or 1=1 or (id\" > ? or (\"id) or 1=1 or (id\" = ? and "
                + "(\"id\" > ?))) order by \"id) or 1=1 or (id\" asc, \"id\" asc limit 17", db.last().sql());
        assertEquals(List.of("bar", "bar", "foo"), db.last().bindings());
    }

    @Test
    void anEmptyFirstPageHasNoCursors() {
        CursorPage page = db.db().table("t").orderBy("test").cursorPaginate(15);

        assertTrue(page.items().isEmpty());
        assertEquals(15, page.perPage());
        assertFalse(page.nextCursor().isPresent());
        assertFalse(page.previousCursor().isPresent());
    }

    @Test
    void anEmptyPageAfterACursorHasNoCursorsEither() {
        Builder query = db.db().table("t").orderBy("test");

        CursorPage next = query.cursorPaginate(15, new Cursor(params("test", 9), true));
        CursorPage previous = query.cursorPaginate(15, new Cursor(params("test", 9), false));

        assertTrue(next.items().isEmpty());
        assertFalse(next.nextCursor().isPresent());
        assertFalse(next.previousCursor().isPresent());
        assertTrue(previous.items().isEmpty());
        assertFalse(previous.nextCursor().isPresent());
        assertFalse(previous.previousCursor().isPresent());
    }

    @Test
    void unionPartsWithJoinsAndSelectBindingsKeepTheirBindingOrder() {
        Builder query = db.db().table("videos").select("id", "created_at").selectRaw("? as type", List.of("video"));
        query.union(db.db().table("news").select("id", "created_at").selectRaw("? as type", List.of("news"))
                .join("authors", j -> j.on("authors.id", "=", "news.author_id").where("authors.active", true)));
        query.orderBy("created_at");

        query.cursorPaginate(16, new Cursor(params("created_at", TS), true));

        assertEquals("(select \"id\", \"created_at\", ? as type from \"videos\" where (\"created_at\" > ?)) union "
                + "(select \"id\", \"created_at\", ? as type from \"news\" inner join \"authors\" on "
                + "\"authors\".\"id\" = \"news\".\"author_id\" and \"authors\".\"active\" = ? where "
                + "(\"created_at\" > ?)) order by \"created_at\" asc limit 17", db.last().sql());
        assertEquals(List.of("video", TS, "news", true, TS), db.last().bindings());
    }

    @Test
    void aRawOrderExpressionBesideTheCursorColumnLeavesTheUnionConditionsAlone() {
        Builder query = db.db().table("videos").select("id", "is_published", "start_time as created_at")
                .selectRaw("'video' as type").where("is_published", true);
        query.union(db.db().table("news").select("id", "is_published", "created_at").selectRaw("'news' as type")
                .where("is_published", true));
        query.orderByRaw("case when (id = 3 and type=\"news\" then 0 else 1 end)").orderBy("created_at");

        query.cursorPaginate(16, new Cursor(params("created_at", TS), true));

        assertEquals("(select \"id\", \"is_published\", \"start_time\" as \"created_at\", 'video' as type from "
                + "\"videos\" where \"is_published\" = ? and (\"start_time\" > ?)) union (select \"id\", "
                + "\"is_published\", \"created_at\", 'news' as type from \"news\" where \"is_published\" = ? and "
                + "(\"created_at\" > ?)) order by case when (id = 3 and type=\"news\" then 0 else 1 end), "
                + "\"created_at\" asc limit 17", db.last().sql());
        assertEquals(List.of(true, TS, true, TS), db.last().bindings());
    }

    @Test
    void theNextCursorOfAMixedOrderPageHoldsEveryOrderValue() {
        db.returns("select * from \"t\" order by \"a\" asc, \"b\" desc limit 3",
                List.of(row("a", 1, "b", 9), row("a", 1, "b", 8), row("a", 2, "b", 7)));

        CursorPage page = db.db().table("t").orderBy("a").orderBy("b", "desc").cursorPaginate(2);

        assertEquals(params("a", 1L, "b", 8L), page.nextCursor().orElseThrow().parameters());
    }
}
