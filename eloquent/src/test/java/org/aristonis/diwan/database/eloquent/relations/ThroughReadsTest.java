package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.exception.ModelNotFoundException;
import org.aristonis.diwan.database.eloquent.exception.MultipleRecordsFoundException;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Country;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read methods of a hasManyThrough relation: finders, first rows, fallbacks, and the aggregate
 * reads. Each read runs on a copy of the relation's query, so the relation keeps its own SQL and its
 * own constraints.
 */
class ThroughReadsTest {

    private static final String POSTS_OF_COUNTRY_1 = "select \"posts\".*, \"users\".\"country_id\" as "
            + "\"laravel_through_key\" from \"posts\" inner join \"users\" on \"users\".\"id\" = "
            + "\"posts\".\"user_id\" where \"users\".\"country_id\" = ?";

    private static final String FIND_ONE = POSTS_OF_COUNTRY_1 + " and \"posts\".\"id\" = ? limit 1";

    private static final String FIRST_ROW = POSTS_OF_COUNTRY_1 + " limit 1";

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
    void secondFindOnSameRelationIsIndependent() {
        HasManyThrough<Post> posts = postsOf(1);

        Optional<Post> first = posts.find(1);
        Optional<Post> second = posts.find(2);

        assertEquals(1, first.get().getAttribute("id"));
        assertEquals(2, second.get().getAttribute("id"));
        assertEquals(Arrays.asList(FIND_ONE, FIND_ONE), db.log.sql());
    }

