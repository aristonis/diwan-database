package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Cursor;
import org.aristonis.diwan.database.query.CursorPage;
import org.aristonis.diwan.database.query.LengthAwarePage;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.query.SimplePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLitePaginationTest {

    private static final int USERS = 23;
    private static final int TEAMS = 4;

    @TempDir
    Path folder;

    private QueryFactory db;

    @BeforeEach
    void seed() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("pages.db"));
        Connection connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("users", t -> {
            t.id();
            t.string("name");
            t.integer("team");
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= USERS; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "user" + i);
            row.put("team", i % TEAMS);
            rows.add(row);
        }
        db.table("users").insert(rows);
    }

    private static List<Long> ids(List<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ids.add(((Number) row.get("id")).longValue());
        }
        return ids;
    }

    @Test
    void paginateGivesPagesTotalsAndAnEmptyPageBeyondTheEnd() {
        List<Long> all = new ArrayList<>();
        LengthAwarePage page = null;
        for (int number = 1; number <= 5; number++) {
            page = db.table("users").orderBy("id").paginate(5, number);
            all.addAll(ids(page.items()));
            assertEquals(USERS, page.total());
            assertEquals(5, page.lastPage());
        }
        assertEquals(ids(db.table("users").orderBy("id").get()), all);
        assertFalse(page.hasMorePages());
        LengthAwarePage beyond = db.table("users").paginate(5, 99);
        assertTrue(beyond.items().isEmpty());
        assertEquals(USERS, beyond.total());
        assertEquals(0, db.table("users").where("id", ">", 1000).paginate(5).total());
    }

    @Test
    void paginateCountsGroupsAndUnionRows() {
        assertEquals(TEAMS, db.table("users").select("team").groupBy("team").paginate(2).total());
        assertEquals(2, db.table("users").select("team").groupBy("team").having("team", ">", 1).paginate(2).total());
        Builder union = db.table("users").select("id").where("id", "<=", 3)
                .unionAll(db.table("users").select("id").where("id", ">", 20));
        assertEquals(6, union.paginate(4).total());
    }

    // A distinct query's total counts the distinct rows, not every matching row.
    @Test
    void paginateCountsDistinctRows() {
        assertEquals(TEAMS, db.table("users").distinct().select("team").orderBy("team").paginate(2).total());
        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("name", "user1");
        duplicate.put("team", 1);
        db.table("users").insert(duplicate);
        assertEquals(USERS, db.table("users").distinct().select("name", "team").paginate(5).total());
        assertEquals(USERS, db.table("users as u").distinct()
                .join("users as v", "u.team", "=", "v.team").select("u.name", "u.team").paginate(5).total());
        assertEquals(USERS + 1, db.table("users as u").distinct().join("users as v", "u.team", "=", "v.team")
                .select("u.*").paginate(5).total());
    }

    @Test
    void simplePaginateKnowsWhetherMoreRowsFollow() {
        SimplePage first = db.table("users").orderBy("id").simplePaginate(10, 1);
        SimplePage last = db.table("users").orderBy("id").simplePaginate(10, 3);

        assertEquals(10, first.items().size());
        assertTrue(first.hasMorePages());
        assertEquals(3, last.items().size());
        assertFalse(last.hasMorePages());
    }

    @Test
    void cursorPaginateWalksForwardAndBackWithOneOrderColumn() {
        walk(() -> db.table("users").orderBy("id"), 4);
    }

    @Test
    void cursorPaginateWalksForwardAndBackWithTwoOrderColumns() {
        walk(() -> db.table("users").orderBy("team").orderBy("id", "desc"), 5);
    }

    private void walk(java.util.function.Supplier<Builder> query, int perPage) {
        List<Long> expected = ids(query.get().get());
        List<List<Long>> forward = new ArrayList<>();
        CursorPage page = query.get().cursorPaginate(perPage);
        forward.add(ids(page.items()));
        while (page.nextCursor().isPresent()) {
            String token = page.nextCursor().get().encode();
            page = query.get().cursorPaginate(perPage, Cursor.decode(token));
            forward.add(ids(page.items()));
        }
        List<Long> flat = new ArrayList<>();
        forward.forEach(flat::addAll);
        assertEquals(expected, flat);

        List<List<Long>> backward = new ArrayList<>();
        backward.add(ids(page.items()));
        while (page.previousCursor().isPresent()) {
            page = query.get().cursorPaginate(perPage, Cursor.decode(page.previousCursor().get().encode()));
            backward.add(ids(page.items()));
        }
        Collections.reverse(backward);
        assertEquals(forward, backward);
    }
}
