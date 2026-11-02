package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.RecordingConnection.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageQueriesTest {

    private final RecordingConnection db = new RecordingConnection();

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ---- count, golden SQL from Laravel 13.39.0's runPaginationCountQuery with the base grammar ----

    @Test
    void countDropsColumnsOrdersLimitAndOffset() {
        Builder query = db.db().table("users").select("name").where("a", 1).orderBy("name").limit(5).offset(10);

        query.getCountForPagination();

        assertEquals(new RecordingConnection.Call("select",
                "select count(*) as \"aggregate\" from \"users\" where \"a\" = ?", List.of(1)), db.last());
        assertEquals("select \"name\" from \"users\" where \"a\" = ? order by \"name\" asc limit 5 offset 10",
                query.toSql());
    }

    @Test
    void countWrapsAGroupedQuery() {
        db.db().table("users").select("team").groupBy("team").orderBy("team").limit(3).getCountForPagination();

        assertEquals("select count(*) as \"aggregate\" from (select \"team\" from \"users\" group by \"team\")"
                + " as \"aggregate_table\"", db.last().sql());
    }

    @Test
    void countWrapsAHavingQueryWithItsBindings() {
        db.db().table("users").select("team").where("active", true).groupBy("team").having("team", ">", 1)
                .getCountForPagination();

        assertEquals("select count(*) as \"aggregate\" from (select \"team\" from \"users\" where \"active\" = ?"
                + " group by \"team\" having \"team\" > ?) as \"aggregate_table\"", db.last().sql());
        assertEquals(List.of(true, 1), db.last().bindings());
    }

    @Test
    void countOfAGroupedJoinSelectsTheTablesColumns() {
        db.db().table("users").join("posts", "users.id", "=", "posts.user_id").groupBy("users.id")
                .getCountForPagination();

        assertEquals("select count(*) as \"aggregate\" from (select \"users\".* from \"users\" inner join \"posts\""
                + " on \"users\".\"id\" = \"posts\".\"user_id\" group by \"users\".\"id\") as \"aggregate_table\"",
                db.last().sql());
    }

    @Test
    void countOfAUnionDropsTheUnionsOrderAndLimit() {
        db.db().table("a").where("x", 1).union(db.db().table("b").where("y", 2)).orderBy("x").limit(5)
                .getCountForPagination();

        assertEquals("select count(*) as \"aggregate\" from ((select * from \"a\" where \"x\" = ?) union"
                + " (select * from \"b\" where \"y\" = ?)) as \"temp_table\"", db.last().sql());
        assertEquals(List.of(1, 2), db.last().bindings());
    }

    // Laravel counts every row of a distinct query; Diwan counts the distinct rows.
    @Test
    void countOfADistinctSelectCountsTheDistinctRows() {
        db.db().table("users").distinct().select("name").orderBy("name").limit(5).offset(5)
                .getCountForPagination();

        assertEquals("select count(*) as \"aggregate\" from (select distinct \"name\" from \"users\")"
                + " as \"aggregate_table\"", db.last().sql());
    }

    @Test
    void countOfAMultiColumnDistinctSelectCountsTheDistinctPairs() {
        db.db().table("users").distinct().select("name", "team").where("a", 1).getCountForPagination();

        assertEquals("select count(*) as \"aggregate\" from (select distinct \"name\", \"team\" from \"users\""
                + " where \"a\" = ?) as \"aggregate_table\"", db.last().sql());
        assertEquals(List.of(1), db.last().bindings());
    }

    @Test
    void countOfADistinctJoinSelectsTheTablesColumns() {
        db.db().table("users").distinct().join("posts", "users.id", "=", "posts.user_id").getCountForPagination();

        assertEquals("select count(*) as \"aggregate\" from (select distinct \"users\".* from \"users\""
                + " inner join \"posts\" on \"users\".\"id\" = \"posts\".\"user_id\") as \"aggregate_table\"",
                db.last().sql());
    }

    @Test
    void countReadsTheAggregateAndZeroWithoutARow() {
        db.returns("select count(*) as \"aggregate\" from \"users\"", List.of(row("aggregate", 42)));

        assertEquals(42L, db.db().table("users").getCountForPagination());
        assertEquals(0L, db.db().table("posts").getCountForPagination());
    }

    // ---- paginate ----

    @Test
    void paginateCountsThenReadsThePage() {
        db.returns("select count(*) as \"aggregate\" from \"users\"", List.of(row("aggregate", 5)));
        String page2 = db.db().table("users").orderBy("id").offset(2).limit(2).toSql();
        db.returns(page2, List.of(row("id", 3), row("id", 4)));

        LengthAwarePage page = db.db().table("users").orderBy("id").paginate(2, 2);

        assertEquals(List.of(row("id", 3), row("id", 4)), page.items());
        assertEquals(5, page.total());
        assertEquals(2, page.perPage());
        assertEquals(2, page.currentPage());
        assertEquals(3, page.lastPage());
        assertTrue(page.hasMorePages());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }

    @Test
    void paginateSkipsTheSelectWhenNothingMatches() {
        LengthAwarePage page = db.db().table("users").paginate(10);

        assertEquals(1, db.calls.size());
        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertEquals(1, page.lastPage());
        assertFalse(page.hasMorePages());
    }

    @Test
    void paginateBeyondTheLastPageGivesNoRowsAndTheTotal() {
        db.returns("select count(*) as \"aggregate\" from \"users\"", List.of(row("aggregate", 3)));

        LengthAwarePage page = db.db().table("users").paginate(2, 9, List.of("id", "name"));

        assertTrue(page.items().isEmpty());
        assertEquals(3, page.total());
        assertEquals("select \"id\", \"name\" from \"users\" limit 2 offset 16", db.last().sql());
    }

    @Test
    void paginateRefusesBadPageNumbers() {
        assertThrows(IllegalArgumentException.class, () -> db.db().table("users").paginate(0));
        assertThrows(IllegalArgumentException.class, () -> db.db().table("users").paginate(5, 0));
        assertThrows(IllegalArgumentException.class, () -> db.db().table("users").simplePaginate(0));
        assertThrows(IllegalArgumentException.class, () -> db.db().table("users").cursorPaginate(0));
    }

    @Test
    void simplePaginateReadsOneExtraRowToKnowAboutMore() {
        String sql = db.db().table("users").offset(2).limit(3).toSql();
        db.returns(sql, List.of(row("id", 3), row("id", 4), row("id", 5)));

        SimplePage page = db.db().table("users").simplePaginate(2, 2);

        assertEquals(List.of(row("id", 3), row("id", 4)), page.items());
        assertTrue(page.hasMorePages());
        assertEquals(2, page.perPage());
        assertEquals(2, page.currentPage());
        assertFalse(db.db().table("users").simplePaginate(2, 9).hasMorePages());
    }

    // ---- cursor pagination ----

    @Test
    void cursorPaginateNeedsAnOrder() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> db.db().table("users").cursorPaginate(2));

        assertEquals("You must specify an orderBy clause when using this function.", error.getMessage());
    }

    @Test
    void firstCursorPageReadsOneExtraRowAndPointsOnlyForward() {
        Builder query = db.db().table("users").orderBy("a").orderBy("b", "desc");
        db.returns("select * from \"users\" order by \"a\" asc, \"b\" desc limit 3",
                List.of(row("a", 1L, "b", 9L), row("a", 1L, "b", 8L), row("a", 2L, "b", 7L)));

        CursorPage page = query.cursorPaginate(2);

        assertEquals(2, page.items().size());
        assertFalse(page.previousCursor().isPresent());
        assertEquals(params("a", 1L, "b", 8L), page.nextCursor().get().parameters());
        assertTrue(page.nextCursor().get().pointsToNextItems());
        assertEquals("select * from \"users\" order by \"a\" asc, \"b\" desc", query.toSql());
    }

    @Test
    void nextCursorAddsLaravelsNestedKeysetCondition() {
        Builder query = db.db().table("users").orderBy("a").orderBy("b", "desc");

        query.cursorPaginate(2, new Cursor(params("a", 1L, "b", 8L), true));

        assertEquals("select * from \"users\" where (\"a\" > ? or (\"a\" = ? and (\"b\" < ?)))"
                + " order by \"a\" asc, \"b\" desc limit 3", db.last().sql());
        assertEquals(List.of(1L, 1L, 8L), db.last().bindings());
    }

    @Test
    void previousCursorFlipsTheOrdersAndReversesTheRows() {
        String sql = "select * from \"users\" where (\"a\" < ? or (\"a\" = ? and (\"b\" > ?)))"
                + " order by \"a\" desc, \"b\" asc limit 3";
        db.returns(sql, List.of(row("a", 1L, "b", 9L), row("a", 0L, "b", 1L)));

        CursorPage page = db.db().table("users").orderBy("a").orderBy("b", "desc")
                .cursorPaginate(2, new Cursor(params("a", 2L, "b", 7L), false));

        assertEquals(List.of(row("a", 0L, "b", 1L), row("a", 1L, "b", 9L)), page.items());
        assertFalse(page.previousCursor().isPresent());
        assertEquals(params("a", 1L, "b", 9L), page.nextCursor().get().parameters());
    }

    @Test
    void cursorColumnsResolveSelectAliasesAndTableNames() {
        Builder query = db.db().table("users").select("users.id as uid", "name").orderBy("uid")
                .orderBy("users.name");

        query.cursorPaginate(5, new Cursor(params("uid", 4L, "users.name", "x"), true));

        assertEquals("select \"users\".\"id\" as \"uid\", \"name\" from \"users\" where (\"users\".\"id\" > ? or"
                + " (\"users\".\"id\" = ? and (\"users\".\"name\" > ?))) order by \"uid\" asc, \"users\".\"name\" asc"
                + " limit 6", db.last().sql());
        query.cursorPaginate(1, new Cursor(params("uid", 4L, "users.name", "x"), true));
        db.returns(db.last().sql(), List.of(row("uid", 5L, "name", "y"), row("uid", 6L, "name", "z")));
        CursorPage page = query.cursorPaginate(1, new Cursor(params("uid", 4L, "users.name", "x"), true));
        assertEquals(params("uid", 5L, "users.name", "y"), page.nextCursor().get().parameters());
    }

    @Test
    void aCursorForOtherColumnsIsRefused() {
        Builder query = db.db().table("users").orderBy("id");

        assertThrows(IllegalArgumentException.class,
                () -> query.cursorPaginate(2, new Cursor(params("name", "x"), true)));
    }

    @Test
    void cursorPaginateAppliesTheConditionToEveryUnionPart() {
        Builder query = db.db().table("a").union(db.db().table("b")).orderBy("id");

        query.cursorPaginate(2, new Cursor(params("id", 3L), true));

        assertEquals("(select * from \"a\" where (\"id\" > ?)) union (select * from \"b\" where (\"id\" > ?))"
                + " order by \"id\" asc limit 3", db.last().sql());
        assertEquals(List.of(3L, 3L), db.last().bindings());
    }
}