    @Test
    void firstDoesNotLimitLaterGet() {
        HasManyThrough<Post> posts = postsOf(1);

        Optional<Post> first = posts.first();
        ModelCollection<Post> all = posts.get();

        assertTrue(first.isPresent());
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 3)), new HashSet<>(all.modelKeys()));
        assertEquals(Arrays.asList(FIRST_ROW, POSTS_OF_COUNTRY_1), db.log.sql());
    }

    @Test
    void findManyWithNoIdsRunsNoQuery() {
        HasManyThrough<Post> posts = postsOf(1);

        ModelCollection<Post> found = posts.findMany(Collections.emptyList());

        assertTrue(found.isEmpty());
        assertEquals(Collections.emptyList(), db.log.sql());
    }

    @Test
    void findOrFailCountsDistinctIds() {
        ModelCollection<Post> found = postsOf(1).findOrFail(Arrays.asList(1, 1, 2));

        assertEquals(new HashSet<>(Arrays.asList(1, 2)), new HashSet<>(found.modelKeys()));
        assertEquals(2, found.size());
    }

    @Test
    void findOrFailListsMissingIds() {
        HasManyThrough<Post> posts = postsOf(1);

        ModelNotFoundException failure = assertThrows(ModelNotFoundException.class,
                () -> posts.findOrFail(Arrays.asList(1, 99)));

        assertEquals(Collections.singletonList("99"), failure.getIds());
        assertEquals(Post.class.getName(), failure.getModel());
    }

    @Test
    void findIgnoresRowsOfOtherParents() {
        Optional<Post> otherCountry = postsOf(1).find(4);

        assertFalse(otherCountry.isPresent());
    }

    @Test
    void findQualifiesTheKey() {
        HasManyThrough<Post> posts = postsOf(1);

        Optional<Post> found = posts.find(1);

        assertEquals(1, found.get().getAttribute("id"));
        assertEquals(FIND_ONE, db.log.sql().get(0));
    }

    @Test
    void findSoleRefusesDuplicateJoinRows() {
        HasManyThrough<Post> posts = postsOf(1);
        posts.getQuery().join("users as twin", "twin.country_id", "=", "users.country_id");

        MultipleRecordsFoundException failure = assertThrows(MultipleRecordsFoundException.class,
                () -> posts.findSole(1));

        assertEquals(2, failure.getCount());
    }

    @Test
    void getWithColumnsKeepsThroughKey() {
        HasManyThrough<Post> posts = postsOf(1);

        ModelCollection<Post> titles = posts.get("title");

        assertEquals(3, titles.size());
        for (Post title : titles) {
            assertEquals(Collections.singleton("title"), title.getAttributes().keySet());
        }
        assertEquals(Collections.singletonList("select \"title\", \"users\".\"country_id\" as "
                + "\"laravel_through_key\" from \"posts\" inner join \"users\" on \"users\".\"id\" = "
                + "\"posts\".\"user_id\" where \"users\".\"country_id\" = ?"), db.log.sql());
    }

    @Test
    void declaredSelectIsKept() {
        HasManyThrough<Post> posts = postsOf(1).select("posts.title");

        ModelCollection<Post> found = posts.get();

        assertEquals(3, found.size());
        for (Post post : found) {
            assertEquals(Collections.singleton("title"), post.getAttributes().keySet());
        }
        assertTrue(db.log.sql().get(0).startsWith("select \"posts\".\"title\", \"users\".\"country_id\""),
                db.log.sql().get(0));
    }

    @Test
    void firstOrAndFindOrUseTheFallback() {
        Post fallback = ThroughModels.Post.QUERY.on(db.db).newModel();

        Post firstFallback = postsOf(3).firstOr(() -> fallback);
        Post findFallback = postsOf(1).findOr(99, () -> fallback);

        assertSame(fallback, firstFallback);
        assertSame(fallback, findFallback);
    }

    @Test
    void takeLimitsTheRows() {
        HasManyThrough<Post> posts = postsOf(1).orderBy("posts.id").take(2);

        ModelCollection<Post> found = posts.get();

        assertEquals(Arrays.asList(1, 2), found.modelKeys());
        assertTrue(db.log.sql().get(0).endsWith(" order by \"posts\".\"id\" asc limit 2"), db.log.sql().get(0));
    }

    @Test
    void findOrAndFirstOrReturnTheRowOnAHit() {
        HasManyThrough<Post> posts = postsOf(1);
        AtomicInteger calls = new AtomicInteger();
        Post fallback = ThroughModels.Post.QUERY.on(db.db).newModel();

        Post found = posts.findOr(2, () -> countedFallback(calls, fallback));
        Post firstFound = posts.firstOr(() -> countedFallback(calls, fallback));

        assertEquals(2, found.getAttribute("id"));
        assertTrue(firstFound.exists());
        assertEquals(0, calls.get());
    }

    @Test
    void aggregatesLeaveTheRelationQueryUnchanged() {
        HasManyThrough<Post> posts = postsOf(1);
        String before = posts.getQuery().toSql();
        List<Object> bindingsBefore = posts.getQuery().getBindings();

        // The relation has no aggregate methods, so the aggregates run on getQuery().
        assertEquals(3, posts.getQuery().count());
        assertUnchanged(posts, before, bindingsBefore);
        assertTrue(posts.getQuery().exists());
        assertUnchanged(posts, before, bindingsBefore);
        assertEquals(6, posts.getQuery().toBase().sum("posts.id").longValue());
        assertUnchanged(posts, before, bindingsBefore);
        assertEquals(3, posts.getQuery().pluck("posts.title").size());
        assertUnchanged(posts, before, bindingsBefore);
        assertTrue(posts.getQuery().value("posts.title").isPresent());
        assertUnchanged(posts, before, bindingsBefore);
        assertTrue(posts.first().isPresent());
        assertUnchanged(posts, before, bindingsBefore);
        assertTrue(posts.find(1).isPresent());
        assertUnchanged(posts, before, bindingsBefore);
        assertEquals(3L, posts.paginate(2).total());
        assertUnchanged(posts, before, bindingsBefore);

        db.log.clear();
        assertEquals(3, posts.get().size());
        assertEquals(1, db.log.sql().size());
        assertEquals("select \"posts\".*, \"users\".\"country_id\" as \"laravel_through_key\" from \"posts\""
                + " inner join \"users\" on \"users\".\"id\" = \"posts\".\"user_id\""
                + " where \"users\".\"country_id\" = ?", db.log.sql().get(0));
    }

    private HasManyThrough<Post> postsOf(int countryId) {
        Country country = Country.QUERY.on(db.db).findOrFail(countryId);
        HasManyThrough<Post> posts = country.posts();
        db.log.clear();
        return posts;
    }

    private static void assertUnchanged(HasManyThrough<Post> posts, String sql, List<Object> bindings) {
        assertEquals(sql, posts.getQuery().toSql());
        assertEquals(bindings, posts.getQuery().getBindings());
    }

    private static Post countedFallback(AtomicInteger calls, Post fallback) {
        calls.incrementAndGet();
        return fallback;
    }
}
