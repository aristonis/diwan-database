package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.CursorModelPage;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.eloquent.SimpleModelPage;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Country;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The iterators and simple pages of a hasManyThrough relation. Every row an iterator visits or a page
 * holds is a related model without the hidden through key, and each read orders by the qualified
 * related key when the relation has no order.
 */
class ThroughIterationTest {

    private static final String KEY_ORDER = " order by \"posts\".\"id\" asc";

    private static final String THROUGH_KEY = "laravel_through_key";

    private ThroughDb db;

    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
        db.seed();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void chunkOrdersByTheQualifiedKey() {
        HasManyThrough<Post> posts = postsOf(1);
        List<List<Object>> chunks = new ArrayList<>();

        boolean completed = posts.chunk(2, page -> chunks.add(page.modelKeys()));

        assertTrue(completed);
        assertEquals(Arrays.asList(Arrays.asList(1, 2), Collections.singletonList(3)), chunks);
        assertTrue(db.log.sql().get(0).contains(KEY_ORDER), db.log.sql().get(0));
    }

    @Test
    void chunkByIdUsesQualifiedColumnAndBareAlias() {
        HasManyThrough<Post> posts = postsOf(1);
        List<Object> visited = new ArrayList<>();

        boolean completed = posts.chunkById(2, page -> visited.addAll(page.modelKeys()));

        assertTrue(completed);
        assertEquals(Arrays.asList(1, 2, 3), visited);
        assertTrue(db.log.sql().get(0).contains(KEY_ORDER), db.log.sql().get(0));
        assertTrue(db.log.sql().get(1).contains("\"posts\".\"id\" > ?"), db.log.sql().get(1));
    }

    @Test
    void callbackFalseStopsChunking() {
        HasManyThrough<Post> posts = postsOf(1);
        List<List<Object>> chunks = new ArrayList<>();

        boolean completed = posts.chunk(1, page -> {
            chunks.add(page.modelKeys());
            return false;
        });

        assertFalse(completed);
        assertEquals(Collections.singletonList(Collections.singletonList(1)), chunks);
    }

    @Test
    void lazyAndCursorReadTheSameRows() {
        HasManyThrough<Post> posts = postsOf(1);

        List<Object> lazy = idsOf(posts.lazy());
        List<Object> lazyById = idsOf(posts.lazyById(2));
        List<Object> cursor;
        try (QueryCursor<Post> rows = posts.cursor()) {
            cursor = idsOf(rows);
        }

        assertEquals(Arrays.asList(1, 2, 3), lazy);
        assertEquals(Arrays.asList(1, 2, 3), lazyById);
        assertEquals(Arrays.asList(1, 2, 3), cursor);
        for (Post post : posts.lazy()) {
            assertFalse(post.getAttributes().containsKey(THROUGH_KEY));
        }
    }

    @Test
    void cursorClosesOnEarlyExit() {
        Iterator<Post> iterator;
        try (QueryCursor<Post> rows = postsOf(1).cursor()) {
            iterator = rows.iterator();
            assertEquals(1, iterator.next().getAttribute("id"));
        }

        assertFalse(iterator.hasNext());
    }

    @Test
    void simplePaginateHasNoCountQuery() {
        HasManyThrough<Post> posts = postsOf(1).orderBy("posts.id");

        SimpleModelPage<Post> page = posts.simplePaginate(1, 2);

        assertEquals(Collections.singletonList(2), page.items().modelKeys());
        assertTrue(page.hasMorePages());
        assertEquals(1, db.log.sql().size());
        assertFalse(db.log.sql().get(0).contains("count("), db.log.sql().get(0));
        assertNoThroughKey(page.items());
    }

    @Test
    void cursorPaginateWalksPages() {
        HasManyThrough<Post> posts = postsOf(1).orderBy("posts.id");

        CursorModelPage<Post> first = posts.cursorPaginate(2);
        CursorModelPage<Post> second = posts.cursorPaginate(2, first.nextCursor().get());

        assertEquals(Arrays.asList(1, 2), first.items().modelKeys());
        assertTrue(first.nextCursor().isPresent());
        assertEquals(Collections.singletonList(3), second.items().modelKeys());
        assertFalse(second.nextCursor().isPresent());
        assertNoThroughKey(first.items());
        assertNoThroughKey(second.items());
    }

    private HasManyThrough<Post> postsOf(int countryId) {
        Country country = Country.QUERY.on(db.db).findOrFail(countryId);
        HasManyThrough<Post> posts = country.posts();
        db.log.clear();
        return posts;
    }

    private static List<Object> idsOf(Iterable<Post> posts) {
        List<Object> ids = new ArrayList<>();
        for (Post post : posts) {
            ids.add(post.getAttribute("id"));
        }
        return ids;
    }

    private static void assertNoThroughKey(ModelCollection<Post> items) {
        for (Post item : items) {
            assertFalse(item.getAttributes().containsKey(THROUGH_KEY));
        }
    }
}
