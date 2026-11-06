package org.aristonis.diwan.database.query;

import static org.aristonis.diwan.database.query.RecordingConnection.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkQueriesTest {

    private static final String ORDER_MESSAGE = "You must specify an orderBy clause when using this function.";

    private final RecordingConnection db = new RecordingConnection();

    private Builder users() {
        return db.db().table("users").orderBy("id");
    }

    private String page(int offset, int limit) {
        return users().offset(offset).limit(limit).toSql();
    }

    private List<String> selects() {
        List<String> sql = new ArrayList<>();
        for (RecordingConnection.Call call : db.calls) {
            sql.add(call.sql());
        }
        return sql;
    }

    private void scriptPages() {
        db.returns(page(0, 2), List.of(row("id", 1L), row("id", 2L)));
        db.returns(page(2, 2), List.of(row("id", 3L), row("id", 4L)));
        db.returns(page(4, 2), List.of(row("id", 5L)));
    }

    @Test
    void chunkWithoutAnOrderIsRefusedWithLaravelsMessage() {
        Builder query = db.db().table("users");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> query.chunk(2, rows -> true));

        assertEquals(ORDER_MESSAGE, error.getMessage());
        assertThrows(IllegalStateException.class, () -> query.each(row -> true));
        assertThrows(IllegalStateException.class, () -> query.lazy(2));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    void chunkSizeBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> users().chunk(0, rows -> true));
        assertThrows(IllegalArgumentException.class, () -> users().lazy(0));
        assertThrows(IllegalArgumentException.class, () -> users().chunkById(0, rows -> true));
        assertThrows(IllegalArgumentException.class, () -> users().lazyById(0));
    }

    @Test
    void chunkVisitsEveryPageAndStopsAfterAShortOne() {
        scriptPages();
        List<Integer> sizes = new ArrayList<>();

        boolean finished = users().chunk(2, rows -> sizes.add(rows.size()));

        assertTrue(finished);
        assertEquals(List.of(2, 2, 1), sizes);
        assertEquals(List.of(page(0, 2), page(2, 2), page(4, 2)), selects());
    }

    @Test
    void chunkStopsWhenTheCallbackSaysSo() {
        scriptPages();

        boolean finished = users().chunk(2, rows -> false);

        assertFalse(finished);
        assertEquals(1, db.calls.size());
    }

    @Test
    void chunkHonorsTheUsersLimitAndOffset() {
        Builder query = users().offset(1).limit(5);

        query.chunk(2, rows -> true);

        assertEquals(List.of(page(1, 2)), selects());
        db.calls.clear();
        db.returns(page(1, 2), List.of(row("id", 2L), row("id", 3L)));
        db.returns(page(3, 2), List.of(row("id", 4L), row("id", 5L)));
        db.returns(page(5, 1), List.of(row("id", 6L)));
        List<Object> ids = new ArrayList<>();

        query.each(row -> ids.add(row.get("id")), 2);

        assertEquals(List.of(2L, 3L, 4L, 5L, 6L), ids);
        assertEquals(List.of(page(1, 2), page(3, 2), page(5, 1)), selects());
    }

    @Test
    void chunkLeavesTheBuilderAsItWas() {
        scriptPages();
        Builder query = users().where("active", true);
        String before = query.toSql();
        List<Object> bindings = query.getBindings();

        query.chunk(2, rows -> true);
        query.lazy(2).forEach(row -> { });

        assertEquals(before, query.toSql());
        assertEquals(bindings, query.getBindings());
    }

    @Test
    void eachStopsOnFalseAndChunkMapCollectsEveryRow() {
        scriptPages();
        List<Object> seen = new ArrayList<>();

        boolean finished = users().each(row -> seen.add(row.get("id")) && !row.get("id").equals(3L), 2);
        List<Object> mapped = users().chunkMap(row -> ((Long) row.get("id")) * 10, 2);

        assertFalse(finished);
        assertEquals(List.of(1L, 2L, 3L), seen);
        assertEquals(List.of(10L, 20L, 30L, 40L, 50L), mapped);
    }

    @Test
    void chunkByIdPagesAfterTheLastKeyOnClones() {
        Builder query = db.db().table("users");
        String first = db.db().table("users").whereNotNull("id").orderBy("id").limit(2).toSql();
        String next = db.db().table("users").where("id", ">", 2L).orderBy("id").limit(2).toSql();
        db.returns(first, List.of(row("id", 1L), row("id", 2L)));
        db.returns(next, List.of(row("id", 3L)));
        List<Integer> sizes = new ArrayList<>();

        assertTrue(query.chunkById(2, rows -> sizes.add(rows.size())));

        assertEquals(List.of(2, 1), sizes);
        assertEquals(List.of(first, next), selects());
        assertEquals(List.of(2L), db.calls.get(1).bindings());
        assertEquals("select * from \"users\"", query.toSql());
    }

    @Test
    void chunkByIdDescPagesBeforeTheLastKey() {
        String first = db.db().table("users").whereNotNull("id").orderBy("id", "desc").limit(2).toSql();
        String next = db.db().table("users").where("id", "<", 9L).orderBy("id", "desc").limit(2).toSql();
        db.returns(first, List.of(row("id", 10L), row("id", 9L)));
        db.returns(next, List.of(row("id", 8L)));
        List<Object> ids = new ArrayList<>();

        db.db().table("users").chunkByIdDesc(2, rows -> {
            rows.forEach(row -> ids.add(row.get("id")));
            return true;
        });

        assertEquals(List.of(10L, 9L, 8L), ids);
    }

    @Test
    void chunkByIdReadsTheAliasFromRowsOfAJoin() {
        Builder query = db.db().table("users").join("posts", "users.id", "=", "posts.user_id")
                .select("users.id as id", "posts.title");
        String first = query.clone().whereNotNull("users.id").orderBy("users.id").limit(1).toSql();
        String next = query.clone().where("users.id", ">", 7L).orderBy("users.id").limit(1).toSql();
        db.returns(first, List.of(row("id", 7L, "title", "a")));

        query.chunkById(1, rows -> true, "users.id", "id");

        assertEquals(List.of(first, next), selects());
    }

    @Test
    void chunkByIdFailsClearlyWhenTheKeyIsMissing() {
        String first = db.db().table("users").whereNotNull("id").orderBy("id").limit(2).toSql();
        db.returns(first, List.of(row("name", "a")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> db.db().table("users").chunkById(2, rows -> true));

        assertTrue(error.getMessage().contains("[id]"));
    }

    @Test
    void chunkByIdAppliesTheUsersOffsetToTheFirstPageOnly() {
        Builder query = db.db().table("users").offset(3).limit(3);
        String first = db.db().table("users").offset(3).whereNotNull("id").orderBy("id").limit(2).toSql();
        String next = db.db().table("users").offset(0).where("id", ">", 5L).orderBy("id").limit(1).toSql();
        db.returns(first, List.of(row("id", 4L), row("id", 5L)));
        db.returns(next, List.of(row("id", 6L)));
        List<Object> ids = new ArrayList<>();

        query.eachById(row -> ids.add(row.get("id")), 2);

        assertEquals(List.of(4L, 5L, 6L), ids);
        assertEquals(List.of(first, next), selects());
    }

    @Test
    void lazyLoadsOnePageAtATime() {
        scriptPages();

        Iterator<Map<String, Object>> rows = users().lazy(2).iterator();

        assertTrue(db.calls.isEmpty());
        assertEquals(1L, rows.next().get("id"));
        assertEquals(1, db.calls.size());
        List<Object> rest = new ArrayList<>();
        rows.forEachRemaining(row -> rest.add(row.get("id")));
        assertEquals(List.of(2L, 3L, 4L, 5L), rest);
        assertEquals(3, db.calls.size());
        assertFalse(rows.hasNext());
    }

    @Test
    void lazyByIdAndLazyByIdDescWalkTheKeys() {
        String first = db.db().table("users").whereNotNull("id").orderBy("id").limit(2).toSql();
        String next = db.db().table("users").where("id", ">", 2L).orderBy("id").limit(2).toSql();
        db.returns(first, List.of(row("id", 1L), row("id", 2L)));
        db.returns(next, List.of(row("id", 3L)));
        List<Object> ids = new ArrayList<>();
        db.db().table("users").lazyById(2).forEach(row -> ids.add(row.get("id")));
        String desc = db.db().table("users").whereNotNull("id").orderBy("id", "desc").limit(2).toSql();
        db.returns(desc, List.of(row("id", 3L)));
        db.db().table("users").lazyByIdDesc(2).forEach(row -> ids.add(row.get("id")));

        assertEquals(List.of(1L, 2L, 3L, 3L), ids);
    }

    @Test
    void lazyByIdStopsOnAShortPageEvenWithoutTheKey() {
        String first = db.db().table("users").whereNotNull("id").orderBy("id").limit(2).toSql();
        db.returns(first, List.of(row("name", "a")));

        List<Map<String, Object>> rows = new ArrayList<>();
        db.db().table("users").lazyById(2).forEach(rows::add);

        assertEquals(1, rows.size());
    }

    @Test
    void cursorStreamsTheCompiledQuery() {
        Builder query = db.db().table("users").where("id", ">", 1);
        db.returns(query.toSql(), List.of(row("id", 2L)));

        try (QueryCursor<Map<String, Object>> cursor = query.cursor()) {
            assertEquals(2L, cursor.iterator().next().get("id"));
        }

        assertEquals(new RecordingConnection.Call("cursor", query.toSql(), List.of(1)), db.last());
    }
}
