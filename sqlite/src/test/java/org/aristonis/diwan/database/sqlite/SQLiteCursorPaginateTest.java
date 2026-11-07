package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Cursor;
import org.aristonis.diwan.database.query.CursorPage;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Cursor pagination against a real SQLite file: mixed orders, empty pages, distinct, joins and unions. */
class SQLiteCursorPaginateTest {

    private static final int USERS = 5;
    private static final int POSTS_PER_USER = 10;

    @TempDir
    Path folder;

    private QueryFactory db;

    @BeforeEach
    void createTables() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("cursor-pages.db"));
        Connection connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        Schema schema = SQLiteSchema.of(connection);
        schema.create("users", t -> {
            t.id();
            t.string("name");
        });
        schema.create("posts", t -> {
            t.id();
            t.string("title");
            t.integer("user_id");
            t.integer("team");
            t.integer("score");
            t.string("created_at");
        });
        schema.create("videos", t -> {
            t.id();
            t.string("created_at");
        });
        schema.create("news", t -> {
            t.id();
            t.string("created_at");
            t.integer("author_id");
        });
        schema.create("authors", t -> {
            t.id();
            t.integer("active");
        });
        schema.create("journal", t -> {
            t.id();
            t.date("day");
        });
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static List<Object> column(List<Map<String, Object>> rows, String name) {
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            values.add(row.get(name));
        }
        return values;
    }

    // Reads every page forward with perPage rows and returns what each page held, by the named column.
    private static List<List<Object>> forward(Supplier<Builder> query, int perPage, String name) {
        List<List<Object>> pages = new ArrayList<>();
        CursorPage page = query.get().cursorPaginate(perPage);
        pages.add(column(page.items(), name));
        while (page.nextCursor().isPresent()) {
            page = query.get().cursorPaginate(perPage, Cursor.decode(page.nextCursor().get().encode()));
            pages.add(column(page.items(), name));
        }
        return pages;
    }

    private static List<Object> flatten(List<List<Object>> pages) {
        List<Object> all = new ArrayList<>();
        pages.forEach(all::addAll);
        return all;
    }

    private void seedScores() {
        List<Map<String, Object>> rows = new ArrayList<>();
        int[] teams = {1, 2, 1, 3, 2, 1, 3, 2, 1};
        int[] scores = {5, 5, 9, 1, 5, 9, 1, 7, 5};
        for (int i = 0; i < teams.length; i++) {
            rows.add(row("title", "p" + i, "user_id", 1, "team", teams[i], "score", scores[i],
                    "created_at", "2024-01-0" + (i + 1)));
        }
        db.table("posts").insert(rows);
    }

    @Test
    void mixedAscendingAndDescendingOrdersWalkEveryRowOnceInQueryOrder() {
        seedScores();
        Supplier<Builder> query = () -> db.table("posts").orderBy("team").orderBy("score", "desc").orderBy("id");

        List<Object> expected = column(query.get().get(), "id");

        assertEquals(expected, flatten(forward(query, 2, "id")));
    }

    @Test
    void mixedOrdersWalkBackToTheSamePages() {
        seedScores();
        Supplier<Builder> query = () -> db.table("posts").orderBy("team").orderBy("score", "desc").orderBy("id");
        List<List<Object>> forward = forward(query, 4, "id");

        List<List<Object>> backward = new ArrayList<>();
        CursorPage page = query.get().cursorPaginate(4);
        while (page.nextCursor().isPresent()) {
            page = query.get().cursorPaginate(4, page.nextCursor().get());
        }
        backward.add(column(page.items(), "id"));
        while (page.previousCursor().isPresent()) {
            page = query.get().cursorPaginate(4, page.previousCursor().get());
            backward.add(column(page.items(), "id"));
        }
        Collections.reverse(backward);

        assertEquals(forward, backward);
    }

    @Test
    void noRowsGiveAnEmptyPageWithNoCursors() {
        CursorPage page = db.table("posts").orderBy("id").cursorPaginate(15);

        assertTrue(page.items().isEmpty());
        assertFalse(page.nextCursor().isPresent());
        assertFalse(page.previousCursor().isPresent());
    }

    @Test
    void distinctRowsArePagedWithoutDuplicates() {
        for (int i = 0; i < 3; i++) {
            db.table("posts").insert(List.of(row("title", "Hello world", "user_id", 1, "team", 1, "score", 1,
                    "created_at", "2024-01-01"), row("title", "Goodbye world", "user_id", 1, "team", 1, "score", 1,
                    "created_at", "2024-01-01")));
        }

        assertEquals(6, db.table("posts").distinct().orderBy("id").cursorPaginate(15).items().size());
        Supplier<Builder> titles = () -> db.table("posts").distinct().select("title").orderBy("title");
        assertEquals(List.of(List.<Object>of("Goodbye world"), List.<Object>of("Hello world")),
                forward(titles, 1, "title"));
        assertEquals(List.<Object>of("Goodbye world", "Hello world"), column(titles.get().cursorPaginate(15).items(),
                "title"));
    }

    @Test
    void distinctOverAJoinPagesEachRowOnce() {
        seedUsersWithPosts();
        Supplier<Builder> query = () -> db.table("users").join("posts", "posts.user_id", "=", "users.id")
                .distinct().select("users.*").orderBy("users.id");

        assertEquals(USERS, query.get().cursorPaginate(15).items().size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), longs(flatten(forward(query, 2, "id"))));
    }

    @Test
    void latestAndOldestOverAJoinPageByTheTimestamp() {
        seedUsersWithPosts();
        db.table("posts").where("id", ">", 3).delete();
        Supplier<Builder> base = () -> db.table("posts").join("users", "users.id", "=", "posts.user_id")
                .select("posts.*");

        CursorPage firstLatest = base.get().latest("posts.created_at").cursorPaginate(2);
        CursorPage secondLatest = base.get().latest("posts.created_at").cursorPaginate(2,
                firstLatest.nextCursor().orElseThrow());
        CursorPage firstOldest = base.get().oldest("posts.created_at").cursorPaginate(2);
        CursorPage secondOldest = base.get().oldest("posts.created_at").cursorPaginate(2,
                firstOldest.nextCursor().orElseThrow());

        assertEquals(List.<Object>of("Title 3", "Title 2"), column(firstLatest.items(), "title"));
        assertEquals(List.<Object>of("Title 1"), column(secondLatest.items(), "title"));
        assertEquals(List.<Object>of("Title 1", "Title 2"), column(firstOldest.items(), "title"));
        assertEquals(List.<Object>of("Title 3"), column(secondOldest.items(), "title"));
    }

    @Test
    void latestAndOldestOverAJoinFromADerivedTableOrderTheRows() {
        seedUsersWithPosts();
        db.table("posts").where("id", ">", 3).delete();
        Supplier<Builder> base = () -> db.query().fromSub(db.table("posts"), "p")
                .joinSub(db.table("users").select("id", "name"), "u", "u.id", "=", "p.user_id").select("p.*");

        assertEquals(List.<Object>of("Title 3", "Title 2", "Title 1"),
                base.get().latest("p.created_at").pluck("title"));
        assertEquals(List.<Object>of("Title 1", "Title 2", "Title 3"),
                base.get().oldest("p.created_at").pluck("title"));
        CursorPage first = base.get().latest("p.created_at").cursorPaginate(2);
        CursorPage second = base.get().latest("p.created_at").cursorPaginate(2, first.nextCursor().orElseThrow());
        assertEquals(List.<Object>of("Title 3", "Title 2"), column(first.items(), "title"));
        assertEquals(List.<Object>of("Title 1"), column(second.items(), "title"));
    }

    @Test
    void aUnionOfPartsWithJoinsAndSelectBindingsPagesInOrder() {
        db.table("authors").insert(List.of(row("active", 1), row("active", 0)));
        db.table("videos").insert(List.of(row("created_at", "2024-01-01"), row("created_at", "2024-01-03"),
                row("created_at", "2024-01-05")));
        db.table("news").insert(List.of(row("created_at", "2024-01-02", "author_id", 1),
                row("created_at", "2024-01-04", "author_id", 2), row("created_at", "2024-01-06", "author_id", 1)));
        Supplier<Builder> query = () -> db.table("videos").select("id", "created_at").selectRaw("? as type", List.of("video"))
                .union(db.table("news").select("news.id", "news.created_at").selectRaw("? as type", List.of("news"))
                        .join("authors", j -> j.on("authors.id", "=", "news.author_id").where("authors.active", 1)))
                .orderBy("created_at");

        List<Object> expected = column(query.get().get(), "created_at");

        assertEquals(List.of("2024-01-01", "2024-01-02", "2024-01-03", "2024-01-05", "2024-01-06"), expected);
        assertEquals(expected, flatten(forward(query, 2, "created_at")));
    }

    @Test
    void dateOrderedPagesWalk() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            entries.add(row("day", "2024-03-0" + (i / 2 + 1)));
        }
        db.table("journal").insert(entries);
        Supplier<Builder> query = () -> db.table("journal").orderBy("day", "desc").orderBy("id", "desc");

        assertEquals(List.of(7L, 6L, 5L, 4L, 3L, 2L, 1L), longs(flatten(forward(query, 2, "id"))));

        Cursor typed = new Cursor(Map.of("day", LocalDate.of(2024, 3, 2), "id", 4L), true);
        CursorPage page = query.get().cursorPaginate(2, typed);
        assertEquals(List.of(3L, 2L), longs(column(page.items(), "id")));
    }

    private void seedUsersWithPosts() {
        List<Map<String, Object>> users = new ArrayList<>();
        List<Map<String, Object>> posts = new ArrayList<>();
        for (int user = 1; user <= USERS; user++) {
            users.add(row("name", "user" + user));
            for (int n = 1; n <= POSTS_PER_USER; n++) {
                posts.add(row("title", "Title " + (posts.size() % 3 + 1), "user_id", user, "team", 1, "score", n,
                        "created_at", "2024-02-0" + (posts.size() % 3 + 1)));
            }
        }
        db.table("users").insert(users);
        db.table("posts").insert(posts);
    }

    private static List<Long> longs(List<Object> values) {
        List<Long> longs = new ArrayList<>();
        for (Object value : values) {
            longs.add(((Number) value).longValue());
        }
        return longs;
    }
}
