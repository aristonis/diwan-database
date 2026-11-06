package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteChunkingTest {

    private static final int USERS = 25;

    @TempDir
    Path folder;

    private QueryFactory db;

    @BeforeEach
    void seed() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("chunk.db"));
        Connection connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        Schema schema = SQLiteSchema.of(connection);
        schema.create("users", t -> {
            t.id();
            t.string("name");
            t.integer("team");
        });
        schema.create("posts", t -> {
            t.id();
            t.integer("user_id");
            t.string("title");
        });
        List<Map<String, Object>> users = new ArrayList<>();
        List<Map<String, Object>> posts = new ArrayList<>();
        for (int i = 1; i <= USERS; i++) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("name", "user" + i);
            user.put("team", i % 3);
            users.add(user);
            Map<String, Object> post = new LinkedHashMap<>();
            post.put("user_id", i);
            post.put("title", "post" + i);
            posts.add(post);
        }
        db.table("users").insert(users);
        db.table("posts").insert(posts);
    }

    private static List<Long> ids(Iterable<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ids.add(((Number) row.get("id")).longValue());
        }
        return ids;
    }

    private static List<Long> range(int from, int to) {
        List<Long> ids = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            ids.add(i);
        }
        return ids;
    }

    @Test
    void chunkVisitsEveryRowOnceInOrderAndHonorsTheLimit() {
        List<Long> seen = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        Builder query = db.table("users").orderBy("id");

        query.chunk(7, rows -> {
            sizes.add(rows.size());
            seen.addAll(ids(rows));
            return true;
        });

        assertEquals(range(1, USERS), seen);
        sizes.clear();
        db.table("users").orderBy("id").limit(25).chunk(10, rows -> sizes.add(rows.size()));
        assertEquals(java.util.Arrays.asList(10, 10, 5), sizes);
    }

    @Test
    void chunkStopsOnFalseAndLeavesTheBuilderUnchanged() {
        Builder query = db.table("users").where("team", ">=", 0).orderBy("id");
        String before = query.toSql();
        List<Integer> pages = new ArrayList<>();

        boolean finished = query.chunk(10, rows -> pages.add(rows.size()) && pages.size() < 2);

        assertFalse(finished);
        assertEquals(2, pages.size());
        assertEquals(before, query.toSql());
        assertEquals(USERS, query.get().size());
    }

    @Test
    void chunkByIdOverAJoinWithAQualifiedKeyAndAnAlias() {
        List<Long> seen = new ArrayList<>();
        Builder query = db.table("users").join("posts", "users.id", "=", "posts.user_id")
                .select("users.id as id", "posts.title");

        query.chunkById(4, rows -> seen.addAll(ids(rows)), "users.id", "id");

        assertEquals(range(1, USERS), seen);
    }

    @Test
    void chunkByIdDescAndEachByIdWalkEveryRow() {
        List<Long> desc = new ArrayList<>();
        db.table("users").chunkByIdDesc(6, rows -> desc.addAll(ids(rows)));
        List<Long> each = new ArrayList<>();
        db.table("users").eachById(row -> each.add(((Number) row.get("id")).longValue()), 4);

        List<Long> expected = range(1, USERS);
        java.util.Collections.reverse(expected);
        assertEquals(expected, desc);
        assertEquals(range(1, USERS), each);
    }

    @Test
    void chunkByIdWithoutTheKeyInTheRowsFails() {
        assertThrows(IllegalStateException.class,
                () -> db.table("users").select("name").chunkById(5, rows -> true));
    }

    @Test
    void lazyAndLazyByIdYieldEveryRowOnce() {
        assertEquals(range(1, USERS), ids(db.table("users").orderBy("id").lazy(4)));
        assertEquals(range(1, USERS), ids(db.table("users").lazyById(4)));
        List<Long> expected = range(1, USERS);
        java.util.Collections.reverse(expected);
        assertEquals(expected, ids(db.table("users").lazyByIdDesc(4)));
        assertEquals(range(1, 10), ids(db.table("users").orderBy("id").limit(10).lazy(4)));
    }

    @Test
    void builderCursorStreamsEveryRow() {
        try (QueryCursor<Map<String, Object>> cursor = db.table("users").where("team", 0).orderBy("id").cursor()) {
            assertEquals(java.util.Arrays.asList(3L, 6L, 9L, 12L, 15L, 18L, 21L, 24L), ids(cursor));
        }
    }
}
